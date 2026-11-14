package dev.oreslang.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    // Both admission stages must bind the *same* parsed AST object identity.
    private static java.util.Map<Ast.CallExpr,
            dev.oreslang.runtime.SourceIdentityV1.Site> bind(
            Fixture fixture, List<SourceLogCallsiteBindingV1.Entry> sites) {
        var checkedRoutines = SourceRoutineBindingV1.bind(fixture.ast(), List.of(
                new SourceRoutineBindingV1.Entry(
                        fixture.module(), "main", 0, RID)));
        return SourceLogCallsiteBindingV1.bind(
                fixture.ast(), sites, checkedRoutines);
    }

    @Test
    void realParsedLogCallsReceiveDistinctImmutableSiteDescriptors() {
        var fixture = parse(SOURCE);
        var sites = bind(fixture, entries(fixture.module()));
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
            () -> bind(f, entries(f.module()).subList(0, 1)));
        var duplicate = new ArrayList<>(entries(f.module()));
        duplicate.set(1, new SourceLogCallsiteBindingV1.Entry(
            f.module(), "main", 0, "hammer.finished", RID, START));
        assertThrows(IllegalArgumentException.class,
            () -> bind(f, duplicate));
        var wrong = new ArrayList<>(entries(f.module()));
        wrong.set(1, new SourceLogCallsiteBindingV1.Entry(
            f.module(), "main", 0, "hammer.finished",
            "rid_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", END));
        assertThrows(IllegalArgumentException.class,
            () -> bind(f, wrong));
    }

    @Test
    void refusesDifferentAstWithSameStructureAndUnverifiedRoutineTable() {
        var first = parse(SOURCE);
        var second = parse(SOURCE);
        var checked = SourceRoutineBindingV1.bind(first.ast(), List.of(
                new SourceRoutineBindingV1.Entry(first.module(), "main", 0, RID)));
        assertThrows(IllegalArgumentException.class,
                () -> SourceLogCallsiteBindingV1.bind(
                        second.ast(), entries(second.module()), checked));
        assertThrows(NullPointerException.class,
                () -> SourceLogCallsiteBindingV1.bind(
                        first.ast(), entries(first.module()), null));
        var foreign = new java.util.IdentityHashMap<Ast.FunctionDecl,
                dev.oreslang.runtime.SourceIdentityV1.Id>(checked);
        var another = second.ast().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .findFirst().orElseThrow();
        foreign.put(another, checked.values().iterator().next());
        assertThrows(IllegalArgumentException.class,
                () -> SourceLogCallsiteBindingV1.bind(
                        first.ast(), entries(first.module()), foreign));
    }

    @Test
    void refusesUncheckedNestedReturnCall() {
        var hidden = parse(
                "pub fnc main(): int { return log.info(\"hidden\"); }");
        assertThrows(IllegalArgumentException.class,
                () -> bind(hidden, List.of(
                        new SourceLogCallsiteBindingV1.Entry(
                                hidden.module(), "main", 0, "hidden", RID, START))));
    }

    @Test
    void refusesGuestOwnedLogParameterAndSpanAlias() {
        var guestParameter = parse(
                "pub fnc main(int log): void { log.info(\"hammer.started\"); }");
        var checked = SourceRoutineBindingV1.bind(
                guestParameter.ast(), List.of(new SourceRoutineBindingV1.Entry(
                        guestParameter.module(), "main", 1, RID)));
        assertThrows(IllegalArgumentException.class,
                () -> SourceLogCallsiteBindingV1.bind(
                        guestParameter.ast(),
                        List.of(new SourceLogCallsiteBindingV1.Entry(
                                guestParameter.module(), "main", 1, "hammer.started",
                                RID, START)),
                        checked));

        var scopeAlias = parse(
                "pub fnc main(): void {"
                + " using telemetry.span(\"hammer\") as log {"
                + " log.info(\"hammer.started\"); } }");
        assertThrows(IllegalArgumentException.class,
                () -> bind(scopeAlias, List.of(new SourceLogCallsiteBindingV1.Entry(
                        scopeAlias.module(), "main", 0, "hammer.started", RID, START))));
    }

    @Test
    void refusesDynamicPayloadAndAmbiguousRepeatedLiteral() {
        var dynamic = parse(
            "pub fnc main(): void { const msg = \"secret\"; log.info(msg); }");
        assertThrows(IllegalArgumentException.class,
            () -> bind(dynamic,
                    List.of(new SourceLogCallsiteBindingV1.Entry(
                            dynamic.module(), "main", 0, "secret", RID, START))));
        var repeated = parse(
            "pub fnc main(): void { log.info(\"dup\"); log.info(\"dup\"); }");
        assertThrows(IllegalArgumentException.class,
            () -> bind(repeated,
                    List.of(new SourceLogCallsiteBindingV1.Entry(
                            repeated.module(), "main", 0, "dup", RID, START))));
    }
}
