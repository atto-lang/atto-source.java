package dev.oreslang.launcher;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SelectReturnWarningCliTest {
    @Test
    void cliDoesNotWarnForProceduralBlockingSelectReturn() {
        String source = """
                fnc check(): int {
                  do select {
                    default: {
                      return 7;
                    }
                  }
                  return 9;
                }
                """;
        List<String> warnings = OresMain.formatSelectReturnWarnings(
                Path.of("check.ores"), source);
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void nestedLambdaReturnDoesNotProduceDoSelectWarning() {
        String source = """
                fnc check(): void {
                  do select {
                    default: {
                      val Fnc<int,int> twice = |number| -> {
                        return number * 2;
                      };
                    }
                  }
                  return;
                }
                """;
        assertTrue(OresMain.formatSelectReturnWarnings(Path.of("check.ores"), source).isEmpty());
    }

    @Test
    void nonblockingDoSelectProducesWarningWithoutTreatingReturnAsActorExit() {
        String source = """
                actor fnc check(): void {
                  do nb select {
                    default: {
                      return 1;
                    }
                  }
                  return;
                }
                """;
        List<String> warnings = OresMain.formatSelectReturnWarnings(
                Path.of("check.ores"), source);
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains("W-SELECT-RETURN"));
    }

    @Test
    void legacySelectRemainsCompatibleAndDoesNotEmitDiscardWarning() {
        String source = """
                fnc check(): void {
                  select {
                    default: {
                      return;
                    }
                  }
                  return;
                }
                """;
        assertTrue(OresMain.formatSelectReturnWarnings(Path.of("check.ores"), source).isEmpty());
    }

    @Test
    void preliminarySyntaxErrorsDoNotSuppressAuthoritativeCheckDiagnostics() {
        assertTrue(OresMain.formatSelectReturnWarnings(
                Path.of("invalid.ores"), "fnc missing(): void { do select {").isEmpty());
    }
}
