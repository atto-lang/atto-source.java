package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ScopedRequestBorrowPreflight;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Syntactic preflight negative examples. Successful preflight is NOT
 * admission: TypeChecker must still fail closed on all using scopes.
 */
final class ScopedRequestBorrowPreflightTest {
    private static String source(String body) {
        return "fnc run(): void { \n"
                + "using request.borrow() as view {\n"
                + body + "\n}\nreturn;\n}\n";
    }
    private static Ast.UsingBorrowStmt scope(String body) {
        Ast.FunctionDecl fn = (Ast.FunctionDecl) Parser.parse(source(body))
                .modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(d -> d instanceof Ast.FunctionDecl)
                .findFirst().orElseThrow();
        return assertInstanceOf(Ast.UsingBorrowStmt.class, fn.body().getFirst());
    }
    private static void rejects(String body, String expected) {
        Ast.UsingBorrowStmt stmt = scope(body);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ScopedRequestBorrowPreflight.verify(stmt));
        assertTrue(failure.getMessage().contains(expected), failure::getMessage);
        // Reject at the actual language compilation boundary too.
        IllegalArgumentException compiler = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source(body))));
        assertTrue(compiler.getMessage().contains(expected), compiler::getMessage);
    }

    @Test
    void allowsOnlySyntacticallyPureReadCallsThenStillBlocksExecution() {
        assertDoesNotThrow(() -> ScopedRequestBorrowPreflight.verify(scope("""
                const method = view.method();
                const path = view.path();
                const again = path;
                """)));
        IllegalArgumentException compile = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source("const method = view.method();"))));
        assertTrue(compile.getMessage().contains("feature is not enabled"), compile::getMessage);
    }

    @Test
    void forbidsReturningViewOrDerivedAlias() {
        rejects("return view;", "cannot escape through return");
        rejects("const path = view.path(); return path;", "cannot escape through return");
        rejects("const a = view.method(); const b = a; return b;", "cannot escape through return");
    }

    @Test
    void forbidsCallsWithUnprovenSuspensionOrCaptureEffects() {
        rejects("stdio.println(view.path());", "unknown call may suspend");
        rejects("helper();", "unknown call may suspend");
        rejects("view.method(1);", "unknown call may suspend");
    }

    @Test
    void forbidsAsyncSuspensionAndRuntimeCooperation() {
        rejects("await pending;", "cannot suspend");
        rejects("rt cooperate;", "cannot suspend");
    }

    @Test
    void forbidsDeferredCleanupAndShadowing() {
        rejects("defer view.method();", "defer may retain");
        rejects("const view = view.method();", "cannot be shadowed");
    }

    @Test
    void refusesUnknownStorageShapeRatherThanAssumingCopy() {
        rejects("const escaped = [view.path()];", "unverified expression or capture");
    }
}
