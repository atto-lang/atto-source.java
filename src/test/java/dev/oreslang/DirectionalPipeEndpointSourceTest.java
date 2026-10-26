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
