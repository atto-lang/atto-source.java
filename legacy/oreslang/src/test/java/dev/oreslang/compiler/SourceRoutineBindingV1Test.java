package dev.oreslang.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.SourceIdentityV1;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Real Java frontend AST binding, NOT an instrumented Truffle guest event. */
class SourceRoutineBindingV1Test {
    private static final String GOOD = "rid_d5f8e146ba97430dac6bf17430477e2a";

    @Test
    void bindsExactParsedAttoRoutineByAstObjectIdentity() {
        Ast.Program parsed = Parser.parse("pub fnc main(): int { return 42; }");
        var module = parsed.modules().stream()
                .filter(value -> value.declarations().stream()
                        .anyMatch(Ast.FunctionDecl.class::isInstance))
                .findFirst().orElseThrow();
        var function = module.declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .findFirst().orElseThrow();
        var manifest = List.of(new SourceRoutineBindingV1.Entry(
                module.name(), function.name(), function.parameters().size(), GOOD));
        var checked = SourceRoutineBindingV1.bind(parsed, manifest);
        assertEquals(1, checked.size());
        assertEquals(SourceIdentityV1.Kind.ROUTINE, checked.get(function).kind());
        assertSame(checked.get(function), checked.get(function));
        assertThrows(UnsupportedOperationException.class,
                () -> checked.clear());
    }

    @Test
    void rejectsWrongKindStaleAndDuplicatedManifestClaims() {
        var parsed = Parser.parse("pub fnc main(): int { return 42; }");
        var module = parsed.modules().stream()
                .filter(value -> value.declarations().stream()
                        .anyMatch(Ast.FunctionDecl.class::isInstance))
                .findFirst().orElseThrow();
        String name = module.name();
        assertThrows(IllegalArgumentException.class, () ->
                SourceRoutineBindingV1.bind(parsed, List.of(
                        new SourceRoutineBindingV1.Entry(name, "main", 0,
                                "sid_d5f8e146ba97430dac6bf17430477e2a"))));
        assertThrows(IllegalArgumentException.class, () ->
                SourceRoutineBindingV1.bind(parsed, List.of(
                        new SourceRoutineBindingV1.Entry(name, "stale", 0, GOOD))));
        assertThrows(IllegalArgumentException.class, () ->
                SourceRoutineBindingV1.bind(parsed, List.of(
                        new SourceRoutineBindingV1.Entry(name, "main", 0, GOOD),
                        new SourceRoutineBindingV1.Entry(name, "main", 0, GOOD))));
    }
}
