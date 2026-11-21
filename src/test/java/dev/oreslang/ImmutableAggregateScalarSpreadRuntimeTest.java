package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executable guest-source acceptance for the first scalar-only, Copy-safe
 * immutable aggregate spread slice. This is not general record spread parity.
 */
final class ImmutableAggregateScalarSpreadRuntimeTest {
    private static String execute(String source) {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "aggregate-scalar-spread.atto")
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .out(output)
                    .build()) {
                context.eval(program);
            }
        });
        return output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void precheckedLeaseCannotBypassAggregateOwnershipOrTypeProof() {
        String source = """
                pub fnc main(): void {
                    const base = record{x: 1};
                    const copy = record{...base, y: 2};
                    stdio.println(copy.x);
                    return;
                }
                """;
        // The trusted prechecked-program hook must never be enough to make
        // unverified aggregate projections executable.
        Ast.Program unchecked = Parser.parse(source);
        Source guest = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "unapproved-spread.ores")
                        .mimeType(OresLanguage.MIME_TYPE).buildLiteral());
        try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build();
             OresLanguage.PrecheckedProgramScope ignored =
                     OresLanguage.openPrecheckedProgram(
                             "unapproved-spread.ores", source, unchecked)) {
            RuntimeException failure = assertThrows(
                    RuntimeException.class, () -> context.eval(guest));
            assertTrue(failure.toString().contains("type and ownership admission"),
                    "raw parser AST must fail specifically at checked spread provenance");
        }

        // Even when the exact spread expression was approved inside another
        // complete program, a new root wrapping the same child declarations
        // has no executable proof. This closes the cross-unit AST graft path.
        Ast.Program approved = OresCompiler.parseAndTypeCheck(source);
        Ast.Program grafted = new Ast.Program(
                approved.namespace(), approved.imports(), approved.modules());
        assertNotSame(approved, grafted);
        try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build();
             OresLanguage.PrecheckedProgramScope ignored =
                     OresLanguage.openPrecheckedProgram(
                             "unapproved-spread.ores", source, grafted)) {
            RuntimeException failure = assertThrows(
                    RuntimeException.class, () -> context.eval(guest));
            assertTrue(failure.toString().contains("type and ownership admission"),
                    "a grafted program must not borrow an approved child AST's authority");
        }

        Ast.Program typeOnly = TypeChecker.checkTypesWithDiagnostics(Parser.parse(source)).program();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build();
             OresLanguage.PrecheckedProgramScope ignored =
                     OresLanguage.openPrecheckedProgram(
                             "unapproved-spread.ores", source, typeOnly)) {
            RuntimeException failure = assertThrows(
                    RuntimeException.class, () -> context.eval(guest));
            assertTrue(failure.toString().contains("type and ownership admission"),
                    "type-only AST must not inherit a fabricated ownership proof");
        }
    }

    @Test
    void canonicalAttoAggregateScalarSpreadExecutesAsGuestSource() {
        // The canonical new source extension is .atto, while legacy .ores
        // fixtures remain supported during the language rename.
        assertEquals("42\n", execute("""
                pub fnc main(): void {
                    const a = infer struct{x: 20};
                    const b = infer struct{...a, y: 22};
                    stdio.println(b.x + b.y);
                    return;
                }
                """));
    }

    @Test
    void inferredStructAndRecordExtendWithoutMutatingOriginal() {
        assertEquals("5\n5\n42\nhello\n", execute("""
                pub fnc main(): void {
                    const original = infer struct{foo: 5};
                    const next = infer struct{...original, bar: "hello"};
                    const row = record{...next, foo: 42};
                    stdio.println(original.foo);
                    stdio.println(next.foo);
                    stdio.println(row.foo);
                    stdio.println(row.bar);
                    return;
                }
                """));
    }

    @Test
    void multipleSpreadsAndExplicitOverrideRespectRightmostRule() {
        assertEquals("20\n42\n1\n", execute("""
                pub fnc main(): void {
                    const first = record{x: 1, y: 2};
                    const second = record{y: 20, z: true};
                    const merged = record{...first, ...second, x: 42};
                    stdio.println(merged.y);
                    stdio.println(merged.x);
                    stdio.println(first.x);
                    return;
                }
                """));
    }

    @Test
    void aggregateElementsEvaluateExactlyOnceInLexicalOrder() {
        assertEquals("base\nfirst\ninline\nlast\n4\n1\n", execute("""
                fnc mark(string label, int value): int {
                    stdio.println(label);
                    return value;
                }

                pub fnc main(): void {
                    const base = record{x: mark("base", 1)};
                    const next = record{
                        x: mark("first", 2),
                        ...record{x: mark("inline", 3)},
                        x_after: mark("last", 4)
                    };
                    stdio.println(next.x_after);
                    stdio.println(base.x);
                    return;
                }
                """));
    }

    @Test
    void spreadCanOverrideNamedInitializerBeforeIt() {
        assertEquals("6\n5\n", execute("""
                pub fnc main(): void {
                    const base = record{x: 5, y: 6};
                    const next = record{x: 1, ...base};
                    stdio.println(next.y);
                    stdio.println(next.x);
                    return;
                }
                """));
    }

    @Test
    void suspendableInitializersPreserveSpreadOperandOrder() {
        assertEquals("1\n2\n1\n", execute("""
                async fnc nextValue(): int {
                    return 2;
                }
                pub async fnc main(): void {
                    const original = record{x: 1};
                    const extended = record{...original, y: await nextValue()};
                    stdio.println(extended.x);
                    stdio.println(extended.y);
                    stdio.println(original.x);
                    return;
                }
                """));
    }

    @Test
    void syncAndSuspendedNestedSpreadsProduceSameRightmostValues() {
        String synchronous = """
                fnc value(): int { return 8; }
                pub fnc main(): void {
                    const base = record{first: 7};
                    const merged = record{
                        first: 1,
                        ...record{first: 2, awaited: value()},
                        ...base,
                        last: 9
                    };
                    stdio.println(merged.first);
                    stdio.println(merged.awaited);
                    stdio.println(merged.last);
                    return;
                }
                """;
        String suspended = """
                async fnc value(): int { return 8; }
                pub async fnc main(): void {
                    const base = record{first: 7};
                    const merged = record{
                        first: 1,
                        ...record{first: 2, awaited: await value()},
                        ...base,
                        last: 9
                    };
                    stdio.println(merged.first);
                    stdio.println(merged.awaited);
                    stdio.println(merged.last);
                    return;
                }
                """;
        assertEquals("7\n8\n9\n", execute(synchronous));
        assertEquals(execute(synchronous), execute(suspended),
                "suspension must not reorder or duplicate spread field writes");
    }

    @Test
    void structuralNarrowingDoesNotExposeOrValidateHiddenRuntimeFields() {
        // The wider runtime value also owns a nested non-Copy aggregate, but
        // the declared parameter exposes only "visible". Spread may project
        // only that static field; it must never enumerate the hidden object.
        assertEquals("7\n", execute("""
                fnc narrow(record{visible: int} source): record{visible: int} {
                    return record{...source};
                }

                pub fnc main(): void {
                    const wide = record{
                        visible: 7,
                        hidden: record{secret: 99}
                    };
                    const result = narrow(wide);
                    stdio.println(result.visible);
                    return;
                }
                """));
    }

    @Test
    void structuralNarrowingStillAdmitsExplicitScalarOverride() {
        assertEquals("42\n", execute("""
                fnc override(record{visible: int} source): record{visible: int} {
                    return record{...source, visible: 42};
                }

                pub fnc main(): void {
                    const wide = record{visible: 7, hidden: true};
                    const result = override(wide);
                    stdio.println(result.visible);
                    return;
                }
                """));
    }

    @Test
    void inferredRecordSpreadReplacesFieldTypeAndRetainsUntouchedSource() {
        assertEquals("8080\n8080\ntrue\n", execute("""
                pub fnc main(): void {
                    const initial = infer record{port: 8080, active: false};
                    const extended = infer record{...initial, port: "8080", active: true};
                    stdio.println(initial.port);
                    stdio.println(extended.port);
                    stdio.println(extended.active);
                    return;
                }
                """));
    }

    @Test
    void structuralReadonlyViewCanReadInferredAggregateWithoutMovingOwner() {
        assertEquals("2\n3\n2\n", execute("""
                define interface HasValue
                    value: int;
                end

                fnc peek(structural HasValue item): int {
                    return item.value;
                }

                pub fnc main(): void {
                    const local = infer struct{value: 2, extra: 99};
                    const readonly_row = infer record{value: 3, extra: "ok"};
                    stdio.println(peek(local));
                    stdio.println(peek(readonly_row));
                    stdio.println(local.value);
                    return;
                }
                """));
    }

    @Test
    void emptySpreadsAndQuotedStaticKeysRemainClosed() {
        assertEquals("Ada\n5\n", execute("""
                pub fnc main(): void {
                    const empty = record{};
                    const first = record{...empty, "first name": "Ada"};
                    const second = infer struct{...first, score: 5};
                    stdio.println(second["first name"]);
                    stdio.println(second.score);
                    return;
                }
                """));
    }

    @Test
    void overridingDoesNotAuthorizeNonCopyIntermediateValues() {
        String source = """
                fnc invalid(): int {
                    const bad = record{inner: record{x: 1}};
                    const next = record{...bad, inner: 2};
                    return next.inner;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(source)));
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void namedFieldCannotSmuggleNonScalarOverSpreadingConstructor() {
        String source = """
                fnc invalid(): int {
                    const first = record{x: 1};
                    const second = record{...first, inner: record{y: 2}};
                    return second.x;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(source)));
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void nestedOrMoveOnlySourcesRejectBeforeExecution() {
        String nested = """
                fnc fail(): int {
                    const first = record{inner: record{x: 1}};
                    const second = record{...first};
                    return second.inner.x;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(nested)));
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(Parser.parse(nested)));
    }

    @Test
    void namedInferredStructuralAliasesRemainLexicalNotNominal() {
        assertEquals("5\nfoo\n42\n", execute("""
                pub fnc main(): void {
                    const base = infer struct{foo: 5};
                    const T2 v = infer struct{...base, bar: "foo"};
                    const T3 w = infer record{...v, foo: 42};
                    stdio.println(v.foo);
                    stdio.println(w.bar);
                    stdio.println(w.foo);
                    return;
                }
                """));
    }

    @Test
    void plainRecordMustNotCreateImplicitTypeAlias() {
        String source = """
                fnc invalid(): void {
                    const base = infer struct{x: 1};
                    const Unbound v = record{...base};
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void explicitShapedStructSpreadRemainsUnsupported() {
        String source = """
                fnc fail(): int {
                    const first = record{x: 1};
                    const second = struct{x: int}{...first};
                    return second.x;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void nonaggregateAndUnknownSourceReject() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc fail(): void {
                    const next = record{...42};
                    return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc fail(): void {
                    const next = infer struct{...missing};
                    return;
                }
                """));
    }

    @Test
    void explicitlyRepeatedFieldRejectsEvenAcrossSpread() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc fail(): void {
                    const row = record{foo: 1};
                    const bad = record{foo: 2, ...row, foo: 3};
                    return;
                }
                """));
    }

    @Test
    void inferCannotMasqueradeAsStructuralViewConstruction() {
        String source = """
                pub fnc main(): void {
                    const invalid = infer structural {value: 42};
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source),
                "infer constructs a struct/record value, not a structural view");
    }

    @Test
    void readonlyStructuralParameterCannotMutateInferredStruct() {
        String source = """
                define interface HasValue
                    value: int;
                end

                fnc mutate(structural HasValue item): void {
                    item.value = 47;
                    return;
                }

                pub fnc main(): void {
                    const value = infer struct{value: 42, extra: true};
                    mutate(value);
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source),
                "a structural loan must never permit field mutation");
    }
}
