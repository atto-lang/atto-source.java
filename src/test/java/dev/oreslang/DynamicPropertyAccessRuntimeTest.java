package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Guest runtime coverage for computed fixed-product property/index reads and writes. */
final class DynamicPropertyAccessRuntimeTest {
    @Test
    void awaitedDynamicStructKeyUsesSuspendableComputedWritePath() {
        String source = """
                async fnc select_key(): string {
                    return "x";
                }

                pub async fnc main(): void {
                    const Vec2 seed = infer<Vec2> struct{x: 1, y: 2};
                    val Vec2 current = infer struct{x: 3, y: 4};
                    current[await select_key()] = 9;
                    stdio.println(current["x"]);
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "dynamic-property-awaited-write.ores")
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

        String text = output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals("9\n", text);
    }

    @Test
    void computedAssignmentPreservesRhsBeforeKeyOrder() {
        String source = """
                fnc rhs(): int {
                    stdio.println("rhs");
                    return 7;
                }

                fnc key(): string {
                    stdio.println("key");
                    return "x";
                }

                pub fnc main(): void {
                    val Point point = infer struct{x: 1, y: 2};
                    point[key()] = rhs();
                    stdio.println(point["x"]);
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "dynamic-property-assignment-order-sync.ores")
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

        String text = output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals("rhs\nkey\n7\n", text);
    }

    @Test
    void suspendableComputedAssignmentPreservesRhsBeforeKeyOrder() {
        String source = """
                async fnc rhs(): int {
                    stdio.println("rhs");
                    return 7;
                }

                async fnc key(): string {
                    stdio.println("key");
                    return "x";
                }

                pub async fnc main(): void {
                    val Point point = infer struct{x: 1, y: 2};
                    point[await key()] = await rhs();
                    stdio.println(point["x"]);
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "dynamic-property-assignment-order.ores")
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

        String text = output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals("rhs\nkey\n7\n", text);
    }

    @Test
    void dynamicAggregateAndClassIndexingExecutesWithCheckedSemantics() {
        String source = """
                type Row = {x: float, y: float};

                define class Point as
                    pub let int x = 1;
                    pub let int y = 2;
                end

                define class InferredPoint as
                    pub let x = 20;
                    pub let y = 30;
                end

                fnc record_get(Row row, string key): float {
                    return row[key];
                }

                fnc tuple_get(Tuple[float,float] pair, int index): float {
                    return pair[index];
                }

                fnc dynamic_key(): string {
                    return "x";
                }

                pub fnc main(): void {
                    const Row row = record{x: 3.0, y: 4.0};
                    stdio.println(record_get(row, "y"));

                    const pair = tuple(5.0, 6.0);
                    stdio.println(tuple_get(pair, 0));

                    const Vec2 seed = infer<Vec2> struct{x: 7.0, y: 8.0};
                    val Vec2 current = infer struct{x: 9.0, y: 10.0};
                    const field = dynamic_key();
                    current[field] = 11.0;
                    stdio.println(current[field]);

                    val Point point = new Point();
                    const classKey = dynamic_key();
                    point[classKey] = 12;
                    stdio.println(point[classKey]);

                    val InferredPoint inferred = new InferredPoint();
                    inferred[classKey] = 13;
                    stdio.println(inferred[classKey]);
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "dynamic-property-access-runtime.ores")
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

        String text = output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals("4.0\n5.0\n11.0\n12\n13\n", text);
    }

    @Test
    void destructuredParentFieldPreservesStaticClassView() {
        String source = """
                define class Parent as
                    pub let int id = 1;
                end

                define class Child extends Parent as
                    pub let string label = "child";
                end

                define class Box as
                    pub val Parent item = new Child();
                end

                pub fnc main(): void {
                    const Box box = new Box();
                    const {item} = box;
                    stdio.println(item["id"]);
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "dynamic-destructure-parent-view.ores")
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

        assertEquals(
                "1\n",
                output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void parentStaticViewAllowsInheritedDomainOnChildObjects() {
        String source = """
                define class Parent as
                    pub let int id = 1;
                end

                define class Child extends Parent as
                    pub let string label = "child";
                end

                fnc read(Parent value, string key): int {
                    return value[key];
                }

                pub fnc main(): void {
                    const Parent value = new Child();
                    stdio.println(read(value, "id"));
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "dynamic-parent-view-runtime.ores")
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

        assertEquals(
                "1\n",
                output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void exactPublicClassKeyDoesNotInheritPrivateSiblingRestrictions() {
        String source = """
                define class Secret as
                    private val int hidden = 1;
                    pub let int shown = 2;
                end

                pub fnc main(): void {
                    val Secret secret = new Secret();
                    secret["shown"] = 7;
                    stdio.println(secret["shown"]);
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "dynamic-property-exact-public-key.ores")
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

        String text = output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals("7\n", text);
    }

    @Test
    void inheritedComputedClassFieldsAndOwnerPrivateAccessExecuteAtRuntime() {
        String source = """
                define class Parent<T> as
                    pub let T inherited;
                end

                define class Child<U> extends Parent<U> as
                    pub let U own;
                end

                define class Secret as
                    private let int hidden = 1;
                    pub let int shown = 2;

                    pub fnc read(string key): int {
                        return self[key];
                    }

                    pub fnc write(string key, int value): int {
                        self[key] = value;
                        return self[key];
                    }
                end

                fnc inherited_key(): string {
                    return "inherited";
                }

                fnc private_key(): string {
                    return "hidden";
                }

                pub fnc main(): void {
                    val Child<int> child = new Child<int>(3, 4);
                    const inherited = inherited_key();
                    stdio.println(child[inherited]);
                    child[inherited] = 8;
                    stdio.println(child[inherited]);

                    val Secret secret = new Secret();
                    const hidden = private_key();
                    stdio.println(secret.read(hidden));
                    stdio.println(secret.write(hidden, 9));
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "dynamic-property-inherited-runtime.ores")
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

        String text = output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals("3\n8\n1\n9\n", text);
    }

    @Test
    void awaitedClassReceiverPreservesStaticComputedAccessView() {
        String source = """
                define class Parent as
                    pub let int id = 7;
                end

                async fnc load(): Parent {
                    return new Parent();
                }

                pub async fnc main(): void {
                    const int value = (await load())["id"];
                    stdio.println(value);
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "dynamic-awaited-class-view.ores")
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

        assertEquals(
                "7\n",
                output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void checkedClassViewSurvivesCompositeReceiverExpressions() {
        String source = """
                define class Parent as
                    pub let int id = 7;
                end

                define class Child extends Parent as
                    pub let string label = "child";
                end

                pub fnc main(): void {
                    const int fromMatch = (match<Parent> true over {
                        on true { return new Child(); }
                        on false { return new Parent(); }
                    })["id"];
                    stdio.println(fromMatch);

                    let Parent assigned = new Parent();
                    stdio.println((assigned = new Child())["id"]);

                    val Parent borrowed = new Child();
                    stdio.println((rt borrow borrowed)["id"]);

                    const values = tuple(new Parent());
                    stdio.println(values[0]["id"]);
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "dynamic-checked-class-view-composite.ores")
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

        assertEquals(
                "7\n7\n7\n7\n",
                output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void structuralClassDynamicAccessUsesCheckedPublicFieldDomain() {
        String source = """
                define class Product as
                    pub val float x = 3.0;
                    pub val float y = 4.0;
                    private val string hidden = "secret";
                end

                fnc read(structural Product value, string key): float {
                    return value[key];
                }

                pub fnc main(): void {
                    stdio.println(read(new Product(), "y"));
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "dynamic-structural-class-domain.ores")
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

        assertEquals(
                "4.0\n",
                output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void structuralInterfaceDynamicAccessUsesNarrowCheckedFieldDomain() {
        String source = """
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
                    stdio.println(read(new Point3d(), "y"));
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "dynamic-structural-interface-domain.ores")
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

        assertEquals(
                "2\n",
                output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void structuralInterfaceDynamicAccessNarrowsStructAndRecordValues() {
        String source = """
                define interface PointLike
                    x: int;
                    y: int;
                end

                fnc read(structural PointLike value, string key): int {
                    return value[key];
                }

                pub fnc main(): void {
                    val point = infer struct{x: 1, y: 2, z: 3};
                    const row = record{x: 4, y: 5, z: 6};
                    stdio.println(read(point, "y"));
                    stdio.println(read(row, "x"));
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "dynamic-structural-data-domain.ores")
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

        assertEquals(
                "2\n4\n",
                output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }
}
