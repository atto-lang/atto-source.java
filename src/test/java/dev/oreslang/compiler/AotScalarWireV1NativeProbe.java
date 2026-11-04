package dev.oreslang.compiler;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Native Image smoke entry for the exact compiler-side JSON/MessagePack codec.
 * This is HOST native AOT, not compiled AttoLang guest AOT parity.
 */
public final class AotScalarWireV1NativeProbe {
    private AotScalarWireV1NativeProbe() { }

    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("no probe arguments");
        String source = """
                pub define class WirePayload as
                  pub int count: 0
                  pub string title: "before"
                  pub bool enabled: false
                  int private_marker: 9
                end
                """;
        var schema = OresCompiler.compileAotScalarWireSchemaV1(
                source, "native-host-smoke@1", "type_of<WirePayload>()");
        var json = schema.decodeJsonUtf8(
                "{\"count\":42,\"title\":\"AOT\",\"enabled\":true}"
                        .getBytes(StandardCharsets.UTF_8));
        byte[] messagePack = new byte[] {
                (byte) 0x83,
                (byte) 0xa5, 'c', 'o', 'u', 'n', 't', (byte) 0x2a,
                (byte) 0xa5, 't', 'i', 't', 'l', 'e',
                    (byte) 0xa3, 'A', 'O', 'T',
                (byte) 0xa7, 'e', 'n', 'a', 'b', 'l', 'e', 'd', (byte) 0xc3
        };
        var msgpack = schema.decodeMessagePack(messagePack);
        if (!json.equals(msgpack) ||
                !json.slots().equals(List.of(42L, "AOT", Boolean.TRUE)) ||
                schema.schemaSha256Hex().length() != 64 ||
                schema.typeIdHex().length() != 64) {
            throw new AssertionError("host native AOT scalar codec mismatch");
        }
        schema.requireSchemaFingerprint(schema.schemaSha256Hex());
        try {
            schema.decodeJsonWithFingerprint(
                    "{\"count\":42,\"title\":\"AOT\",\"enabled\":true}",
                    "0".repeat(64));
            throw new AssertionError("schema fingerprint must be enforced");
        } catch (IllegalArgumentException expected) {
            // Strong equality fence, not an authentication assertion.
        }
        System.out.println("HOST_NATIVE_AOT_SCALAR_WIRE_PASS json=42 msgpack=42");
        System.out.println("GUEST_NATIVE_AOT_NOT_EXECUTED");
    }
}
