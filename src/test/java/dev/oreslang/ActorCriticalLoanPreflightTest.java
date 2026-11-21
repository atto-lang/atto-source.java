package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorCriticalLoanPreflight;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorCriticalLoanPreflightTest {
    private static String actor(String mode, String body, String fieldType) {
        return """
                define actor Counter as
                  state { count: %s = 0; }
                  pub update(): void with %s {
                    self.handle_state(%s |s| -> { %s });
                    return;
                  }
                end
                """.formatted(fieldType,
                        mode.contains("read") ? "state.read" : "state.write",
                        mode, body);
    }

    private static Ast.ClassDecl parsed(String source) {
        return (Ast.ClassDecl) Parser.parse(source).modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.ClassDecl)
                .findFirst().orElseThrow();
    }

    private static void accepted(String mode, String body) {
        assertDoesNotThrow(() -> ActorCriticalLoanPreflight.auditClass(
                parsed(actor(mode, body, "int"))));
    }

    private static void rejected(String mode, String body, String reason) {
        var failed = assertThrows(IllegalArgumentException.class,
                () -> ActorCriticalLoanPreflight.auditClass(
                        parsed(actor(mode, body, "int"))));
        assertTrue(failed.getMessage().contains(reason), failed::getMessage);
    }

    @Test void readAndWriteAliasesHaveIdenticalPreflightSemantics() {
        for (String mode : new String[] {"critical[write]", "critical-write", "critical"}) {
            accepted(mode, "s.count = s.count + 1;");
        }
        for (String mode : new String[] {"critical[read]", "critical-read"}) {
            accepted(mode, "return s.count;");
        }
    }

    @Test void readLoanCannotWriteEvenInNestedCondition() {
        rejected("critical[read]", "s.count = 3;", "cannot mutate");
        rejected("critical-read", "if true; then s.count = 3; fi",
                "cannot mutate");
    }

    @Test void cannotEscapeStateOrUseUnknownMembers() {
        rejected("critical[write]", "return s;", "reference cannot escape");
        rejected("critical[read]", "return s.unknown;", "undeclared");
        rejected("critical[write]", "return s.count.foo;", "nested or foreign");
    }

    @Test void rejectUnverifiedCallsAndSuspension() {
        rejected("critical[write]", "await operation;", "suspension");
        rejected("critical[write]", "doIO();", "forbids calls");
        rejected("critical[write]", "self.send(3);", "forbids calls");
    }

    @Test void modeMustFitDeclaredEffectBound() {
        String write = actor("critical[write]", "s.count = 1;", "int")
                .replace("with state.write", "with state.read");
        var writeFailure = assertThrows(IllegalArgumentException.class,
                () -> ActorCriticalLoanPreflight.auditClass(parsed(write)));
        assertTrue(writeFailure.getMessage().contains("requires with state.write"));

        String read = actor("critical[read]", "return s.count;", "int")
                .replace("with state.read", "with state.write");
        var readFailure = assertThrows(IllegalArgumentException.class,
                () -> ActorCriticalLoanPreflight.auditClass(parsed(read)));
        assertTrue(readFailure.getMessage().contains("requires with state.read"));
    }

    @Test void duplicateOrUnknownStateEffectsAreRejectedByParser() {
        String source = actor("critical[read]", "return s.count;", "int");
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse(source.replace("with state.read",
                        "with state.read, state.read")));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse(source.replace("with state.read", "with state.upgrade")));
    }

    @Test void callbackCannotForgeLoanParameterType() {
        var typed = assertThrows(IllegalArgumentException.class,
                () -> ActorCriticalLoanPreflight.auditClass(parsed(
                        actor("critical[write]", "s.count = 1;", "int")
                                .replace("|s| ->", "|s: int| ->"))));
        assertTrue(typed.getMessage().contains("untyped"), typed::getMessage);
    }

    @Test void callbackCannotShadowLoanParameter() {
        rejected("critical[write]", "let s = 1; s.count = 2;", "cannot be shadowed");
    }

    @Test void stillNoGuestExecutionUntilLoansAndArenasExist() {
        var program = Parser.parse(actor("critical[write]",
                "s.count = s.count + 1;", "int"));
        var denied = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(denied.getMessage().contains("compiler-enforced handle_state loans"),
                denied::getMessage);
    }
}
