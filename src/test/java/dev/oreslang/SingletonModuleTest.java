package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ProcessSingletonRegistry;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class SingletonModuleTest {
    @Test
    void parsesSingletonModuleAsDistinctModuleKind() {
        Ast.Program program = Parser.parse("""
                define singleton module process_cache
                  let int count = 0;
                  pub fnc read() => int { return count; }
                end
                """);

        assertEquals(1, program.modules().size());
        assertTrue(program.modules().getFirst().singleton());
    }

    @Test
    void singletonStateIsMutableOnlyBehindActorSafeFunctions() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define singleton module process_counter
                  let int count = 0;

                  pub fnc next() => int {
                    count = count + 1;
                    return count;
                  }
                end

                define module app
                  pub fnc main() => void {
                    val int first = await process_counter.next();
                    stdio.println(first);
                    return;
                  }
                end
                """)));

        IllegalArgumentException exposedField = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module bad_singleton
                          pub let int count = 0;
                        end
                        """)));
        assertTrue(exposedField.getMessage().contains("cannot be public"));
    }

    @Test
    void singletonModulePersistsAcrossIndependentGraalContextsInOneProcess() throws Exception {
        String program = """
                define singleton module process_counter_cross_context_test
                  let int count = 0;

                  pub fnc next() => int {
                    count = count + 1;
                    return count;
                  }
                end

                define module app
                  pub fnc main() => void {
                    val int n = await process_counter_cross_context_test.next();
                    stdio.println(n);
                    return;
                  }
                end
                """;

        String first = eval(program, "singleton-shared-unit.ores");
        String second = eval(program, "singleton-shared-unit.ores");

        assertTrue(first.contains("1"), first);
        assertTrue(second.contains("2"), second);
    }

    @Test
    void unrelatedCodeUnitsWithSameModuleNameDoNotAlias() throws Exception {
        String program = """
                define singleton module same_name_cache
                  let int count = 0;

                  pub fnc next() => int {
                    count = count + 1;
                    return count;
                  }
                end

                define module app
                  pub fnc main() => void {
                    val int n = await same_name_cache.next();
                    stdio.println(n);
                    return;
                  }
                end
                """;

        String tenantA = eval(program, "tenant-a/cache.ores");
        String tenantB = eval(program, "tenant-b/cache.ores");

        assertTrue(tenantA.contains("1"), tenantA);
        assertTrue(tenantB.contains("1"), tenantB);
    }

    @Test
    void registryCreatesOneActorAndSerializesConcurrentCalls() {
        String key = "test:" + UUID.randomUUID();
        AtomicInteger initializations = new AtomicInteger();

        ProcessSingletonRegistry.Handle<AtomicInteger> first =
                ProcessSingletonRegistry.getOrCreate(key, () -> {
                    initializations.incrementAndGet();
                    return new AtomicInteger();
                });
        ProcessSingletonRegistry.Handle<AtomicInteger> second =
                ProcessSingletonRegistry.getOrCreate(key, () -> {
                    fail("second factory must not initialize the same process singleton");
                    return new AtomicInteger();
                });

        assertEquals(first.instanceId(), second.instanceId());

        List<CompletableFuture<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            calls.add(first.call(List.of(), (state, ignored) -> state.incrementAndGet())
                    .toCompletableFuture());
        }
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();

        assertEquals(1, initializations.get());
        assertEquals(64L, ((Number) calls.getLast().join()).longValue());
    }

    private String eval(String program, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, name)
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
