package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class StdinOptionResultTest {
    @Test
    void guestDistinguishesBlankLineValueAndEof() throws Exception {
        Path fixture = Path.of("src/test/resources/oreslang/stdin-option.atto");
        OresCompiler.parseAndTypeCheck(Files.readString(fixture));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .in(new ByteArrayInputStream("\nhello\n".getBytes(StandardCharsets.UTF_8)))
                .out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, fixture.toFile()).build());
        }
        assertEquals("true\ntrue\nhello\ntrue\n", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void directStringBindingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                "pub fnc main():void{val String line=stdio.stdin.read_line();return;}"));
    }

    @Test
    void linkedHostExplicitlyAdmitsInputAndRetainsItsOwnership() throws Exception {
        var input = new ByteArrayInputStream("\nhello\n".getBytes(StandardCharsets.UTF_8)) {
            boolean closed;
            @Override public void close() { closed = true; }
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var policy = IsolatePolicy.developer();
        var result = LinkedProgramRunner.runWithInput(
                Path.of("src/test/resources/oreslang/stdin-option.atto"), policy,
                RuntimePermissions.fromCapabilities(policy.capabilities()),
                PermissionCheckMode.COMPILE, ExecutionProfile.serverJit(), Set.of(), Map.of(),
                input, output, new ByteArrayOutputStream());
        assertEquals(0, result.exitStatus());
        assertEquals("true\ntrue\nhello\ntrue\n", output.toString(StandardCharsets.UTF_8));
        assertFalse(input.closed);
    }

    @Test
    void inputAdmissionRequiresStdinCapability() {
        var policy = IsolatePolicy.strictFaas();
        var input = new ByteArrayInputStream("secret".getBytes(StandardCharsets.UTF_8));
        assertThrows(SecurityException.class, () -> LinkedProgramRunner.runWithInput(
                Path.of("src/test/resources/oreslang/stdin-option.atto"), policy,
                RuntimePermissions.fromCapabilities(policy.capabilities()),
                PermissionCheckMode.COMPILE, ExecutionProfile.serverJit(), Set.of(), Map.of(),
                input, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertEquals(6, input.available());
    }
}
