package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;

/** Host-only compiler-table proof; NOT guest type_of or Native Image AOT. */
final class AotReflectionTableV1Test {
    @Test
    void compilerOwnedMetadataIsDeterministicBoundedAndPrivateFieldFree() throws Exception {
        AotReflectionTableV1Smoke.main(new String[0]);
    }
    @Test
    void descriptorTableRefusesOversizedPayloadBeforeAllocation() {
        AotReflectionLimitProbe.main(new String[0]);
    }
    @Test
    void descriptorReaderRejectsMalformedAndTamperedSections() throws Exception {
        AotReflectionTableV1ReaderSmoke.main(new String[0]);
    }
    @Test
    void encoderAndReaderRoundTripOnlyPublicMetadata() {
        var fields = java.util.List.of(
            new AotReflectionTableV1.Field("public_id", "core.int", AotReflectionTableV1.Visibility.PUBLIC),
            new AotReflectionTableV1.Field("private_key_marker", "core.text", AotReflectionTableV1.Visibility.PRIVATE),
            new AotReflectionTableV1.Field("public_name", "core.text", AotReflectionTableV1.Visibility.PUBLIC));
        var generic = new AotReflectionTableV1.ResolvedType(
            "pkg@revision", "Record", java.util.List.of("core.int"), fields);
        var encoded = AotReflectionTableV1.encode(java.util.List.of(generic));
        var decoded = AotReflectionTableV1Reader.decode(encoded.bytes());
        if (decoded.types().size() != 1 || !decoded.types().get(0).resolvedSymbol().equals("Record") ||
                !decoded.types().get(0).genericArguments().equals(java.util.List.of("core.int")) ||
                !decoded.types().get(0).publicFields().stream().map(AotReflectionTableV1Reader.FieldDescriptor::name)
                    .toList().equals(java.util.List.of("public_id", "public_name")) ||
                new String(encoded.bytes(), java.nio.charset.StandardCharsets.UTF_8).contains("private_key_marker")) {
            throw new AssertionError("compiler-owned AOT descriptor roundtrip exposed or corrupted metadata");
        }
        if (decoded.findByTypeId(decoded.types().get(0).typeIdHex()).isEmpty()) {
            throw new AssertionError("closed-world descriptor lookup must resolve a retained nominal type");
        }
    }

    @Test
    void rejectsOversizedResolvedTypeCollectionsBeforeDefensiveCopy() {
        var publicField = new AotReflectionTableV1.Field(
                "f", "int", AotReflectionTableV1.Visibility.PUBLIC);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AotReflectionTableV1.ResolvedType("pkg@revision", "N",
                        java.util.Collections.nCopies(65, "int"), java.util.List.of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AotReflectionTableV1.ResolvedType("pkg@revision", "N",
                        java.util.List.of(), java.util.Collections.nCopies(257, publicField)));
    }
}
