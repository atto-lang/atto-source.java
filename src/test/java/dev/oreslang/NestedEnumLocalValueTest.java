package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TaggedMessageContracts;
import dev.oreslang.types.NominalEnumValueGuards;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.runtime.TaggedMessageCodec;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class NestedEnumLocalValueTest {
    private static final String DECLS = """
            define enum Bar as
                Message1 = 1
            end
            define enum Foo as
                Message1: Bar
            end
            """;

    @Test
    void nominalNestedLocalCasesRetainPayloadTypeAndAreNotWireValuesYet() {
        var program = Parser.parse(DECLS);
        var registry = TaggedMessageContracts.forTypes(program);
        var bar = registry.requireCase("Bar.Message1");
        var foo = registry.requireCase("Foo.Message1");
        assertNull(bar.sourcePayload());
        assertNull(bar.payload());
        assertEquals("Bar", foo.sourcePayload().name());
        assertNull(foo.payload(), "nested enum codec is not implemented");
        assertEquals(1, foo.tag());

        assertThrows(IllegalArgumentException.class,
                () -> TaggedMessageContracts.verify(program));
        assertThrows(IllegalArgumentException.class,
                () -> new TaggedMessageCodec(registry.requireDomain("Foo")));
        // Constructed values are tagged instances, not their wire integer tags.
        var inner = new TaggedMessageCodec.TaggedValue("Bar", "Message1", 1, null);
        var outer = new TaggedMessageCodec.TaggedValue("Foo", "Message1", 1, inner);
        assertNotEquals(inner, outer);
        assertNotEquals(1, inner);
        assertEquals(inner, outer.payload());
    }

    @Test
    void typeCheckerAcceptsConstructingBarInsideFooButRejectsWrongNominalEnum() {
        var valid = DECLS + """
                pub routine main(): void {
                    val inner = Bar.Message1();
                    val outer = Foo.Message1(inner);
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(valid.replace(
                        "val outer = Foo.Message1(inner);",
                        "val outer = Foo.Message1(7);"))));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(valid.replace(
                        "val outer = Foo.Message1(inner);",
                        "val outer = Foo.Message1(Foo.Message1(inner));"))));
    }

    @Test
    void sourceRuntimeConstructsNestedNominalCaseLocally() throws Exception {
        var program = DECLS + """
                pub routine main(): void {
                    val outer = Foo.Message1(Bar.Message1());
                    if outer == Foo.Message1(Bar.Message1()); do
                        stdio.stdout.write("nested-ok");
                    fi
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        var buffer = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "nested-enum-local.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(buffer).build()) {
            context.eval(source);
        }
        assertEquals("nested-ok", buffer.toString(StandardCharsets.UTF_8));
    }

    @Test
    void actorMailboxCannotPromoteNestedEnumWithoutCanonicalWireCodec() {
        var source = DECLS + """
                define actor Worker as
                    receive(ActorMail<Foo> mail): void {
                        self.end();
                        return;
                    }
                end
                """;
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("no portable actor wire codec"),
                error.getMessage());
    }

    @Test
    void nestedRuntimeConstructorRejectsForgedTagOwnerAndNullaryPayload() {
        var registry = TaggedMessageContracts.forTypes(Parser.parse(DECLS));
        var foo = registry.requireCase("Foo.Message1");
        var good = new TaggedMessageCodec.TaggedValue("Bar", "Message1", 1, null);
        assertDoesNotThrow(() -> NominalEnumValueGuards.requirePayload(registry, foo, good));

        var forgedTag = new TaggedMessageCodec.TaggedValue("Bar", "Message1", 99, null);
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                NominalEnumValueGuards.requirePayload(registry, foo, forgedTag))
                .getMessage().contains("forged"));
        var wrongOwner = new TaggedMessageCodec.TaggedValue("Foo", "Message1", 1, good);
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                NominalEnumValueGuards.requirePayload(registry, foo, wrongOwner))
                .getMessage().contains("nominal owner"));
        var forgedNullary = new TaggedMessageCodec.TaggedValue("Bar", "Message1", 1, "smuggled");
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                NominalEnumValueGuards.requirePayload(registry, foo, forgedNullary))
                .getMessage().contains("nullary"));
    }

    @Test
    void nestedEnumInsideTupleChecksAllNominalElementsAndRejectsCycle() {
        var parsed = Parser.parse("""
                define enum Bar as
                    Ready
                end
                define enum Foo as
                    Payload: Tuple[int, Bar]
                end
                """);
        var registry = TaggedMessageContracts.forTypes(parsed);
        var foo = registry.requireCase("Foo.Payload");
        var valid = List.of(42L, new TaggedMessageCodec.TaggedValue("Bar", "Ready", 1, null));
        assertDoesNotThrow(() -> NominalEnumValueGuards.requirePayload(registry, foo, valid));
        assertThrows(IllegalArgumentException.class, () ->
                NominalEnumValueGuards.requirePayload(registry, foo,
                        List.of(42L, new TaggedMessageCodec.TaggedValue("Foo", "Payload", 1, valid))));
        var cyclic = new java.util.ArrayList<Object>();
        cyclic.add(42L);
        cyclic.add(cyclic);
        assertThrows(IllegalArgumentException.class, () ->
                NominalEnumValueGuards.requirePayload(registry, foo, cyclic));
    }

    @Test
    void rejectsMalformedBoxArityAtDependencyBoundary() {
        for (String type : List.of("Box<>", "Box<Bar, Foo>")) {
            var source = Parser.parse(DECLS + """
                    define enum Holder as
                        More: %s
                    end
                    """.formatted(type));
            var problem = assertThrows(IllegalArgumentException.class,
                    () -> TaggedMessageContracts.forTypes(source));
            assertTrue(problem.getMessage().contains("Box<T> requires exactly one"),
                    problem.toString());
        }
    }

    @Test
    void boxIsExplicitIndirectionNotImplicitSerialization() {
        var source = Parser.parse("""
                define enum Foo as
                    Empty
                    Message1: Box<Foo>
                end
                """);
        assertEquals(2, TaggedMessageContracts.forTypes(source)
                .requireDomain("Foo").cases().size());
        assertThrows(IllegalArgumentException.class,
                () -> TaggedMessageContracts.verify(source));
    }
}
