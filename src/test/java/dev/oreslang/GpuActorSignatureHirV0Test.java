package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.GpuActorSignatureHirV0;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class GpuActorSignatureHirV0Test {
    private static Ast.GpuActorDecl parseActor(String source) {
        return (Ast.GpuActorDecl) Parser.parse(source)
                .modules().getFirst().declarations().getFirst();
    }

    @Test void typedSignatureIsImmutableButNeverExecutable() {
        var actor = parseActor("""
            define gpu actor VectorWorker as
                state { let f32 scale: 1.0; let bool ready: true; }
                multiply(GpuBuffer<f32> buffer, f32 factor) -> void ::
                    return;
                end
            end
            """);
        var hir = GpuActorSignatureHirV0.describe(actor);
        assertEquals("VectorWorker", hir.name());
        assertEquals("TYPED_SIGNATURE_ONLY", hir.admissionStage());
        assertEquals(List.of(GpuActorSignatureHirV0.DeviceKind.F32,
                GpuActorSignatureHirV0.DeviceKind.BOOL),
                hir.state().stream().map(GpuActorSignatureHirV0.Field::kind).toList());
        assertEquals(GpuActorSignatureHirV0.DeviceKind.F32_DEVICE_BUFFER,
                hir.methods().getFirst().arguments().getFirst().kind());
        assertEquals(GpuActorSignatureHirV0.DeviceKind.VOID,
                hir.methods().getFirst().result());
        assertFalse(hir.executable());
        assertFalse(hir.physicalGpuVerified());
        assertFalse(hir.bodyEffectsVerified());
        assertFalse(hir.ownedDeviceBuffersVerified());
        assertThrows(UnsupportedOperationException.class,
                () -> hir.state().clear());
    }

    @Test void deviceUnsafeTypesAreNotRepresentedAsValidDeviceValues() {
        for (var type : List.of("String", "ActorMail", "Channel", "Future", "Pointer")) {
            var a = new Ast.GpuActorDecl("X", Ast.Visibility.PRIVATE,
                    List.of(new Ast.ProtectedStateField("value", Ast.TypeRef.simple(type),
                            new Ast.LiteralExpr(1L))), List.of());
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> GpuActorSignatureHirV0.describe(a)).getMessage()
                    .contains("GPU_ACTOR_DEVICE_TYPE_UNSUPPORTED"));
        }
    }

    @Test void unverifiedSourceTokensAndForgedProofPromotionAreDenied() {
        var fn = new Ast.ProtectedMethod("step", List.of(),
                Ast.TypeRef.simple("void"), List.of("return", ";"));
        var actor = new Ast.GpuActorDecl("X", Ast.Visibility.PRIVATE,
                List.of(), List.of(fn));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> GpuActorSignatureHirV0.describe(actor)).getMessage()
                .contains("GPU_ACTOR_UNVERIFIED_BODY_PROVENANCE"));
        assertThrows(IllegalArgumentException.class, () ->
                new GpuActorSignatureHirV0.Descriptor("X", Ast.Visibility.PRIVATE,
                    List.of(), List.of(), "TYPED_SIGNATURE_ONLY",
                    true, false, true, true));
    }

    @Test void validTypedShapesStillCannotPassFullCompilerTypecheck() {
        var source = """
            define gpu actor VectorWorker as
                state { let f32 scale: 1.0; }
                multiply(f32 factor) -> f32 ::
                    return factor;
                end
            end
            """;
        var program = Parser.parse(source);
        assertEquals("VectorWorker", GpuActorSignatureHirV0.describe(
            (Ast.GpuActorDecl) program.modules().getFirst().declarations().getFirst()).name());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypes(program));
        assertTrue(failure.getMessage().contains("GPU_ACTOR_MIR_UNSUPPORTED"));
    }
}
