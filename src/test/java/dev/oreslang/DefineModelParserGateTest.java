package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class DefineModelParserGateTest {
    @Test
    void parsesGeneralPurposeModelAsInertNominalAst() {
        Ast.Program program = Parser.parse("""
                define model PriceEstimate(tax_rate: float = 0.15) as
                    ValidatePrice()
                    ApplyTax(tax_rate)
                    RoundCurrency()
                end
                """);

        Ast.ModelDecl model = assertInstanceOf(Ast.ModelDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals("PriceEstimate", model.name());
        assertEquals(Ast.Visibility.PRIVATE, model.visibility());
        assertEquals(1, model.parameters().size());
        assertEquals("tax_rate", model.parameters().getFirst().name());
        assertEquals("float", model.parameters().getFirst().type().name());
        assertNotNull(model.parameters().getFirst().defaultValue());
        assertEquals(3, model.operations().size());
        assertEquals("ApplyTax", model.operations().get(1).name());
        Ast.NameExpr ref = assertInstanceOf(Ast.NameExpr.class,
                model.operations().get(1).arguments().getFirst().value());
        assertEquals("tax_rate", ref.name());
    }

    @Test
    void preservesNamedStaticOperationArgumentsWithoutExecutingThem() {
        Ast.Program program = Parser.parse("""
                define model Filter as
                    Input()
                    LowPass(3000, mode: 2)
                end
                """);
        Ast.ModelDecl model = assertInstanceOf(Ast.ModelDecl.class,
                program.modules().getFirst().declarations().getFirst());
        Ast.ModelOpCall call = model.operations().get(1);
        assertNull(call.arguments().getFirst().name());
        assertEquals("mode", call.arguments().get(1).name());
        assertInstanceOf(Ast.LiteralExpr.class, call.arguments().get(1).value());
    }

    @Test
    void modelRemainsAContextualIdentifierOutsideDefineModel() {
        Token first = new Lexer("model").scan().getFirst();
        assertEquals(Token.Type.IDENT, first.type());

        assertDoesNotThrow(() -> Parser.parse("""
                pub fnc main(): int {
                    const int model = 3;
                    return model;
                }
                """));
    }

    @Test
    void supportsModelDeclarationsInsideModules() {
        Ast.Program program = Parser.parse("""
                define module finance as
                    define pub model Price as
                        Source()
                        Round()
                    end
                end
                """);
        Ast.ModelDecl model = assertInstanceOf(Ast.ModelDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals(Ast.Visibility.PUBLIC, model.visibility());
        assertEquals("Price", model.name());
    }

    @Test
    void rejectsEmptyOrExpressionBodiesWithStableDiagnosticClasses() {
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define model Empty as
                        end
                        """));
        assertTrue(empty.getMessage().contains("MODEL_EMPTY_BODY"), empty::toString);

        IllegalArgumentException expression = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define model Bad as
                            Source()
                            1 + 2
                        end
                        """));
        assertTrue(expression.getMessage().contains("MODEL_NON_LAYER_EXPRESSION"),
                expression::toString);
    }

    @Test
    void rejectsDuplicateParametersAndNamedOperationArguments() {
        IllegalArgumentException parameter = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define model Duplicate(x: int, x: int) as
                            Source()
                        end
                        """));
        assertTrue(parameter.getMessage().contains("duplicate model parameter 'x'"),
                parameter::toString);

        IllegalArgumentException argument = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define model DuplicateNamed as
                            Op(x: 1, x: 2)
                        end
                        """));
        assertTrue(argument.getMessage().contains("duplicate named model argument 'x'"),
                argument::toString);
    }

    @Test
    void rejectsPositionalArgumentsAfterNamedArguments() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define model InvalidArgs as
                            Op(x: 1, 2)
                        end
                        """));
        assertTrue(failure.getMessage().contains(
                "positional model arguments must precede named arguments"), failure::toString);
    }

    @Test
    void acceptsOnlyVisibilityModifiersOnModelDeclarations() {
        assertDoesNotThrow(() -> Parser.parse("""
                define pub model Visible as
                    Source()
                end
                """));
        assertDoesNotThrow(() -> Parser.parse("""
                define model pub Visible2 as
                    Source()
                end
                """));

        IllegalArgumentException async = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define async model Invalid as
                            Source()
                        end
                        """));
        assertTrue(async.getMessage().contains("model declarations"), async::toString);
    }


    @Test
    void preservesQualifiedOperationIdentityForLaterTrustedResolution() {
        Ast.Program program = Parser.parse("""
                define model Qualified as
                    finance.PriceInput()
                    finance.ApplyTaxBps(1500)
                end
                """);
        Ast.ModelDecl model = assertInstanceOf(Ast.ModelDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals("finance.PriceInput", model.operations().getFirst().name());
        assertEquals("finance.ApplyTaxBps", model.operations().get(1).name());
    }

    @Test
    void classifiesDynamicTopologyAndDirectEffectsBeforeGenericBodyErrors() {
        IllegalArgumentException dynamic = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define model Dynamic as
                            if condition then
                                Dense(12, 12)
                            fi
                        end
                        """));
        assertTrue(dynamic.getMessage().contains("MODEL_DYNAMIC_TOPOLOGY"), dynamic::toString);

        IllegalArgumentException effect = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define model Spawning as
                            spawn Worker()
                        end
                        """));
        assertTrue(effect.getMessage().contains("MODEL_EFFECTFUL_BODY"), effect::toString);
    }

    @Test
    void semanticPipelineFailsClosedUntilTypedModelGraphLoweringExists() {
        Ast.Program program = Parser.parse("""
                define model PriceEstimate as
                    Source()
                    Round()
                end
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(program));
        assertTrue(failure.getMessage().contains("MODEL_LOWERING_NOT_IMPLEMENTED"),
                failure::toString);
    }
}
