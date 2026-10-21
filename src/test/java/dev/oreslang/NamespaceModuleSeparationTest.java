package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class NamespaceModuleSeparationTest {

    @Test
    void defineNamespaceIsTypeOnlyAndSupportsQualifiedTypes() {
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                define namespace protocol as {
                  type RequestId = int;

                  interface Request {
                    id: RequestId;
                  }
                }

                fnc echo(protocol.Request request): protocol.Request {
                  return request;
                }

                fnc echoId(protocol.RequestId id): protocol.RequestId {
                  return id;
                }
                """);

        assertEquals(1, program.namespaces().size());
        Ast.NamespaceDecl namespace = program.namespaces().getFirst();
        assertEquals("protocol", namespace.name());
        assertEquals(Ast.NamespaceMode.DEFINE, namespace.mode());
        assertEquals(2, namespace.declarations().size());
    }

    @Test
    void declareNamespaceSupportsBraceAndEndFormsWithoutRuntimeDefinitions() {
        Ast.Program braceStyle = OresCompiler.parseAndTypeCheck("""
                declare namespace external_api as {
                  type RequestId = int;
                  interface Request {
                    id: RequestId;
                  }
                }
                """);

        Ast.Program endStyle = OresCompiler.parseAndTypeCheck("""
                declare namespace external_api as
                  type RequestId = int;
                  interface Request
                    id: RequestId;
                  end
                end
                """);

        assertEquals(Ast.NamespaceMode.DECLARE, braceStyle.namespaces().getFirst().mode());
        assertEquals(Ast.NamespaceMode.DECLARE, endStyle.namespaces().getFirst().mode());
    }

    @Test
    void namespaceRejectsExecutableMembers() {
        IllegalArgumentException functionError = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define namespace protocol as {
                          fnc run(): void {
                            return;
                          }
                        }
                        """));
        assertTrue(functionError.getMessage().contains("type-only"));

        IllegalArgumentException bindingError = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define namespace protocol as {
                          const int port = 8080;
                        }
                        """));
        assertTrue(bindingError.getMessage().contains("type-only"));

        IllegalArgumentException classError = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define namespace protocol as {
                          define class Request as
                          end
                        }
                        """));
        assertTrue(classError.getMessage().contains("interfaces or type aliases"));
    }

    @Test
    void namespacesAndModulesCannotBeNestedOrDotted() {
        IllegalArgumentException nestedNamespace = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define namespace protocol as {
                          define namespace wire as {
                            type Id = int;
                          }
                        }
                        """));
        assertTrue(nestedNamespace.getMessage().contains("cannot be nested"));

        IllegalArgumentException nestedModule = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define namespace protocol as {
                          define module wire
                          end
                        }
                        """));
        assertTrue(nestedModule.getMessage().contains("cannot be nested"));

        IllegalArgumentException dottedNamespace = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define namespace protocol.wire as {
                          type Id = int;
                        }
                        """));
        assertTrue(dottedNamespace.getMessage().contains("cannot be nested or dotted"));
    }

    @Test
    void namespaceIsNotARuntimeValue() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define namespace protocol as {
                          type RequestId = int;
                        }

                        fnc bad(): int {
                          val x = protocol;
                          return 1;
                        }
                        """)));

        assertTrue(error.getMessage().contains("unknown name 'protocol'"));
    }

    @Test
    void namespaceAndModuleCannotShareTheSameContainerName() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define namespace protocol as {
                          type RequestId = int;
                        }

                        define module protocol
                          pub fnc run(): void {
                            return;
                          }
                        end
                        """));

        assertTrue(error.getMessage().contains("cannot be both a namespace and a module"));
    }

    @Test
    void moduleRemainsRuntimeCapable() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define module math
                  const int answer = 42;

                  pub fnc add(int a, int b): int {
                    return a + b;
                  }
                end
                """));
    }
}
