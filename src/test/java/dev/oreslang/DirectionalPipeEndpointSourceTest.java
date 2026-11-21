package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class DirectionalPipeEndpointSourceTest {
    private static void typechecks(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static IllegalArgumentException rejects(String source) {
        return assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void directionalChannelViewsAdmitOnlyTheirDeclaredDirection() {
        typechecks("""
                pub suspends fnc consume(ReadChannel<int> ch): int {
                    return readch ch;
                }

                pub suspends fnc produce(WriteChannel<int> ch): void {
                    writech ch, 1;
                    return;
                }
                """);

        IllegalArgumentException writeToReadOnly = rejects("""
                pub suspends fnc bad(ReadChannel<int> ch): void {
                    writech ch, 1;
                    return;
                }
                """);
        assertTrue(writeToReadOnly.getMessage().contains("WriteChannel"),
                writeToReadOnly::toString);

        IllegalArgumentException readFromWriteOnly = rejects("""
                pub suspends fnc bad(WriteChannel<int> ch): int {
                    return readch ch;
                }
                """);
        assertTrue(readFromWriteOnly.getMessage().contains("ReadChannel"),
                readFromWriteOnly::toString);
    }

    @Test
    void ordinaryClassMayImplementInputAndOutputProtocolsWithIndependentTypes() {
        typechecks("""
                define class Decoder
                    implements HasInputChannel<int>, HasOutputChannel<string>
                as
                    pub input: WriteChannel<int>;
                    pub output: ReadChannel<string>;
                end
                """);
    }

    @Test
    void protocolConformanceRequiresStablePropertyWithExactDirectionalView() {
        IllegalArgumentException wrongDirection = rejects("""
                define class BadSink implements HasInputChannel<int> as
                    pub input: ReadChannel<int>;
                end
                """);
        assertTrue(wrongDirection.getMessage().contains("public stable property 'input"),
                wrongDirection::toString);

        IllegalArgumentException missingOutput = rejects("""
                define class BadSource implements HasOutputChannel<int> as
                    pub input: WriteChannel<int>;
                end
                """);
        assertTrue(missingOutput.getMessage().contains("public stable property 'output"),
                missingOutput::toString);

        IllegalArgumentException rebindable = rejects("""
                define class RebindableSource implements HasOutputChannel<int> as
                    pub let ReadChannel<int> output;
                end
                """);
        assertTrue(rebindable.getMessage().contains("stable val/const slot"),
                rebindable::toString);
    }

    @Test
    void reservedPipeProtocolsCannotBeShadowedByGuestDeclarations() {
        IllegalArgumentException iface = rejects("""
                define interface HasInputChannel<T> as
                    input: WriteChannel<T>;
                end
                """);
        assertTrue(iface.getMessage().contains("reserved pipe endpoint interface"),
                iface::toString);

        IllegalArgumentException alias = rejects("""
                type ReadChannel<T> = Channel<T>;
                """);
        assertTrue(alias.getMessage().contains("reserved pipeline alias"),
                alias::toString);
    }

    @Test
    void actorClassesCannotSelfAssertPipeAuthorityAndEndpointCapabilitiesAreNotMailboxData() {
        IllegalArgumentException actorImpl = rejects("""
                define actor Bad implements HasInputChannel<int> as
                    receive(ActorMail<int> mail): void {
                        return;
                    }
                end
                """);
        assertTrue(actorImpl.getMessage().contains(
                        "ActorRef pipe endpoints are compiler-synthesized"),
                actorImpl::toString);

        IllegalArgumentException capabilityPayload = rejects("""
                define actor Bad as
                    receive(ActorMail<ReadChannel<int>> mail): void {
                        return;
                    }
                end
                """);
        assertTrue(capabilityPayload.getMessage().contains(
                        "cannot use ReadChannel across an actor boundary"),
                capabilityPayload::toString);

        IllegalArgumentException protocolPayload = rejects("""
                define actor Bad as
                    receive(ActorMail<HasOutputChannel<int>> mail): void {
                        return;
                    }
                end
                """);
        assertTrue(protocolPayload.getMessage().contains(
                        "cannot use HasOutputChannel across an actor boundary"),
                protocolPayload::toString);
    }

    @Test
    void endpointProtocolRequiresExplicitClassDeclarationAndNeverAcceptsActorRef() {
        // A matching public field is not compiler-validated protocol
        // conformance: ordinary classes must opt in explicitly.
        IllegalArgumentException structuralSpoof = rejects("""
                define class ShapeOnly as
                    pub output: ReadChannel<int>;
                end

                pub fnc consume(HasOutputChannel<int> source): void {
                    val ReadChannel<int> endpoint = source.output;
                    return;
                }

                pub fnc try_spoof(ShapeOnly source): void {
                    consume(source);
                    return;
                }
                """);
        assertTrue(structuralSpoof.getMessage().contains("argument 1"),
                structuralSpoof::toString);

        // ActorRef has its own authenticated, compiler-owned routing domain.
        // An actor handle must not become a guest channel protocol merely
        // because both kinds of endpoint can appear in pipe graphs.
        IllegalArgumentException actorSpoof = rejects("""
                define actor Worker as
                    receive(ActorMail<int> mail): void {
                        return;
                    }
                end

                pub fnc consume(HasOutputChannel<int> source): void {
                    val ReadChannel<int> endpoint = source.output;
                    return;
                }

                pub fnc try_spoof(ActorRef<Worker> worker): void {
                    consume(worker);
                    return;
                }
                """);
        assertTrue(actorSpoof.getMessage().contains("argument 1"),
                actorSpoof::toString);
    }

    @Test
    void genericAndInheritedEndpointProtocolsConcretizeBeforePipeChecking() {
        IllegalArgumentException bridge = rejects("""
                define class GenericSource<T> implements HasOutputChannel<T> as
                    pub output: ReadChannel<T>;
                end

                define class IntSource extends GenericSource<int> as
                end

                define class IntSink implements HasInputChannel<int> as
                    pub input: WriteChannel<int>;
                end

                pub fnc connect(IntSource source, IntSink sink): void {
                    val graph = source pipe sink;
                    return;
                }
                """);
        assertTrue(bridge.getMessage().contains(
                        "non-actor pipe transport requires bounded stream/channel bridge lowering"),
                bridge::toString);
    }

    @Test
    void endpointProtocolCannotBeSatisfiedByGetterMethod() {
        IllegalArgumentException getter = rejects("""
                define class MethodSource implements HasOutputChannel<int> as
                    pub output(): ReadChannel<int> {
                        val Channel<int> channel = Channel.new<int>(1);
                        return channel.output;
                    }
                end
                """);
        assertTrue(getter.getMessage().contains("public stable property 'output"),
                getter::toString);
    }

    @Test
    void directionalViewsCannotWidenBackToFullOrOppositeChannelCapabilities() {
        IllegalArgumentException full = rejects("""
                pub fnc widen(ReadChannel<int> source): Channel<int> {
                    return source;
                }
                """);
        assertTrue(full.getMessage().contains("return"), full::toString);

        IllegalArgumentException opposite = rejects("""
                pub fnc reverse(WriteChannel<int> sink): ReadChannel<int> {
                    return sink;
                }
                """);
        assertTrue(opposite.getMessage().contains("return"), opposite::toString);
    }

    @Test
    void heterogeneousTransformChecksAdjacentDomainsBeforeTransportLowering() {
        IllegalArgumentException bridge = rejects("""
                define class Source implements HasOutputChannel<int> as
                    pub output: ReadChannel<int>;
                end

                define class Transform
                    implements HasInputChannel<int>, HasOutputChannel<string>
                as
                    pub input: WriteChannel<int>;
                    pub output: ReadChannel<string>;
                end

                define class Sink implements HasInputChannel<string> as
                    pub input: WriteChannel<string>;
                end

                pub fnc connect(Source source, Transform transform, Sink sink): void {
                    val graph = source pipe transform pipe sink;
                    return;
                }
                """);
        assertTrue(bridge.getMessage().contains(
                        "non-actor pipe transport requires bounded stream/channel bridge lowering"),
                bridge::toString);
    }

    @Test
    void mismatchedAdjacentDomainsFailBeforeBridgeSelection() {
        IllegalArgumentException mismatch = rejects("""
                define class Source implements HasOutputChannel<int> as
                    pub output: ReadChannel<int>;
                end

                define class Sink implements HasInputChannel<string> as
                    pub input: WriteChannel<string>;
                end

                pub fnc connect(Source source, Sink sink): void {
                    val graph = source pipe sink;
                    return;
                }
                """);
        assertTrue(mismatch.getMessage().contains(
                        "pipe endpoint domains must match exactly"),
                mismatch::toString);
    }

    @Test
    void laterDomainMismatchIsDiagnosedBeforeFirstUnimplementedBridge() {
        IllegalArgumentException mismatch = rejects("""
                define class Source implements HasOutputChannel<int> as
                    pub output: ReadChannel<int>;
                end

                define class Transform
                    implements HasInputChannel<int>, HasOutputChannel<string>
                as
                    pub input: WriteChannel<int>;
                    pub output: ReadChannel<string>;
                end

                define class Sink implements HasInputChannel<int> as
                    pub input: WriteChannel<int>;
                end

                pub fnc connect(Source source, Transform transform, Sink sink): void {
                    val graph = source pipe transform pipe sink;
                    return;
                }
                """);
        assertTrue(mismatch.getMessage().contains("pipe endpoint domains must match exactly"),
                mismatch::toString);
        assertFalse(mismatch.getMessage().contains("bridge lowering"),
                mismatch::toString);
    }

    @Test
    void laterMissingInputIsDiagnosedBeforeFirstUnimplementedBridge() {
        IllegalArgumentException wrongDirection = rejects("""
                define class Source implements HasOutputChannel<int> as
                    pub output: ReadChannel<int>;
                end

                define class Transform
                    implements HasInputChannel<int>, HasOutputChannel<string>
                as
                    pub input: WriteChannel<int>;
                    pub output: ReadChannel<string>;
                end

                define class OtherSource implements HasOutputChannel<string> as
                    pub output: ReadChannel<string>;
                end

                pub fnc connect(
                    Source source, Transform transform, OtherSource other
                ): void {
                    val graph = source pipe transform pipe other;
                    return;
                }
                """);
        assertTrue(wrongDirection.getMessage().contains("no input channel"),
                wrongDirection::toString);
        assertFalse(wrongDirection.getMessage().contains("bridge lowering"),
                wrongDirection::toString);
    }

    @Test
    void protocolTypedValuesParticipateWithoutConcreteClassKnowledge() {
        IllegalArgumentException bridge = rejects("""
                pub fnc connect(
                    HasOutputChannel<int> source,
                    HasInputChannel<int> sink
                ): void {
                    val graph = source pipe sink;
                    return;
                }
                """);
        assertTrue(bridge.getMessage().contains(
                        "non-actor pipe transport requires bounded stream/channel bridge lowering"),
                bridge::toString);
    }

    @Test
    void laterTypeMismatchIsNotMaskedByEarlierUnimplementedBridge() {
        IllegalArgumentException mismatch = rejects("""
                define class Source implements HasOutputChannel<int> as
                    pub output: ReadChannel<int>;
                end

                define class Transform
                    implements HasInputChannel<int>, HasOutputChannel<string>
                as
                    pub input: WriteChannel<int>;
                    pub output: ReadChannel<string>;
                end

                define class WrongSink implements HasInputChannel<int> as
                    pub input: WriteChannel<int>;
                end

                pub fnc connect(
                    Source source,
                    Transform transform,
                    WrongSink sink
                ): void {
                    val graph = source pipe transform pipe sink;
                    return;
                }
                """);
        assertTrue(mismatch.getMessage().contains(
                        "pipe endpoint domains must match exactly"),
                mismatch::toString);
    }

    @Test
    void builtinEndpointProtocolsParticipateInOrdinaryAssignability() {
        typechecks("""
                define class Source implements HasOutputChannel<int> as
                    pub output: ReadChannel<int>;
                end

                define class Sink implements HasInputChannel<int> as
                    pub input: WriteChannel<int>;
                end

                pub fnc use_output(HasOutputChannel<int> source): void {
                    val ReadChannel<int> endpoint = source.output;
                    return;
                }

                pub fnc use_input(HasInputChannel<int> sink): void {
                    val WriteChannel<int> endpoint = sink.input;
                    return;
                }

                pub fnc pass_concrete(Source source, Sink sink): void {
                    use_output(source);
                    use_input(sink);
                    return;
                }

                pub fnc pass_channel(Channel<int> channel): void {
                    use_output(channel);
                    use_input(channel);
                    return;
                }
                """);
    }

    @Test
    void inheritedBuiltinEndpointProtocolsRemainPipeVisible() {
        IllegalArgumentException bridge = rejects("""
                define class BaseSource implements HasOutputChannel<int> as
                    pub output: ReadChannel<int>;
                end

                define class DerivedSource extends BaseSource as
                end

                pub fnc connect(
                    DerivedSource source,
                    WriteChannel<int> sink
                ): void {
                    val graph = source pipe sink;
                    return;
                }
                """);
        assertTrue(bridge.getMessage().contains(
                        "non-actor pipe transport requires bounded stream/channel bridge lowering"),
                bridge::toString);
    }

    @Test
    void genericInheritedEndpointProtocolsParticipateInOrdinaryAssignability() {
        typechecks("""
                define class BaseSource<T> implements HasOutputChannel<T> as
                    pub output: ReadChannel<T>;
                end

                define class IntSource extends BaseSource<int> as
                end

                pub fnc use_output(HasOutputChannel<int> source): void {
                    val ReadChannel<int> endpoint = source.output;
                    return;
                }

                pub fnc pass_derived(IntSource source): void {
                    use_output(source);
                    return;
                }
                """);

        IllegalArgumentException mismatch = rejects("""
                define class BaseSource<T> implements HasOutputChannel<T> as
                    pub output: ReadChannel<T>;
                end

                define class IntSource extends BaseSource<int> as
                end

                pub fnc use_output(HasOutputChannel<string> source): void {
                    return;
                }

                pub fnc bad(IntSource source): void {
                    use_output(source);
                    return;
                }
                """);
        assertTrue(mismatch.getMessage().contains("argument 1"), mismatch::toString);
    }

    @Test
    void protocolTypedValuesExposeOnlyTheirDeclaredDirectionalProperty() {
        IllegalArgumentException wrongProperty = rejects("""
                pub fnc bad(HasOutputChannel<int> source): void {
                    val endpoint = source.input;
                    return;
                }
                """);
        assertTrue(wrongProperty.getMessage().contains(
                        "unknown HasOutputChannel member 'input'"),
                wrongProperty::toString);

        IllegalArgumentException wrongProperty2 = rejects("""
                pub fnc bad(HasInputChannel<int> sink): void {
                    val endpoint = sink.output;
                    return;
                }
                """);
        assertTrue(wrongProperty2.getMessage().contains(
                        "unknown HasInputChannel member 'output'"),
                wrongProperty2::toString);
    }

    @Test
    void rawChannelCanParticipateAsBothDirectionalEndpointsButStillNeedsBridgeLowering() {
        IllegalArgumentException bridge = rejects("""
                pub fnc connect(Channel<int> middle, WriteChannel<int> sink): void {
                    val graph = middle pipe sink;
                    return;
                }
                """);
        assertTrue(bridge.getMessage().contains(
                        "non-actor pipe transport requires bounded stream/channel bridge lowering"),
                bridge::toString);
    }
}
