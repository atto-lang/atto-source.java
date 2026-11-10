package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-level negative actor inheritance tests. Neither guest execution
 * nor durable actor admission is claimed.
 */
final class ActorSuperfamilyTypeCheckTest {
    @Test
    void actorCannotInheritMutableStateFromParent() {
        var error=assertThrows(IllegalArgumentException.class,
            ()->TypeChecker.check(Parser.parse("""
                define actor Parent as
                  let int mutable_counter = 1;
                end
                define actor Child from Parent as
                end
                """)));
        assertTrue(error.getMessage().contains("stateless actor types"),
            error.getMessage());
    }

    @Test
    void actorCannotInheritStateFromGrandparent() {
        var error=assertThrows(IllegalArgumentException.class,
            ()->TypeChecker.check(Parser.parse("""
                define actor Grandparent as
                  val int hidden = 5;
                end
                define actor Parent from Grandparent as
                end
                define actor Child from Parent as
                end
                """)));
        assertTrue(error.getMessage().contains("stateless actor types"),
            error.getMessage());
    }

    @Test
    void plainClassCannotBeAnActorParent() {
        var error=assertThrows(IllegalArgumentException.class,
            ()->TypeChecker.check(Parser.parse("""
                define class Ordinary as
                end
                define actor Child from Ordinary as
                end
                """)));
        assertTrue(error.getMessage().contains("stateless actor types")
            || error.getMessage().contains("isolation kind"),error.getMessage());
    }

    @Test
    void missingNominalActorParentFailsClosed() {
        var error=assertThrows(IllegalArgumentException.class,
            ()->TypeChecker.check(Parser.parse("""
                define actor Child from NonexistentActor as
                end
                """)));
        assertTrue(error.getMessage().contains("unknown")
            || error.getMessage().contains("parent"),error.getMessage());
    }

    @Test
    void actorAncestryCycleRejected() {
        assertThrows(IllegalArgumentException.class,
            ()->TypeChecker.check(Parser.parse("""
                define abstract actor Parent from Child as
                end
                define abstract actor Child from Parent as
                end
                """)));
    }
}
