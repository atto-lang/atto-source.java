package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-free OresLLM-style ownership syntax compiler admission.
 * This is not an execution receipt for private OresLLM PR source trees.
 */
final class OresLlmExplicitOwnershipSyntaxTest {
    private static final String PRELUDE = """
            define class OresModelMeta as
              pub val int hidden = 12;
              pub val int heads = 30;
            end
            fnc read_oresllm(borrow OresModelMeta cfg): int {
              return cfg.hidden + cfg.heads;
            }
            """;

    private static final String VALID = PRELUDE + """
            pub fnc main(): int {
              val OresModelMeta cfg = new OresModelMeta();
              return read_oresllm(rt borrow cfg);
            }
            """;

    @Test
    void borrowedParameterAndExplicitRtBorrowTypeCheck() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(VALID));
    }

    @Test
    void CStyleReferenceParameterIsNotAnAliasForBorrow() {
        final String source = VALID.replace(
            "borrow OresModelMeta cfg", "&OresModelMeta cfg");
        assertNotEquals(source, VALID);
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void CStyleAddressOfArgumentIsNotAnAliasForRtBorrow() {
        final String source = VALID.replace("rt borrow cfg", "&cfg");
        assertNotEquals(source, VALID);
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void CStylePointerDeclarationMustBeRejected() {
        final String source = VALID.replace(
            "borrow OresModelMeta cfg", "OresModelMeta *cfg");
        assertNotEquals(source, VALID);
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void CStyleDereferenceExpressionMustBeRejected() {
        final String source = VALID.replace("rt borrow cfg", "*cfg");
        assertNotEquals(source, VALID);
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void OrdinaryMultiplicationRemainsAllowed() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub fnc main(): int {
                  return 6 * 7;
                }
                """));
    }

    @Test
    void BooleanAndRemainsAllowed() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                fnc joined(): bool {
                  return true && true;
                }
                pub fnc main(): int {
                  return 42;
                }
                """));
    }

    @Test
    void CStyleMutableReferenceParameterMustBeRejected() {
        final String source = VALID.replace(
            "borrow OresModelMeta cfg", "&mut OresModelMeta cfg");
        assertNotEquals(source, VALID);
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void CStylePointerMemberAccessMustBeRejected() {
        final String source = VALID.replace(
            "cfg.hidden + cfg.heads", "cfg->hidden + cfg->heads");
        assertNotEquals(source, VALID);
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void CStylePointerCastMustBeRejected() {
        final String source = VALID.replace(
            "rt borrow cfg", "(OresModelMeta*) cfg");
        assertNotEquals(source, VALID);
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(source));
    }
}
