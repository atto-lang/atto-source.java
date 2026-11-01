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

    @Test
    void actorMayConformToMarkerInterfaceAndTraitWithoutExtraSuperclass() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
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
        assertTrue(error.getMessage().contains("marker traits/interfaces"),
                error.getMessage());
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
        assertTrue(error.getMessage().contains("marker traits/interfaces"),
                error.getMessage());
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
        assertTrue(error.getMessage().contains("marker traits/interfaces"),
                error.getMessage());
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
        assertTrue(error.getMessage().contains("including inherited members"),
                error.getMessage());
    }

    @Test
    void inheritedEmptyMarkerInterfacesRemainValid() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
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
