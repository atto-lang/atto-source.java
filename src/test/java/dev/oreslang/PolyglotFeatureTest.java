package dev.oreslang;

import com.oracle.truffle.api.interop.InteropLibrary;
import dev.oreslang.runtime.OresTuple;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class PolyglotFeatureTest {
    @Test
    void tupleRuntimeValueExportsReadOnlyArrayInterop() throws Exception {
        OresTuple tuple = new OresTuple(List.of("ok", 42L));
        InteropLibrary interop = InteropLibrary.getUncached();

        assertTrue(interop.hasArrayElements(tuple));
        assertTrue(interop.isArrayElementReadable(tuple, 0));
        assertTrue(interop.isArrayElementReadable(tuple, 1));
        assertTrue(!interop.isArrayElementReadable(tuple, 2));
        assertTrue(interop.getArraySize(tuple) == 2);
        assertTrue(interop.readArrayElement(tuple, 0).equals("ok"));
        assertTrue(interop.readArrayElement(tuple, 1).equals(42L));
    }

    @Test
    void nonExhaustiveMatchErrorsDoNotEchoPayloads() throws Exception {
        String program = """
                define module app
                  pub fnc main() => void {
                    match ("super-secret-token", 42) {
                      ("ok", value) => {
                        stdio.println(value);
                      }
                    }
                    return;
                  }
                end
                """;

        Source source = Source.newBuilder(OresLanguage.ID, program, "non-exhaustive-match.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            RuntimeException error = assertThrows(RuntimeException.class, () -> context.eval(source));
            assertTrue(error.getMessage().contains("tuple(arity=2)"));
            assertFalse(error.getMessage().contains("super-secret-token"));
        }
    }

    @Test
    void executesTaggedTupleAndFiniteListMatches() throws Exception {
        String program = """
                define module app
                  pub fnc main() => void {
                    match ("error", "enoent") {
                      ("ok", value) => {
                        stdio.println(value);
                      }
                      ("error", "enoent") => {
                        stdio.println("missing");
                      }
                      ("error", reason) => {
                        stdio.println(reason);
                      }
                      _ => {
                        stdio.println("unexpected");
                      }
                    }

                    match [1, 2, 3] {
                      [1, second] => {
                        stdio.println("wrong arity");
                      }
                      [1, second, _] => {
                        stdio.println(second + 40);
                      }
                      _ => {
                        stdio.println("fallback");
                      }
                    }
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "pattern-match.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("missing"));
        assertTrue(text.contains("42"));
        assertTrue(!text.contains("wrong arity"));
    }

    @Test
    void matchLiteralsAndTupleIdentityAreRuntimeStable() throws Exception {
        String program = """
                define module app
                  pub fnc main() => void {
                    match -4i {
                      -4i => {
                        stdio.println("negative-imag");
                      }
                      _ => {
                        stdio.println("bad-imag");
                      }
                    }

                    stdio.println((1, 2) == [1, 2] ? "bad-shape" : "shape-distinct");
                    stdio.println([1, 2] == (1, 2) ? "bad-reverse-shape" : "reverse-shape-distinct");
                    stdio.println([(1, 2)] == [[1, 2]] ? "bad-nested-shape" : "nested-shape-distinct");
                    stdio.println((1, 2) == (1, 2) ? "tuple-equal" : "bad-tuple");
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "match-runtime-hardening.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("negative-imag"));
        assertTrue(text.contains("shape-distinct"));
        assertTrue(text.contains("reverse-shape-distinct"));
        assertTrue(text.contains("nested-shape-distinct"));
        assertTrue(text.contains("tuple-equal"));
        assertTrue(!text.contains("bad-imag"));
        assertTrue(!text.contains("bad-shape"));
        assertTrue(!text.contains("bad-reverse-shape"));
        assertTrue(!text.contains("bad-nested-shape"));
        assertTrue(!text.contains("bad-tuple"));
    }

    @Test
    void executesRepeatedUnderscoreDestructureDiscards() throws Exception {
        String program = """
                define module app
                  pub fnc main() => void {
                    [const foo, _, let bar] = (1, 200, 3);
                    [const z, _, let y] = (4, 500, 6);
                    [_, _, const tail] = (700, 800, 9);
                    stdio.println(foo + bar + z + y + tail);
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "destructure-discard.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("23"));
    }

    @Test
    void executesNamespacesCollectionsAssignmentAndInheritedMethods() throws Exception {
        String program = """
                define module math
                  pub fnc add(int a, int b) => int { return a + b; }
                end

                define module model
                  define class A
                    pub value() => int { return 7; }
                  end
                  define class B extends A
                  end
                end

                define module app
                  pub fnc main() => void {
                    let answer = math.add(1, 2);
                    answer = answer + 4;
                    val values = arr[answer, 9];
                    val person = obj{name: "ores"};
                    val inherited = new B();
                    stdio.println(values[0]);
                    stdio.println(person.name);
                    stdio.println(inherited.value());
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "features.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("7"));
        assertTrue(text.contains("ores"));
    }
}
