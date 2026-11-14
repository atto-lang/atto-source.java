package dev.oreslang.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks the real Java guest AST; this does not execute a guest log event. */
class SourceLogCallsiteBindingV1Test {
    private static final String RID = "rid_d5f8e146ba97430dac6bf17430477e2a";
    private static final String START = "sid_652e1632e7464fdb936fc40fe24f1c7b";
    private static final String END = "sid_77777777777777777777777777777777";
    private static final String SOURCE =
            "pub fnc main(): void { " +
            "using telemetry.span(\"hammer\") as span { " +
            "log.info(\"hammer.started\"); " +
            "log.info(\"hammer.finished\"); " +
            "} }";

    private record Fixture(Ast.Program ast, String module) {}

    private static Fixture parse(String source) {
        var ast = Parser.parse(source);
        var owner = ast.modules().stream()
                .filter(module -> module.declarations().stream()
                        .anyMatch(Ast.FunctionDecl.class::isInstance))
                .findFirst().orElseThrow();
        return new Fixture(ast, owner.name());
    }

    private static List<SourceLogCallsiteBindingV1.Entry> entries(String module) {
        return List.of(
            new SourceLogCallsiteBindingV1.Entry(
                module, "main", 0, "hammer.started", RID, START),
            new SourceLogCallsiteBindingV1.Entry(
                module, "main", 0, "hammer.finished", RID, END));
    }

    @Test
    void realParsedLogCallsReceiveDistinctImmutableSiteDescriptors() {
        var fixture = parse(SOURCE);
        var sites = SourceLogCallsiteBindingV1.bind(
                fixture.ast(), entries(fixture.module()));
        assertEquals(2, sites.size());
        var distinct = new java.util.HashSet<String>();
        for (var entry : sites.entrySet()) {
            assertEquals("info",
                ((Ast.MemberExpr)entry.getKey().callee()).member());
            distinct.add(entry.getValue().siteId().toString());
            assertEquals(dev.oreslang.runtime.SourceIdentityV1.Kind.ROUTINE,
                         entry.getValue().routineId().kind());
        }
        assertEquals(2, distinct.size());
        assertThrows(UnsupportedOperationException.class, sites::clear);
    }

    @Test
    void refusesMissingForgedAndDuplicatedStaticSiteRows() {
        var f = parse(SOURCE);
        assertThrows(IllegalArgumentException.class,
            () -> SourceLogCallsiteBindingV1.bind(
                f.ast(), entries(f.module()).subList(0, 1)));
        var duplicate = new ArrayList<>(entries(f.module()));
        duplicate.set(1, new SourceLogCallsiteBindingV1.Entry(
            f.module(), "main", 0, "hammer.finished", RID, START));
        assertThrows(IllegalArgumentException.class,
            () -> SourceLogCallsiteBindingV1.bind(f.ast(), duplicate));
        var wrong = new ArrayList<>(entries(f.module()));
        wrong.set(1, new SourceLogCallsiteBindingV1.Entry(
            f.module(), "main", 0, "hammer.finished",
            "rid_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", END));
        assertThrows(IllegalArgumentException.class,
            () -> SourceLogCallsiteBindingV1.bind(f.ast(), wrong));
    }

    @Test
    void refusesDynamicPayloadAndAmbiguousRepeatedLiteral() {
        var dynamic = parse(
            "pub fnc main(): void { const msg = \"secret\"; log.info(msg); }");
        assertThrows(IllegalArgumentException.class,
            () -> SourceLogCallsiteBindingV1.bind(dynamic.ast(),
                    List.of(new SourceLogCallsiteBindingV1.Entry(
                            dynamic.module(), "main", 0, "secret", RID, START))));
        var repeated = parse(
            "pub fnc main(): void { log.info(\"dup\"); log.info(\"dup\"); }");
        assertThrows(IllegalArgumentException.class,
            () -> SourceLogCallsiteBindingV1.bind(repeated.ast(),
                    List.of(new SourceLogCallsiteBindingV1.Entry(
                            repeated.module(), "main", 0, "dup", RID, START))));
    }
}
