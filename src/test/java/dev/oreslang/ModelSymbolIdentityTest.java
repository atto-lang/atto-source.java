package dev.oreslang;

import dev.oreslang.compiler.ModelGraphCompiler;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.OresSymbol;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ModelSymbolIdentityTest {
    private static final String SOURCE = """
            fnc Pass(Symbol tag, Symbol input): Symbol {
              return input;
            }
            define model Tagged(tag: Symbol) as
              Pass(tag)
            end
            """;

    @Test
    void symbolConfigurationUsesNominalKindAndNormalizedName() throws Exception {
        ModelGraphCompiler.CompiledModel model =
                ModelGraphCompiler.compile(OresCompiler.parseAndTypeCheck(SOURCE))
                        .find("Tagged");
        assertNotNull(model);

        String uuid = "12345678-1234-1234-1234-123456789abc";
        OresSymbol process = OresSymbol.process(uuid);
        OresSymbol stable = OresSymbol.stable(uuid);
        assertNotEquals(process, stable);

        String processDigest = model.configuredGraphDigest(
                model.bindConfiguration(List.of(process)));
        String stableDigest = model.configuredGraphDigest(
                model.bindConfiguration(List.of(stable)));
        assertNotEquals(processDigest, stableDigest,
                "equal names with different nominal kinds must never alias cache entries");

        String nameBytes = HexFormat.of().formatHex(uuid.getBytes(StandardCharsets.UTF_8));
        assertEquals(digest("ores-model-configured-v1|" + model.graphDigest()
                        + "|tag=symbol:process:utf8:" + nameBytes + "|"),
                processDigest);
        assertEquals(digest("ores-model-configured-v1|" + model.graphDigest()
                        + "|tag=symbol:stable:utf8:" + nameBytes + "|"),
                stableDigest);
    }

    @Test
    void nfcEquivalentNamesYieldExactlyOneConfiguredIdentity() {
        ModelGraphCompiler.CompiledModel model =
                ModelGraphCompiler.compile(OresCompiler.parseAndTypeCheck(SOURCE))
                        .find("Tagged");
        String composed = model.configuredGraphDigest(
                model.bindConfiguration(List.of(OresSymbol.process("\u00e9"))));
        String decomposed = model.configuredGraphDigest(
                model.bindConfiguration(List.of(OresSymbol.process("e\u0301"))));
        assertEquals(composed, decomposed,
                "Symbol identity is its NFC-normalized name, not source spelling");
    }

    @Test
    void differentNamesAndKindsCannotAliasConfiguredModels() {
        ModelGraphCompiler.CompiledModel model =
                ModelGraphCompiler.compile(OresCompiler.parseAndTypeCheck(SOURCE))
                        .find("Tagged");
        String ready = model.configuredGraphDigest(
                model.bindConfiguration(List.of(OresSymbol.process("ready"))));
        String error = model.configuredGraphDigest(
                model.bindConfiguration(List.of(OresSymbol.process("error"))));
        assertNotEquals(ready, error);
        assertEquals(ready, model.configuredGraphDigest(
                model.bindConfiguration(List.of(OresSymbol.process("ready")))));
    }

    private static String digest(String input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8)));
    }
}
