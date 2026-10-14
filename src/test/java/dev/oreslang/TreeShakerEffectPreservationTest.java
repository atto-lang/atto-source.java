package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The tree shaker runs after checking; it must preserve callable effect contracts. */
final class TreeShakerEffectPreservationTest {
    private static Ast.FunctionDecl function(TreeShaker.Result result, String name) {
        return result.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    void libraryRewriteKeepsSuspensionAndTrapContracts() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                pub suspends fnc slow(): int { return 7; }
                pub trap fnc safe(): int { return 42; }
                define pub class Service as
                  pub static suspends fnc fetch(): int { return 8; }
                end
                """, BuildOptions.library(Map.of()));

        assertTrue(result.retained(Parser.ROOT_MODULE + ".slow"));
        assertTrue(result.retained(Parser.ROOT_MODULE + ".safe"));
        assertTrue(function(result, "slow").suspends());
        assertFalse(function(result, "slow").trapped());
        assertTrue(function(result, "safe").trapped());
        assertFalse(function(result, "safe").suspends());

        Ast.ClassDecl service = result.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .filter(klass -> klass.name().equals("Service"))
                .findFirst().orElseThrow();
        assertTrue(service.methods().stream()
                .filter(method -> method.name().equals("fetch"))
                .findFirst().orElseThrow().suspends());
    }

    @Test
    void constantInlinerCannotEraseTrapOrSuspensionBoundary() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                trap fnc trapped(int n): int { return n + 1; }
                suspends fnc slow(int n): int { return n + 2; }

                pub trap fnc get_trapped(): Option<int> {
                  return trapped(7);
                }
                pub suspends fnc get_slow(): int {
                  return slow(8);
                }
                """, BuildOptions.library(Map.of()));

        Ast.FunctionDecl trapped = function(result, "get_trapped");
        Ast.FunctionDecl slow = function(result, "get_slow");
        assertTrue(trapped.trapped());
        assertTrue(slow.suspends());
        assertTrue(trapped.body().stream()
                .anyMatch(stmt -> stmt.toString().contains("trapped")),
                "the optimizer must not substitute the trap-wrapped call with a bare literal");
        assertTrue(slow.body().stream()
                .anyMatch(stmt -> stmt.toString().contains("slow")),
                "the optimizer must not substitute a suspends call with a bare literal");
    }
}
