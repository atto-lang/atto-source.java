package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The type checker must enforce sealing independently of source parsing.
 * Compiler extensions, imported ASTs and rewrite passes cannot be trusted
 * to have used the source parser's admission rules.
 */
final class SealedHierarchyForgedAstTest {
    private static final String BASE = """
            define sealed class Root permits Child as end
            define final class Child extends Root as end
            """;

    private static Ast.ClassDecl withMetadata(Ast.ClassDecl original,
            boolean sealed, boolean fin, List<String> permits) {
        return new Ast.ClassDecl(original.name(), original.visibility(),
                original.isAbstract(), original.actorKind(),
                original.genericParameters(), original.parents(),
                original.interfaces(), original.fields(), original.constructor(),
                original.methods(), sealed, fin, permits);
    }

    private static Ast.Program rewriteClass(
            Ast.Program program, String className,
            UnaryOperator<Ast.ClassDecl> rewrite) {
        List<Ast.ModuleDecl> modules = new ArrayList<>();
        boolean replaced = false;
        for (Ast.ModuleDecl module : program.modules()) {
            List<Ast.Decl> declarations = new ArrayList<>();
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ClassDecl cls
                        && cls.name().equals(className)) {
                    declarations.add(rewrite.apply(cls));
                    replaced = true;
                } else {
                    declarations.add(declaration);
                }
            }
            modules.add(new Ast.ModuleDecl(module.name(), module.annotations(),
                    module.contracts(), declarations));
        }
        assertTrue(replaced, "fixture class must exist");
        return new Ast.Program(program.namespace(), program.imports(), modules);
    }

    private static void rejects(Ast.Program program, String fragment) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.checkTypes(program));
        assertTrue(failure.getMessage().contains(fragment), failure.getMessage());
    }

    @Test
    void forgedPermitsOnUnsealedClassAreRejected() {
        Ast.Program program = rewriteClass(Parser.parse(BASE), "Root",
                cls -> withMetadata(cls, false, false, List.of("Child")));
        rejects(program, "requires a sealed class");
    }

    @Test
    void forgedDuplicatePermitsAreRejected() {
        Ast.Program program = rewriteClass(Parser.parse(BASE), "Root",
                cls -> withMetadata(cls, true, false, List.of("Child", "Child")));
        rejects(program, "duplicate permitted subtype");
    }

    @Test
    void forgedOpenChildCannotEvadeSealedParent() {
        Ast.Program program = rewriteClass(Parser.parse(BASE), "Child",
                cls -> withMetadata(cls, false, false, List.of()));
        rejects(program, "must be sealed or final");
    }

    @Test
    void forgedAbsentPermitCannotBeInventedByMetadata() {
        Ast.Program program = rewriteClass(Parser.parse(BASE), "Root",
                cls -> withMetadata(cls, true, false, List.of("Child", "Ghost")));
        rejects(program, "not a direct subtype");
    }
}
