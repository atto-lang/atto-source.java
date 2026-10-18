package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class StaticSignatureAdversarialTest {
    @Test
    void everyStaticSignatureSpellingNormalizesToExistingContractAst() {
        String[] moduleDecls = {
                "define signature Api as fnc ping(): int; end",
                "pub define signature Api as fnc ping(): int; end",
                "signature define Api as fnc ping(): int; end",
                "pub signature define Api as fnc ping(): int; end"
        };
        for (String source : moduleDecls) {
            String equivalent = source.replace("signature", "contract");
            assertEquals(Parser.parse(equivalent), Parser.parse(source), source);
        }

        String[] staticDecls = {
                "define static signature Factory as fnc make(): int; end",
                "pub define static signature Factory as fnc make(): int; end",
                "define signature static Factory as fnc make(): int; end"
        };
        for (String source : staticDecls) {
            String equivalent = source.replace("signature", "contract");
            assertEquals(Parser.parse(equivalent), Parser.parse(source), source);
        }
    }

    @Test
    void signatureFieldMutabilityMatchesContractExactly() {
        String template = """
                define %s ImmutableSlot as
                  let name: string;
                end
                define module Sample conforms ImmutableSlot as
                  pub const name: string = "immutable";
                end
                """;
        for (String keyword : new String[]{"contract", "signature"}) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(template.formatted(keyword))));
            assertTrue(failure.getMessage().contains("binding kind"), keyword + ": " + failure.getMessage());
        }
    }

    @Test
    void staticSignatureFieldMustBelongToClassNamespaceNotInstance() {
        String template = """
                define static %s HasVersion as
                  version: string;
                end
                define class Client implements static HasVersion as
                  pub const version: string = "instance only";
                end
                """;
        for (String keyword : new String[]{"contract", "signature"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(template.formatted(keyword))));
            assertTrue(error.getMessage().contains("static contract"), error.getMessage());
        }
    }

    @Test
    void aliasImportCannotChangeStaticSignatureIntoModuleContract() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("api.ores", """
                pub define static signature Factory as
                  fnc make(): int;
                end
                """);
        sources.put("bad.ores", """
                import signature Factory as Api from "./api.ores";
                define module Bad conforms Api as
                  pub fnc make(): int { return 3; }
                end
                """);
        var error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(error.getMessage().contains("static contract"), error.getMessage());
    }

    @Test
    void importContractAndSignatureAreExactlySameKind() {
        String raw = """
                import signature Api from "./api.ores";
                pub routine main(): void { return; }
                """;
        Ast.Program program = Parser.parse(raw);
        assertEquals(Ast.ImportKind.CONTRACT, program.imports().getFirst().kind());
        assertEquals(program, Parser.parse(raw.replace("import signature", "import contract")));
    }

    @Test
    void unsupportedContractModifiersFailInsteadOfCreatingRuntimeObjects() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define async signature Async as fnc run(): void; end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define abstract signature Abstract as fnc run(): void; end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define actor signature ActorApi as fnc run(): void; end"));
    }
}
