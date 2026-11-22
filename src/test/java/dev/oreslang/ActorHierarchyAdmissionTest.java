package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Actor inheritance must not accidentally reuse the ordinary class
 * multiple-inheritance model. Interface/trait conformance is type-only:
 * it does not introduce a second actor state, mailbox or scheduler.
 */
final class ActorHierarchyAdmissionTest {
    private static void assertLexicalBoundary(org.junit.jupiter.api.function.Executable check) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, check);
        assertTrue(error.getMessage().contains("cannot use same-file"), error.getMessage());
    }

    /**
     * A callable/stateful contract must never become an actor RPC surface. On
     * main the same-file actor lexical boundary rejects it first; the
     * transitive marker-shape diagnostic is the fallback once imported
     * contracts reach actor conformance.
     */
    private static void assertActorContractRejected(IllegalArgumentException error) {
        String message = error.getMessage();
        assertTrue(message.contains("cannot use same-file")
                        || message.contains("marker traits/interfaces"),
                message);
    }

    @Test
    void emptyActorDeclarationAndSingleActorParentAreAdmitted() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor A1 as
                end
                """)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Base as
                end
                define actor A1 extends Base as
                end
                """)));
    }

    // Main's actor lexical boundary (commits e195703979, f0f75ad8a1) forbids an actor
    // from naming any same-file interface/trait, and imported interfaces are
    // not yet linkable as actor conformance targets. Until they are, even a
    // marker contract fails closed at the boundary before the transitive
    // marker-shape check below can admit it; the shape check stays as
    // defense in depth for when imported actor contracts are lowered.
    @Test
    void sameFileMarkerInterfaceAndTraitStayBehindActorLexicalBoundary() {
        assertLexicalBoundary(() -> TypeChecker.check(Parser.parse("""
                define interface Identified as
                end
                define trait Traced as
                end
                define actor Base as
                end
                define actor A1 extends Base implements Identified, Traced as
                end
                """)));
    }

    @Test
    void actorCannotImplementCallableInterfaceAsDirectRpcSurface() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface Callable as
                          fnc ping(): int;
                        end
                        define actor A1 implements Callable as
                          pub ping(): int { return 1; }
                        end
                        """)));
        assertActorContractRejected(error);
    }

    @Test
    void actorCannotImplementCallableTraitAsDirectRpcSurface() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define trait CallableTrait as
                          fnc ping(): int;
                        end
                        define actor A1 implements CallableTrait as
                          pub ping(): int { return 1; }
                        end
                        """)));
        assertActorContractRejected(error);
    }

    @Test
    void actorCannotImplementInterfaceWithStoredFields() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface HasField as
                          value: int;
                        end
                        define actor A1 implements HasField as
                        end
                        """)));
        assertActorContractRejected(error);
    }

    @Test
    void inheritedCallableInterfaceCannotBeLaunderedThroughEmptyChildMarker() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface Parent as
                          fnc ping(): int;
                        end
                        define interface Child extends Parent as
                        end
                        define actor A1 implements Child as
                          pub ping(): int { return 1; }
                        end
                        """)));
        assertActorContractRejected(error);
    }

    @Test
    void inheritedEmptyMarkerInterfacesStayBehindActorLexicalBoundary() {
        assertLexicalBoundary(() -> TypeChecker.check(Parser.parse("""
                define interface Parent as
                end
                define interface Child extends Parent as
                end
                define actor A1 implements Child as
                end
                """)));
    }

    @Test
    void multipleActorSuperclassesAreRejectedEvenWithDistinctNames() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define actor Left as
                        end
                        define actor Right as
                        end
                        define actor A1 extends Left, Right as
                        end
                        """));
        assertTrue(error.getMessage().contains("at most one actor superclass"),
                error.getMessage());
    }

    @Test
    void actorCannotUseAnOrdinaryClassAsSuperclass() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Ordinary as
                        end
                        define actor A1 extends Ordinary as
                        end
                        """)));
        assertTrue(error.getMessage().contains("isolation kind"), error.getMessage());
    }

    @Test
    void ordinaryClassCannotBecomeActorByInheritance() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Parent as
                        end
                        define class Child extends Parent as
                        end
                        """)));
        assertTrue(error.getMessage().contains("isolation kind"), error.getMessage());
    }

    @Test
    void actorCannotInheritAcrossIsolationProfiles() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define isoactor Parent as
                        end
                        define actor A1 extends Parent as
                        end
                        """)));
        assertTrue(error.getMessage().contains("isolation kind"), error.getMessage());
    }

    @Test
    void interfaceIsNotAConcreteActorSuperclass() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface Contract as
                        end
                        define actor A1 extends Contract as
                        end
                        """)));
    }

    @Test
    void ordinaryClassCannotClaimActorStreamOperatorMarker() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Pretender implements StreamOperator<int,int> as
                        end
                        """)));
        assertTrue(error.getMessage().contains("can only be implemented by actors"),
                error.getMessage());
    }

    @Test
    void classIsNotAnActorImplementedContract() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Concrete as
                        end
                        define actor A1 implements Concrete as
                        end
                        """)));
    }

    @Test
    void actorRefCannotCallOrdinaryPublicActorMethodsDirectly() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor A1 as
                          pub ping(): void { return; }
                          receive(ActorMail<int> mail): void { self.end(); return; }
                        end
                        pub async routine main(): void {
                          val worker = spawn A1();
                          await worker.get_ready();
                          worker.ping();
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("unknown ActorRef callable member 'ping'"),
                error.getMessage());
    }

    @Test
    void actorRefCannotInvokeRuntimeOwnedReceiveHook() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor A1 as
                          receive(ActorMail<int> mail): void { self.end(); return; }
                        end
                        pub async routine main(): void {
                          val worker = spawn A1();
                          worker.receive(41);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("unknown ActorRef callable member 'receive'"),
                error.getMessage());
    }

    @Test
    void runtimeReservedSendCannotBeImplementedAsUserMethod() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor A1 as
                          send(int value): void { return; }
                        end
                        """)));
        assertTrue(error.getMessage().contains("reserved"), error.getMessage());
    }
}
