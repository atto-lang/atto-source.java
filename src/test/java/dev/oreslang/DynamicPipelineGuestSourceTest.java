package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Actual .ores source using AOT-static actor providers and host-only
 * dynamic planner lowering. No LLVM/native-image certification is inferred. */
final class DynamicPipelineGuestSourceTest {
    private static String execute(String guest) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(Source.newBuilder(
                    OresLanguage.ID, guest, "dynamic-pipeline-guest.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String source() throws Exception {
        return Files.readString(Path.of("examples/actor-dynamic-pipeline-swap.ores"));
    }

    @Test void sourceGuestStartDrainSwapResumeAndComplete() throws Exception {
        assertEquals("DYNAMIC_PIPELINE_V1_19|DYNAMIC_PIPELINE_V2_24",
                execute(source()));
    }

    @Test void rejectDynamicProviderWithCapturedOrComputedActorType() throws Exception {
        String guest = source();
        String malformed = guest.replace(
                "|| -> spawn Transform()",
                "|| -> { return spawn Transform(); }");
        assertNotEquals(guest, malformed);
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(malformed)));
        assertTrue(err.toString().contains("actor providers must be"),
                err.toString());
    }

    @Test void rejectPipelineBuiltinsShadowedByUserClasses() {
        for (String kind : new String[]{
                "PipelinePlanner", "PipelinePlan", "PipelineController", "PipelineRunner"}) {
            String source = "define class " + kind + " as end\n"
                    + "pub fnc main(): int { return 1; }";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)),
                    kind + " must be a reserved language intrinsic");
        }
    }

    @Test void rejectPipelineControllerWithForgedHostValue() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(
                Parser.parse("pub fnc main(): void { "
                        + "val c = new PipelineController(7); return; }")));
    }

    @Test void deliveryReceiptCannotMasqueradeAsTypedActorResult() throws Exception {
        String guest = source();
        String misleading = guest.replace(
                "    await first.send(Signal.Data(5));",
                "    let int forged = await first.send(Signal.Data(5));");
        assertNotEquals(guest, misleading);
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(misleading)));
        assertTrue(rejected.toString().contains("assign")
                        || rejected.toString().contains("type"),
                rejected.toString());
    }

    @Test void receiptIsRuntimeOnlyAndCannotBeSourceConstructed() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                            val receipt = new PipelineDeliveryReceipt();
                            return;
                        }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class PipelineDeliveryReceipt as end
                        pub fnc main(): int { return 1; }
                        """)));
    }

    @Test void deliveryReceiptNameCannotBeHijackedByEnumOrPattern() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define enum PipelineDeliveryReceipt as
                            Forged: int = 1
                        end
                        pub fnc main(): int { return 1; }
                        """)),
                "nominal enum spoofing must not redefine the runtime receipt");
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define enum OtherMessages as
                            Data: int = 1
                        end
                        define pattern PipelineDeliveryReceipt as
                            OtherMessages.Data
                        end
                        pub fnc main(): int { return 1; }
                        """)),
                "nominal match pattern must not redefine the runtime receipt");
    }

    @Test void pipelineRuntimeTypeNamesCannotBeDeclaredAsFunctions() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc PipelineDeliveryReceipt(): int { return 1; }
                        pub fnc main(): int { return 1; }
                        """)));
    }

    @Test void rejectTooFewProviderArguments() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(
                Parser.parse("pub fnc main(): void { "
                        + "val p = new PipelinePlanner(); "
                        + "p.add(\"missing\"); return; }")));
    }
}
