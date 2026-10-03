package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class SingletonClassTest {

    @Test
    void parsesSingletonClassAsDistinctClassKind() {
        Ast.Program program = Parser.parse("""
                define singleton class ProcessCounter as
                  let int count = 0;

                  pub read() => int {
                    return self.count;
                  }
                end
                """);

        Ast.ClassDecl klass = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertTrue(klass.singleton());
    }

    @Test
    void singletonClassBootstrapsOnceAndPersistsAcrossTrustedContexts() throws Exception {
        String program = """
                define singleton class ProcessCounter as
                  let int count = 0;

                  pub next() => int {
                    self.count = self.count + 1;
                    return self.count;
                  }
                end

                define module app as
                  pub routine main() => void {
                    stdio.println(await ProcessCounter.next());
                    return;
                  }
                end
                """;

        String first = eval(program, "singleton-class-process-counter.ores");
        String second = eval(program, "singleton-class-process-counter.ores");

        assertTrue(first.contains("1"), first);
        assertTrue(second.contains("2"), second);
    }

    @Test
    void qualifiedSingletonClassCallsUseTheSameProcessInstance() throws Exception {
        String program = """
                define module cache as
                  define singleton class GlobalCounter as
                    let int count = 40;

                    pub next() => int {
                      self.count = self.count + 1;
                      return self.count;
                    }
                  end
                end

                define module app as
                  pub routine main() => void {
                    stdio.println(await cache.GlobalCounter.next());
                    return;
                  }
                end
                """;

        String first = eval(program, "qualified-singleton-class.ores");
        String second = eval(program, "qualified-singleton-class.ores");

        assertTrue(first.contains("41"), first);
        assertTrue(second.contains("42"), second);
    }

    @Test
    void singletonClassCannotBeConstructedOrAliased() {
        IllegalArgumentException construction = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton class ProcessCounter as
                          let int count = 0;
                          pub read() => int { return self.count; }
                        end

                        define module app as
                          pub routine main() => void {
                            val ProcessCounter other = new ProcessCounter();
                            return;
                          }
                        end
                        """)));
        assertTrue(construction.getMessage().contains("cannot be constructed with new"),
                construction.getMessage());

        IllegalArgumentException classAlias = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton class ProcessCounter as
                          let int count = 0;
                          pub read() => int { return self.count; }
                        end

                        define module app as
                          pub routine main() => void {
                            val handle = ProcessCounter;
                            return;
                          }
                        end
                        """)));
        assertTrue(classAlias.getMessage().contains("handles cannot be extracted"),
                classAlias.getMessage());

        IllegalArgumentException methodAlias = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton class ProcessCounter as
                          let int count = 0;
                          pub read() => int { return self.count; }
                        end

                        define module app as
                          pub routine main() => void {
                            val method = ProcessCounter.read;
                            return;
                          }
                        end
                        """)));
        assertTrue(methodAlias.getMessage().contains("cannot be extracted"),
                methodAlias.getMessage());
    }

    @Test
    void singletonClassStorageAndTransportDoNotLeakMutableAliases() {
        IllegalArgumentException publicField = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton class BadCounter as
                          pub let int count = 0;
                          pub read() => int { return self.count; }
                        end
                        """)));
        assertTrue(publicField.getMessage().contains("must be private"), publicField.getMessage());

        IllegalArgumentException returnsSelf = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton class BadCounter as
                          let int count = 0;

                          pub leak() => BadCounter {
                            return self;
                          }
                        end
                        """)));
        assertTrue(returnsSelf.getMessage().contains("not statically Sendable"),
                returnsSelf.getMessage());
    }

    @Test
    void ordinaryModulesRemainActorOrContextLocalRatherThanImplicitSingletons() throws Exception {
        String program = """
                define module ordinary_counter as
                  let int count = 0;

                  pub fnc next() => int {
                    count = count + 1;
                    return count;
                  }
                end

                define module app as
                  pub routine main() => void {
                    stdio.println(ordinary_counter.next());
                    return;
                  }
                end
                """;

        String first = eval(program, "ordinary-module-not-singleton.ores");
        String second = eval(program, "ordinary-module-not-singleton.ores");

        assertTrue(first.contains("1"), first);
        assertTrue(second.contains("1"), second);
    }

    @Test
    void untrustedPolicyCannotAcquireSingletonClassCapability() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define singleton class ProcessCounter as
                  let int count = 0;
                  pub read() => int { return self.count; }
                end
                """));

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
        assertTrue(denied.getMessage().contains("PROCESS_SINGLETON"), denied.getMessage());

        assertThrows(
                IllegalArgumentException.class,
                () -> IsolatePolicy.developer()
                        .withCapabilities(IsolatePolicy.Capability.PROCESS_SINGLETON)
                        .asAdversarial());
    }

    @Test
    void singletonClassMustUseStableDeterministicBootstrapState() {
        IllegalArgumentException missingInitializer = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton class MissingBootstrap as
                          let int count;
                          pub read() => int { return self.count; }
                        end
                        """)));
        assertTrue(missingInitializer.getMessage().contains("bootstrap initializer"),
                missingInitializer.getMessage());

        IllegalArgumentException ambientInitializer = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton class AmbientBootstrap as
                          let String id = process.context_id;
                          pub read() => String { return self.id; }
                        end
                        """)));
        assertTrue(ambientInitializer.getMessage().contains("deterministic context-free initializer")
                        || ambientInitializer.getMessage().contains("ambient caller capability"),
                ambientInitializer.getMessage());
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
