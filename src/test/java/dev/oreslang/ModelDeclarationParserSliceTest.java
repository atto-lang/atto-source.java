package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Source parser evidence only. No model is executable until backend lowering
 * and trusted typed op signatures are implemented in BOTH compiler backends.
 */
final class ModelDeclarationParserSliceTest {
    private static Ast.ModelDecl model(String source) {
        Ast.Program program = Parser.parse(source);
        assertEquals(1, program.modules().size());
        Ast.Decl declaration = program.modules().getFirst().declarations().getFirst();
        return assertInstanceOf(Ast.ModelDecl.class, declaration);
    }
    private static void invalid(String source, String diagnostic) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source));
        assertTrue(e.getMessage().contains(diagnostic), e::getMessage);
    }

    @Test
    void canonicalMlModelIsParsedAsTypedSourceGraphButNotExecuted() {
        Ast.ModelDecl model = model("""
                define model Classifier(features: int = 784, hidden: int = 512, classes: int = 10) as
                  Dense(features, hidden)
                  ReLU()
                  Dense(hidden, classes)
                end
                """);
        assertEquals("Classifier", model.name());
        assertEquals(3, model.formals().size());
        assertEquals(3, model.operations().size());
        assertEquals("Dense", model.operations().getFirst().name());
        assertEquals(Ast.ModelValueKind.FORMAL,
                model.operations().getFirst().arguments().getFirst().value().kind());
        assertEquals(Ast.ModelValueKind.INTEGER, model.formals().getFirst().defaultValue().kind());
        assertEquals("784", model.formals().getFirst().defaultValue().source());
    }

    @Test
    void nonMlModelInsideModuleIsPureSequentialSourceSyntax() {
        Ast.Program program = Parser.parse("""
                define module app as
                  define pub model Tax(rate: float = 0.15) as
                    Validate()
                    Apply(rate)
                    Round()
                  end
                  pub fnc main(): int { return 1; }
                end
                """);
        Ast.ModelDecl decl = assertInstanceOf(Ast.ModelDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals(Ast.Visibility.PUBLIC, decl.visibility());
        assertEquals("Tax", decl.name());
        assertEquals(Ast.ModelValueKind.FLOAT, decl.formals().getFirst().defaultValue().kind());
        assertEquals(3, decl.operations().size());
        assertEquals("rate", decl.operations().get(1).arguments().getFirst().value().source());
    }

    @Test
    void modelIsContextualAndNotGloballyReserved() {
        assertDoesNotThrow(() -> Parser.parse("""
                pub fnc model(): int { return 42; }
                """));
    }

    @Test
    void rejectsDynamicAndEffectfulSyntaxInsteadOfRunningIt() {
        invalid("define model M as Network().fetch() end", "MODEL_INVALID_OP");
        invalid("define model M as Dense(foo()) end", "MODEL_UNKNOWN_FORMAL");
        invalid("define model M as Dense(1 + 2) end", "expected ')'");
        invalid("define model M as Dense(await result) end", "MODEL_DYNAMIC_ARGUMENT");
        invalid("define model M as Dense(x) end", "MODEL_UNKNOWN_FORMAL");
        invalid("define model M as Dense(unknown: x) end", "MODEL_UNKNOWN_FORMAL");
        invalid("define model M as Dense(foo: 1, foo: 2) end", "MODEL_DUPLICATE_ARGUMENT");
        invalid("define model M as Dense(x: 1, 2) end", "MODEL_POSITIONAL_AFTER_NAMED");
    }

    @Test
    void rejectsMalformedDeclarationsAndBudgets() {
        invalid("define suspends model Wrong as Op() end", "MODEL_EFFECTFUL_BODY");
        invalid("define trap model Wrong as Op() end", "MODEL_EFFECTFUL_BODY");
        invalid("define async model Wrong as Op() end", "models accept only");
        invalid("define model Empty as end", "MODEL_EMPTY_BODY");
        invalid("define model M(x: int, x: int) as Dense(x) end", "MODEL_DUPLICATE_FORMAL");
        invalid("define model M(x: int = foo) as Dense(x) end", "MODEL_DYNAMIC_ARGUMENT");
        invalid("define model M as Dense(", "MODEL_DYNAMIC_ARGUMENT");
        invalid("define model M as Dense() ", "expected 'end'");
        StringBuilder many = new StringBuilder("define model Long as ");
        for (int i = 0; i < 65; i++) many.append("ReLU() ");
        many.append("end");
        invalid(many.toString(), "MODEL_NODE_BUDGET");
    }

    @Test
    void checkerMustRejectUnimplementedBackendRatherThanSilentlySkipModel() {
        Ast.Program source = Parser.parse("""
                define model Audio as
                  LowPass(3000)
                  Normalize()
                end
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(source));
        assertTrue(error.getMessage().contains("MODEL_LOWERING_UNSUPPORTED"), error::getMessage);
    }
}
