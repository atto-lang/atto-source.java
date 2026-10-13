package dev.oreslang;

import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Targeted closed-world annotation admission, independent of runtime/JIT. */
final class AnnotationExpanderAdmissionTest {
    @Test
    void fromJsonAccessorsAreGeneratedAtCompileTimeAndExpansionIsIdempotent() {
        Ast.Program parsed = Parser.parse("""
                define module demo as
                  define class Person as
                    @FromJson("user_id")
                    id: int;
                  end
                end
                """);
        Ast.Program expanded = AnnotationExpander.expand(parsed);
        Ast.ClassDecl person = (Ast.ClassDecl) expanded.modules().getFirst()
                .declarations().getFirst();
        assertEquals(List.of("getId", "setId"),
                person.methods().stream().map(Ast.MethodDecl::name).toList());
        assertTrue(AnnotationExpander.isGeneratedFromJsonGetter(person.methods().get(0)));
        assertTrue(AnnotationExpander.isGeneratedFromJsonSetter(person.methods().get(1)));
        assertEquals("user_id", AnnotationExpander.fromJsonBindings(person)
                .getFirst().jsonKey());

        Ast.ClassDecl second = (Ast.ClassDecl) AnnotationExpander.expand(expanded)
                .modules().getFirst().declarations().getFirst();
        assertEquals(2, second.methods().size(),
                "re-expansion must not add duplicate accessors");
    }

    @Test
    void invalidAnnotationTargetsFailBeforeRuntime() {
        String[] examples = {
                "@FromJson(\"id\") define module demo as end",
                "@Align(16) define module demo as end",
                """
                define module demo as
                  define class Person as
                    @FromJson("id")
                    fnc read(): int { return 1; }
                  end
                end
                """,
                """
                define module demo as
                  define class Person as
                    @Align(16)
                    fnc read(): int { return 1; }
                  end
                end
                """
        };
        for (String example : examples) {
            Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse(example), example);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> AnnotationExpander.expand(parsed), example);
            assertTrue(error.getMessage().contains("only valid")
                            || error.getMessage().contains("compile-time type metadata"),
                    () -> "missing target rejection: " + error.getMessage());
        }
    }

    @Test
    void duplicateJsonKeysAreRejectedDuringExpansion() {
        Ast.Program parsed = Parser.parse("""
                define module demo as
                  define class Person as
                    @FromJson("dup")
                    first: int;
                    @FromJson("dup")
                    second: int;
                  end
                end
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AnnotationExpander.expand(parsed));
        assertTrue(error.getMessage().contains("duplicate @FromJson key"),
                error.getMessage());
    }
}
