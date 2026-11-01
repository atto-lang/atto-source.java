package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Supervisor is a distinct nominal authority: neither class inheritance nor
 * actor/trait composition may silently grant CONTROL hooks or supervisor IDs.
 * Tests are source parser/compiler gates, NOT guest runtime execution claims.
 */
final class SourceSupervisorTypeBoundaryTest {
    private static final String HOOK =
            "on_panic(f: Failure): void { return; }";

    @Test
    void simpleSupervisorRemainsDistinctAndGeneralExecutionClosed() {
        for (String source : new String[] {
                "define supervisor S1 as " + HOOK + " end",
                "define pub supervisor S1 as " + HOOK + " end",
                "define supervisor S1 { " + HOOK + " }"
        }) {
            Ast.Program parsed = Parser.parse(source);
            Ast.SupervisorDecl supervisor = assertInstanceOf(
                    Ast.SupervisorDecl.class,
                    parsed.modules().getFirst().declarations().getFirst());
            assertEquals("S1", supervisor.name());
            assertEquals(1, supervisor.hooks().size());
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source));
            assertTrue(denied.getMessage().contains("compiler-lowered CONTROL turns"));
        }
    }

    @Test
    void superclassInterfaceAndTraitClausesFailExplicitly() {
        for (String clause : new String[] {
                "extends Actor", "extends Supervisor", "extends BaseClass",
                "implements Observer", "impl Observer", "conforms Policy",
                "trait RestartPolicy", "interface Observer",
                "with RestartPolicy", "is Observer"
        }) {
            String source = "define supervisor S1 " + clause
                    + " as " + HOOK + " end";
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertTrue(denied.getMessage().contains(
                    "supervisor inheritance and interface/trait composition"), source);
        }
    }

    @Test
    void sourceCannotUpgradeActorLocalHooksToSupervisorControl() {
        for (String hook : new String[] {
                "on_throw(f: Failure): void { return; }",
                "on_raise(f: Failure): void { return; }",
                "on_throw(): void { return; }",
                "on_raise(): void { return; }"
        }) {
            String source = "define supervisor S1 as " + HOOK + " " + hook + " end";
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertTrue(denied.getMessage().contains(
                    "unsupported supervisor lifecycle hook"), source);
        }
    }

    @Test
    void failureBinderIsImmutableNominalAndNotSelf() {
        for (String signature : new String[] {
                "on_panic(f: Failure mut)",
                "on_panic(structural Failure f)",
                "on_panic(self: Failure)",
                "on_panic(f: Failure<int>)"
        }) {
            String source = "define supervisor S1 as " + signature
                    + ": void { return; } end";
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void controlHooksCannotSuspendOrBecomeAsync() {
        // A supervisor CONTROL turn is synchronous and bounded; no
        // scheduling/suspension modifier may be silently accepted as a hook.
        for (String modifier : new String[] { "async", "suspends", "async suspends" }) {
            for (String hook : new String[] {
                    "on_panic(f: Failure)", "on_start()", "tear_down()"
            }) {
                String source = "define supervisor S1 as " + HOOK + " "
                        + modifier + " " + hook + ": void { return; } end";
                IllegalArgumentException denied = assertThrows(
                        IllegalArgumentException.class, () -> Parser.parse(source), source);
                assertTrue(denied.getMessage().contains("supervisor hooks cannot use"), source);
            }
        }
    }

    @Test
    void controlHookReturnTypeMustBeUnparameterizedVoid() {
        for (String source : new String[] {
                "define supervisor S1 as on_panic(f: Failure): void<int> { return; } end",
                "define supervisor S1 as " + HOOK
                        + " on_start(): void<int> { return; } end"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void unknownHookAnnotationsCannotBeImplicitAuthority() {
        String source = "define supervisor S1 as "
                + "@GrantControl " + HOOK + " end";
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
        String allowed = "define supervisor S1 as @Implements " + HOOK + " end";
        assertInstanceOf(Ast.SupervisorDecl.class,
                Parser.parse(allowed).modules().getFirst().declarations().getFirst());
    }
}
