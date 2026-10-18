package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VoidCompletionValueBoundaryTest {
    private static final String DECLARATIONS = """
            fnc complete(): void { return; }
            async fnc complete_async(): void { return; }
            trap fnc complete_safely(): void { return; }
            """;

    private static void rejectsVoidBinding(String statement) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        DECLARATIONS + "fnc bad(): void { " + statement + " return; }"));
        assertTrue(error.getMessage().contains("void"), error::getMessage);
    }

    @Test
    void voidFunctionCompletionCannotBecomeAnExplicitOrInferredValue() {
        rejectsVoidBinding("val void result = complete();");
        rejectsVoidBinding("val result = complete();");
    }

    @Test
    void awaitingFutureVoidDoesNotManufactureAValue() {
        rejectsVoidBinding("val void result = await complete_async();");
        rejectsVoidBinding("val result = await complete_async();");
    }

    @Test
    void voidCannotBePassedAsAnOrdinaryNamedParameter() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("fnc invalid(void result): void { return; }"));
        assertTrue(error.getMessage().contains("void"), error::getMessage);
    }

    @Test
    void classFieldsAndModuleBindingsCannotStoreVoid() {
        IllegalArgumentException field = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class Invalid as
                          val void finished;
                        end
                        """));
        assertTrue(field.getMessage().contains("void"), field::getMessage);

        IllegalArgumentException module = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define module Invalid as
                          val void finished = 1;
                        end
                        """));
        assertTrue(module.getMessage().contains("void"), module::getMessage);
    }

    @Test
    void optionVoidAndFutureVoidNeverImplicitlyConvertToEachOther() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(DECLARATIONS + """
                        fnc invalid(): void {
                          val Option<void> status = complete_async();
                          return;
                        }
                        """));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(DECLARATIONS + """
                        fnc invalid(): void {
                          val Future<void> pending = complete_safely();
                          return;
                        }
                        """));
    }

    @Test
    void voidIsStillLegalAsFutureOptionAndResultCompletionParameter() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(DECLARATIONS + """
                fnc accepted(): void {
                  val Future<void> pending = complete_async();
                  await pending;
                  val Option<void> recovered = complete_safely();
                  val Result<void, String> success = Ok();
                  val bool observed = recovered.is_some();
                  return;
                }
                """));
    }

    @Test
    void optionVoidIsNotAnOrdinaryVoidBinding() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(DECLARATIONS + """
                fnc accepted(): void {
                  val Option<void> completed = complete_safely();
                  val bool present = completed.is_some();
                  return;
                }
                """));
    }

    @Test
    void unimplementedStaticSelectExpressionsMustNotSilentlyBecomeDetachedCallbacks() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        fnc invalid(Channel<int> inbox): void {
                          val Future<void> tracked = nb select first {
                            when readch inbox: int value -> { return; }
                          };
                          return;
                        }
                        """));
        assertTrue(error.getMessage() != null && !error.getMessage().isBlank());
    }
}
