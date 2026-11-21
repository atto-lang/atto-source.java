package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime negative coverage for computed fixed-product access. */
final class DynamicPropertyAccessRuntimeFailureTest {
    private static void runtimeRejected(
            String source,
            String name,
            String expectedMessageFragment) {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, name)
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build());
        PolyglotException failure = assertThrows(PolyglotException.class, () -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .build()) {
                context.eval(program);
            }
        });
        assertTrue(
                failure.getMessage() != null
                        && failure.getMessage().contains(expectedMessageFragment),
                () -> "unexpected runtime failure: " + failure.getMessage());
    }

    @Test
    void dynamicUnknownRecordKeyFailsAtRuntime() {
        runtimeRejected("""
                type Row = {x: int, y: int};

                fnc selector(): string { return "missing"; }

                pub fnc main(): void {
                    const Row row = record{x: 1, y: 2};
                    val string key = selector();
                    const value = row[key] as int;
                    stdio.println(value);
                    return;
                }
                """, "dynamic-record-missing-key.ores", "unknown record field key missing");
    }

    @Test
    void dynamicTupleOutOfRangeFailsAtRuntime() {
        runtimeRejected("""
                fnc selector(): int { return 4; }

                pub fnc main(): void {
                    const pair = tuple(1, 2);
                    val int index = selector();
                    val int value = pair[index];
                    stdio.println(value);
                    return;
                }
                """, "dynamic-tuple-oob.ores", "array/list/tuple index 4 outside length 2");
    }

    @Test
    void dynamicUnknownClassFieldFailsAtRuntime() {
        runtimeRejected("""
                define class Point as
                    pub val int x = 1;
                    pub val int y = 2;
                end

                fnc selector(): string { return "missing"; }

                pub fnc main(): void {
                    const Point point = new Point();
                    val string key = selector();
                    val int value = point[key];
                    stdio.println(value);
                    return;
                }
                """, "dynamic-class-missing-key.ores", "unknown class field key Point.missing");
    }

    @Test
    void dynamicUnknownStructWriteFailsAtRuntime() {
        runtimeRejected("""
                fnc selector(): string { return "missing"; }

                pub fnc main(): void {
                    val Point point = infer struct{x: 1, y: 2};
                    val string key = selector();
                    point[key] = 9;
                    return;
                }
                """, "dynamic-struct-write-missing-key.ores", "unknown struct field key missing");
    }

    @Test
    void dynamicUnknownClassWriteFailsAtRuntime() {
        runtimeRejected("""
                define class Point as
                    pub let int x = 1;
                    pub let int y = 2;
                end

                fnc selector(): string { return "missing"; }

                pub fnc main(): void {
                    val Point point = new Point();
                    val string key = selector();
                    point[key] = 9;
                    return;
                }
                """, "dynamic-class-write-missing-key.ores", "unknown class field key Point.missing");
    }

    @Test
    void dynamicUnknownStructReadFailsAtRuntime() {
        runtimeRejected("""
                fnc selector(): string { return "missing"; }

                pub fnc main(): void {
                    val Point point = infer struct{x: 1, y: 2};
                    val string key = selector();
                    val int value = point[key];
                    stdio.println(value);
                    return;
                }
                """, "dynamic-struct-read-missing-key.ores", "unknown struct field key missing");
    }

    @Test
    void dynamicNegativeTupleIndexFailsAtRuntime() {
        runtimeRejected("""
                fnc selector(): int { return -1; }

                pub fnc main(): void {
                    const pair = tuple(1, 2);
                    val int index = selector();
                    val int value = pair[index];
                    stdio.println(value);
                    return;
                }
                """, "dynamic-tuple-negative-index.ores", "array/list/tuple index -1 outside length 2");
    }

    @Test
    void dynamicParentViewCannotExposeChildOnlyFieldAtRuntime() {
        runtimeRejected("""
                define class Parent as
                    pub val int id = 1;

                    pub fnc read(string key): int {
                        return self[key];
                    }
                end

                define class Child extends Parent as
                    pub val string label = "child";
                end

                fnc selector(): string { return "label"; }

                fnc read_through_parent(Parent value, string key): int {
                    return value[key];
                }

                pub fnc main(): void {
                    const Parent value = new Child();
                    val string key = selector();
                    stdio.println(read_through_parent(value, key));
                    return;
                }
                """,
                "dynamic-parent-view-child-only-field.ores",
                "unknown class field key Parent.label");
    }

    @Test
    void inheritedParentMethodCannotWriteChildOnlyFieldAtRuntime() {
        runtimeRejected("""
                define class Parent as
                    pub let int id = 1;

                    pub fnc write(string key, int value): int {
                        self[key] = value;
                        return self.id;
                    }
                end

                define class Child extends Parent as
                    pub let string label = "child";
                end

                fnc selector(): string { return "label"; }

                pub fnc main(): void {
                    val Child child = new Child();
                    val string key = selector();
                    stdio.println(child.write(key, 9));
                    return;
                }
                """,
                "dynamic-parent-method-child-only-write.ores",
                "unknown class field key Parent.label");
    }

    @Test
    void dynamicTupleIndexOverflowFailsAtRuntime() {
        runtimeRejected("""
                fnc selector(): int { return 2147483648; }

                pub fnc main(): void {
                    const pair = tuple(1, 2);
                    val int index = selector();
                    val int value = pair[index];
                    stdio.println(value);
                    return;
                }
                """, "dynamic-tuple-index-overflow.ores", "array/list index outside supported signed 32-bit range: 2147483648");
    }


    @Test
    void structuralClassDynamicAccessCannotEscapeCheckedFieldDomain() {
        runtimeRejected("""
                define class Product as
                    pub val float x = 3.0;
                    pub val float y = 4.0;
                    private val string hidden = "secret";
                end

                fnc read(structural Product value, string key): float {
                    return value[key];
                }

                pub fnc main(): void {
                    stdio.println(read(new Product(), "hidden"));
                    return;
                }
                """,
                "dynamic-structural-class-hidden-field.ores",
                "structural computed field key outside checked domain: hidden");
    }

    @Test
    void structuralInterfaceDynamicAccessCannotExposeWiderPublicField() {
        runtimeRejected("""
                define interface PointLike
                    x: int;
                    y: int;
                end

                define class Point3d as
                    pub val int x = 1;
                    pub val int y = 2;
                    pub val int z = 3;
                end

                fnc read(structural PointLike value, string key): int {
                    return value[key];
                }

                pub fnc main(): void {
                    stdio.println(read(new Point3d(), "z"));
                    return;
                }
                """,
                "dynamic-structural-interface-extra-field.ores",
                "structural computed field key outside checked domain: z");
    }

    @Test
    void structuralInterfaceDynamicAccessCannotExposeExtraStructOrRecordFields() {
        runtimeRejected("""
                define interface PointLike
                    x: int;
                    y: int;
                end

                fnc read(structural PointLike value, string key): int {
                    return value[key];
                }

                pub fnc main(): void {
                    val point = infer struct{x: 1, y: 2, z: 3};
                    stdio.println(read(point, "z"));
                    return;
                }
                """,
                "dynamic-structural-struct-extra-field.ores",
                "structural computed field key outside checked domain: z");

        runtimeRejected("""
                define interface PointLike
                    x: int;
                    y: int;
                end

                fnc read(structural PointLike value, string key): int {
                    return value[key];
                }

                pub fnc main(): void {
                    const row = record{x: 4, y: 5, z: 6};
                    stdio.println(read(row, "z"));
                    return;
                }
                """,
                "dynamic-structural-record-extra-field.ores",
                "structural computed field key outside checked domain: z");
    }
}
