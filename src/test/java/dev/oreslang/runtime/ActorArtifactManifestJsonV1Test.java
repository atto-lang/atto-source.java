package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ActorArtifactManifestJsonV1Test {
    @Test
    void acceptsClosedValidManifest() {
        var manifest = ActorArtifactManifestJsonV1.parse(
                validJson().getBytes(StandardCharsets.UTF_8));

        assertEquals(ActorArtifactAdmission.SCHEMA_VERSION, manifest.schemaVersion());
        assertEquals(ActorArtifactAdmission.Backend.LLVM, manifest.compiler().backend());
        assertEquals(8192, manifest.artifact().sizeBytes());
        assertEquals(
                ActorArtifactAdmission.Format.NATIVE_EXECUTABLE,
                manifest.artifact().format());
        assertEquals(java.util.List.of("filesystem.read"),
                manifest.policy().requiredCapabilities());
    }

    @Test
    void rejectsDuplicateDecodedKeysAtAnyDepth() {
        for (String json : new String[]{
                "{"a":1,"a":2}",
                "{"a":1,"\\u0061":2}",
                "{"nested":{"x":0,"x":1}}"
        }) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> ActorArtifactManifestJsonV1.parse(
                            json.getBytes(StandardCharsets.UTF_8)));
            assertTrue(failure.getMessage().contains("duplicate"),
                    failure.getMessage());
        }
    }

    @Test
    void rejectsMalformedUtf8UnpairedSurrogatesAndNonJsonNumbers() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ActorArtifactManifestJsonV1.parse(
                        new byte[]{'{', '"', 'x', '"', ':', '"',
                                (byte) 0xc3, 0x28, '"', '}'}));

        for (String json : new String[]{
                "{"x":"\\uD800"}",
                "{"\\uDFFF":1}",
                "{"x":NaN}",
                "{"x":Infinity}",
                "{"x":-Infinity}",
                "{"x":1e10000}",
                "{"x":-1e10000}"
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ActorArtifactManifestJsonV1.parse(
                            json.getBytes(StandardCharsets.UTF_8)),
                    json);
        }
    }

    @Test
    void rejectsDepthSizeAndTrailingJson() {
        String deep = "[".repeat(65) + "0" + "]".repeat(65);
        assertThrows(
                IllegalArgumentException.class,
                () -> ActorArtifactManifestJsonV1.parse(
                        deep.getBytes(StandardCharsets.UTF_8)));

        byte[] oversized = (""" + "x".repeat(
                ActorArtifactManifestJsonV1.MAX_MANIFEST_BYTES + 1) + """)
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(
                IllegalArgumentException.class,
                () -> ActorArtifactManifestJsonV1.parse(oversized));

        assertThrows(
                IllegalArgumentException.class,
                () -> ActorArtifactManifestJsonV1.parse(
                        "{"ok":true}{"unsafe":true}"
                                .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsUnknownMissingAndWrongTypedSchemaFields() {
        String unknown = validJson().replace(
                ""schema_version"",
                ""runtime_codegen":true,"schema_version"");
        IllegalArgumentException unknownFailure = assertThrows(
                IllegalArgumentException.class,
                () -> ActorArtifactManifestJsonV1.parse(
                        unknown.getBytes(StandardCharsets.UTF_8)));
        assertTrue(unknownFailure.getMessage().contains("unknown field"),
                unknownFailure.getMessage());

        String missing = validJson().replace(
                ""expanded_sha256":"" + "2".repeat(64) + "",",
                "");
        IllegalArgumentException missingFailure = assertThrows(
                IllegalArgumentException.class,
                () -> ActorArtifactManifestJsonV1.parse(
                        missing.getBytes(StandardCharsets.UTF_8)));
        assertTrue(missingFailure.getMessage().contains("missing required"),
                missingFailure.getMessage());

        String wrongBoolean = validJson().replace(
                ""runtime_guest_compilation":false",
                ""runtime_guest_compilation":0");
        assertThrows(
                IllegalArgumentException.class,
                () -> ActorArtifactManifestJsonV1.parse(
                        wrongBoolean.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsFractionalUnsafeAndZeroArtifactSizes() {
        for (String size : new String[]{"1.5", "9007199254740992", "0"}) {
            String json = validJson().replace(""size_bytes":8192",
                    ""size_bytes":" + size);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ActorArtifactManifestJsonV1.parse(
                            json.getBytes(StandardCharsets.UTF_8)),
                    size);
        }
    }

    @Test
    void rejectsDuplicateCapabilitiesAndUnknownEnumValues() {
        String duplicateCaps = validJson().replace(
                "["filesystem.read"]",
                "["filesystem.read","filesystem.read"]");
        assertThrows(
                IllegalArgumentException.class,
                () -> ActorArtifactManifestJsonV1.parse(
                        duplicateCaps.getBytes(StandardCharsets.UTF_8)));

        String badBackend = validJson().replace(""backend":"llvm"",
                ""backend":"jit"");
        assertThrows(
                IllegalArgumentException.class,
                () -> ActorArtifactManifestJsonV1.parse(
                        badBackend.getBytes(StandardCharsets.UTF_8)));

        String badFormat = validJson().replace(
                ""format":"native-executable"",
                ""format":"script"");
        assertThrows(
                IllegalArgumentException.class,
                () -> ActorArtifactManifestJsonV1.parse(
                        badFormat.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void callerMutationAfterParseCannotChangeTypedManifest() {
        byte[] bytes = validJson().getBytes(StandardCharsets.UTF_8);
        var manifest = ActorArtifactManifestJsonV1.parse(bytes);
        java.util.Arrays.fill(bytes, (byte) 'x');
        assertEquals("llvm", manifest.compiler().backend().manifestName());
        assertEquals("4".repeat(64), manifest.artifact().fileSha256());
    }

    private static String validJson() {
        return """
                {
                  "schema_version": "oreslang.aot-artifact.v1",
                  "compiler": {
                    "backend": "llvm",
                    "version": "0.1.0-dev",
                    "git_commit": "%s"
                  },
                  "source_sha256": "%s",
                  "expanded_sha256": "%s",
                  "generated_ast_sha256": "%s",
                  "artifact": {
                    "file_sha256": "%s",
                    "size_bytes": 8192,
                    "format": "native-executable",
                    "target_triple": "x86_64-unknown-linux-gnu",
                    "abi": "sysv"
                  },
                  "policy": {
                    "runtime_macro_expansion": false,
                    "runtime_annotation_generation": false,
                    "runtime_guest_compilation": false,
                    "required_capabilities": ["filesystem.read"]
                  },
                  "provenance": {
                    "builder_id": "trusted-builder-1",
                    "signature": {
                      "algorithm": "ed25519",
                      "key_id": "release-key-1",
                      "value_base64": "AA=="
                    }
                  }
                }
                """.formatted(
                "a".repeat(40),
                "1".repeat(64),
                "2".repeat(64),
                "3".repeat(64),
                "4".repeat(64));
    }
}
