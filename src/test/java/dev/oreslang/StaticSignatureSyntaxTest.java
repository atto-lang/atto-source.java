package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class StaticSignatureSyntaxTest {

    @Test
    void signatureAndContractHaveIdenticalAstAndContractKind() {
        String contract = """
                pub define contract Api as
                  fnc run(int x): int;
                end
                """;
        String signature = contract.replace("define contract", "define signature");
        assertEquals(Parser.parse(contract), Parser.parse(signature));

        Ast.InterfaceDecl member = (Ast.InterfaceDecl) Parser.parse(signature)
                .modules().get(0).declarations().get(0);
        assertTrue(member.moduleContract());
        assertFalse(member.staticContract());

        String classSide = """
                pub define static signature Factory as
                  fnc make(): int;
                end
                """;
        Ast.InterfaceDecl staticMember = (Ast.InterfaceDecl) Parser.parse(classSide)
                .modules().get(0).declarations().get(0);
        assertTrue(staticMember.moduleContract());
        assertTrue(staticMember.staticContract());
        assertEquals(Parser.parse(classSide),
                Parser.parse(classSide.replace("static signature", "static contract")));
    }

    @Test
    void signaturesConformThroughExistingModuleAndStaticClassRules() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define signature Service as
                  name: string;
                  fnc value(): int;
                end
                define module Demo conforms Service as
                  pub const name: string = "demo";
                  pub fnc value(): int { return 7; }
                end
                define static signature Factory as
                  fnc make(): int;
                end
                define class DemoClass implements static Factory as
                  pub static fnc make(): int { return 9; }
                end
                """)));
    }

    @Test
    void moduleAndStaticSignaturesCannotCrossTheirConformanceDomains() {
        IllegalArgumentException module = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                        define static signature Factory as
                          fnc make(): int;
                        end
                        define module Illegal conforms Factory as
                          pub fnc make(): int { return 1; }
                        end
                        """)));
        assertTrue(module.getMessage().contains("cannot be used by a module"));

        IllegalArgumentException klass = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                        define signature API as
                          fnc make(): int;
                        end
                        define class Illegal implements static API as
                          pub static fnc make(): int { return 1; }
                        end
                        """)));
        assertTrue(klass.getMessage().contains("module contract"));
    }

    @Test
    void classStaticSignatureRequiresStaticImplementation() {
        IllegalArgumentException wrong = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                        define static signature Factory as
                          fnc make(): int;
                        end
                        define class Illegal implements static Factory as
                          pub fnc make(): int { return 1; }
                        end
                        """)));
        assertTrue(wrong.getMessage().contains("static contract"));
    }

    @Test
    void importedSignatureIsLinkedAsContractWithoutRuntimeModule() {
        Map<String, String> units = new LinkedHashMap<>();
        units.put("api.ores", """
                pub define signature Api as
                  fnc ping(): int;
                end
                pub define static signature Factory as
                  fnc create(): int;
                end
                """);
        units.put("impl.ores", """
                import signature Api from "./api.ores";
                import signature Factory as F from "./api.ores";
                define module Impl conforms Api as
                  pub fnc ping(): int { return 42; }
                end
                pub define class Constructible implements static F as
                  pub static fnc create(): int { return 9; }
                end
                """);
        assertEquals(2, assertDoesNotThrow(() ->
                new IncrementalCompiler().compile(units)).units().size());
    }

    @Test
    void signatureIsNotAClassOrAValueAndCannotBeInstantiated() {
        IllegalArgumentException contract = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                        define signature Service as
                          fnc ping(): int;
                        end
                        routine main(): void {
                          const x = new Service();
                          return;
                        }
                        """)));
        assertTrue(contract.getMessage().contains("compile-time contract/signature"));

        IllegalArgumentException module = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                        define module Service as
                          pub fnc ping(): int { return 1; }
                        end
                        routine main(): void {
                          const x = new Service();
                          return;
                        }
                        """)));
        assertTrue(module.getMessage().contains("compile-time module"));

        IllegalArgumentException staticSignature = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                        define static signature Factory as
                          fnc make(): int;
                        end
                        routine main(): void {
                          const x = new Factory();
                          return;
                        }
                        """)));
        assertTrue(staticSignature.getMessage().contains("compile-time contract/signature"));
    }

    @Test
    void signatureAndContractNamesCannotSilentlyCollide() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define contract Api as
                  fnc ping(): int;
                end
                define signature Api as
                  fnc ping(): int;
                end
                """)));
    }

    @Test
    void compatibilitySpellingAndModuleLocalSignatureWork() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module Catalog as
                  pub signature define Contract as
                    fnc ping(): int;
                  end
                end
                """)));
    }
}
