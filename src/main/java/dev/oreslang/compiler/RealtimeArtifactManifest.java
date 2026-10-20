package dev.oreslang.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deterministic evidence handoff between the Oreslang frontend/classfile audit
 * and an external AOT toolchain/runtime qualification step.
 */
final class RealtimeArtifactManifest {
    static final String FILE_NAME = "oreslang-rt-artifact-v1.properties";
    static final String SCHEMA = "oreslang.realtime-artifact.v1";

    private RealtimeArtifactManifest() { }

    static Path write(
            Path directory,
            String bytecodeSha256,
            RealtimePrimitiveCompiler.SourceReport source,
            RealtimeClassfileAudit.Report classfile) throws IOException {
        Path manifest = directory.resolve(FILE_NAME);
        String text = ""
                + "schema=" + SCHEMA + "\n"
                + "class.name=" + classfile.className() + "\n"
                + "bytecode.sha256=" + bytecodeSha256 + "\n"
                + "entry=" + source.entry() + "\n"
                + "source.max_steps=" + source.maxSourceSteps() + "\n"
                + "source.max_call_depth=" + source.maxCallDepth() + "\n"
                + "source.reachable_functions=" + source.reachableFunctions() + "\n"
                + "source.frontend_type_ownership_checked=true\n"
                + "source.closed_world=true\n"
                + "source.bounded_control_flow=true\n"
                + "classfile.verified_methods=" + classfile.verifiedMethods() + "\n"
                + "classfile.bytecode_bytes=" + classfile.bytecodeBytes() + "\n"
                + "classfile.no_allocation_opcodes=true\n"
                + "classfile.no_dynamic_dispatch=true\n"
                + "classfile.no_monitor_operations=true\n"
                + "classfile.acyclic_call_graph=true\n"
                + "source.assurance=" + source.assurance() + "\n"
                + "classfile.assurance=" + classfile.assurance() + "\n";
        Files.writeString(manifest, text, StandardCharsets.UTF_8);
        return manifest;
    }
}
