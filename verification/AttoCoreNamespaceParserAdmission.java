import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Source-only compile-time verification of backend-owned flat Atto core
 * namespace declarations. No JVM/Truffle guest execution is asserted.
 */
public final class AttoCoreNamespaceParserAdmission {
    private static int passed;

    private static void require(boolean result, String name) {
        if (!result) throw new AssertionError("core parser admission failed: " + name);
        passed++;
    }

    private static void reject(String source, String label) {
        try {
            Parser.parse(source);
        } catch (IllegalArgumentException e) {
            require(true, label + ": rejected");
            return;
        }
        throw new AssertionError(label + ": invalid source was accepted");
    }

    public static void main(String[] args) throws Exception {
        for (String stem : List.of("slice", "iter", "cmp")) {
            String source = Files.readString(Path.of("core/namespace", stem + ".atto"));
            Ast.Program program = Parser.parse(source);
            require(stem.equals(program.namespace()), stem + ": parsed declared flat namespace");
            require(program.modules().size() >= 1, stem + ": rooted source has declarations");
            Ast.Program reparsed = Parser.parse(source);
            require(program.namespace().equals(reparsed.namespace()), stem + ": deterministic identity");
            reject(source.replace("use namespace " + stem + ";",
                "use namespace atto.core." + stem + ";"), stem + ": dotted declaration");
            reject(source.replace("use namespace " + stem + ";",
                "use namespace " + stem + ";\nuse namespace another;"), stem + ": duplicate declaration");
            reject(source.replace("use namespace " + stem + ";",
                "use namespace " + stem + ";\nimport module fs from \"../../../host\";"),
                stem + ": namespace/import out of declaration order");
        }
        require(Parser.parse("use namespace sample; pub fnc main(): int { return 42; }")
            .namespace().equals("sample"), "single-function namespace");
        System.out.println("ATTO_JAVA_CORE_NAMESPACE_PARSER_ASSERTIONS=" + passed);
    }
}
