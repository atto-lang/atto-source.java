package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.EnumPayloadDependencies;
import dev.oreslang.types.TaggedMessageContracts;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class EnumPayloadDependenciesTest {
    @Test void directSelfContainmentRejectedWithoutAutoBoxing() {
        var source = Parser.parse("""
            define enum Foo as
                Empty
                Message1: Foo
            end
            """);
        var error = assertThrows(IllegalArgumentException.class,
                () -> TaggedMessageContracts.verify(source));
        assertTrue(error.getMessage().contains("unboxed recursive enum payload"), error.toString());
        assertTrue(error.getMessage().contains("Foo -> Foo"), error.toString());
    }

    @Test void mutualAndTupleRecursionRejectedIndependentOfDeclarationOrder() {
        var mutual = Parser.parse("""
            define enum Bar as
                ToFoo: Foo
            end
            define enum Foo as
                ToBar: Bar
            end
            """);
        var error = assertThrows(IllegalArgumentException.class,
                () -> TaggedMessageContracts.verify(mutual));
        assertTrue(error.getMessage().contains("Bar -> Foo -> Bar"), error.toString());
        var tuple = Parser.parse("""
            define enum Foo as
                Empty
                Message1: Tuple[int, Foo]
            end
            """);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TaggedMessageContracts.verify(tuple))
                .getMessage().contains("Foo -> Foo"));
    }

    @Test void distinctNestedEnumTypeIsRecognizedButWireCodecsFailClosed() {
        var source = Parser.parse("""
            define enum Priority as
                Low
                High
            end
            define enum Foo as
                Message1: Priority
            end
            """);
        var references = EnumPayloadDependencies.check(source);
        assertEquals(1, references.size());
        assertEquals("Priority", references.getFirst().to());
        assertTrue(references.getFirst().byValue());
        var error = assertThrows(IllegalArgumentException.class,
                () -> TaggedMessageContracts.verify(source));
        assertTrue(error.getMessage().contains("portable nested enum wire codec"), error.toString());
    }

    @Test void explicitIndirectionBreaksSizeCycleButDoesNotCreateWireSupport() {
        var source = Parser.parse("""
            define enum Foo as
                Empty
                More: List<Foo>
            end
            """);
        var references = EnumPayloadDependencies.check(source);
        assertEquals(1, references.size());
        assertFalse(references.getFirst().byValue());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TaggedMessageContracts.verify(source))
                .getMessage().contains("portable nested enum wire codec"));
    }

    @Test void twoFooDeclarationsCannotBeMergedOrSilentlyShadowed() {
        var source = Parser.parse("""
            define enum Foo as
                Message1 = 1
            end
            define enum Foo as
                Message1: Foo
            end
            """);
        var error = assertThrows(IllegalArgumentException.class,
                () -> TaggedMessageContracts.verify(source));
        assertTrue(error.getMessage().contains("duplicate enum declaration"), error.toString());
    }

    @Test void regularEnumsAndMatchPatternSpreadsRemainUnaffected() {
        var source = Parser.parse("""
            define enum Status as
                Message1: Tuple[bool, string]
                Message2: string
                Message3: string
            end
            define match pattern Messages as
                ...Status
            end
            """);
        assertTrue(EnumPayloadDependencies.check(source).isEmpty());
        assertEquals(3, TaggedMessageContracts.verify(source)
                .requireDomain("Messages").cases().size());
    }
}
