// Restricted Atto source receiver compiler file and grammar security probe.
// This verifies source-to-Java generation, not canonical actor guest support.
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class M10RestrictedReceiverCompilerProbe {
    private M10RestrictedReceiverCompilerProbe() {}
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    @FunctionalInterface private interface IoAction { void run() throws Exception; }
    private static void must_reject_io(IoAction action, String message) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 1, "receiver fixture required");
        String input = Files.readString(Path.of(args[0]));
        require(AttoM10RestrictedActorCompiler.compile_source(input)
            .contains(M10GeneratedActor.SOURCE_SHA256), "source hash mismatch");
        require(M10GeneratedActor.on_message(32L) == 42L,
            "generated receiver returned incorrect typed result");
        require(M10GeneratedActor.new_handler().receive(32L) == 42L,
            "typed M6 handler not bound to generated receiver");
        try {
            M10GeneratedActor.on_message(Long.MAX_VALUE);
            throw new AssertionError("checked overflow silently wrapped");
        } catch (ArithmeticException expected) { }
        for (String bad : new String[] {
            input + " evil", input.replace("RemoteAdder", "ForgedActor"),
            input.replace("value + 10", "os.read_env(\"TOKEN\")"),
            "", "x".repeat(4100)
        }) {
            try {
                AttoM10RestrictedActorCompiler.compile_source(bad);
                throw new AssertionError("untrusted receiver grammar accepted");
            } catch (IllegalArgumentException expected) { }
        }
        Path dir = Files.createTempDirectory("atto-m10-receiver-test-");
        Path source = dir.resolve("source.atto");
        Path link = dir.resolve("symlink.atto");
        Path oversized = dir.resolve("oversized.atto");
        Path invalidUtf8 = dir.resolve("invalid.atto");
        Path output = dir.resolve("M10GeneratedActor.java");
        Path bait = dir.resolve("bait.java");
        try {
            Files.writeString(source, input);
            Files.createSymbolicLink(link, source);
            must_reject_io(() -> AttoM10RestrictedActorCompiler.main(new String[] {
                link.toString(), output.toString()}), "source symlink admitted");
            Files.createSymbolicLink(output, bait);
            must_reject_io(() -> AttoM10RestrictedActorCompiler.main(new String[] {
                source.toString(), output.toString()}), "dangling output symlink admitted");
            require(!Files.exists(bait), "output redirected through symlink");
            Files.delete(output);
            Files.writeString(oversized, "x".repeat(4100));
            must_reject_io(() -> AttoM10RestrictedActorCompiler.main(new String[] {
                oversized.toString(), output.toString()}), "oversize receiver accepted");
            Files.write(invalidUtf8, new byte[] {(byte) 0xc3, (byte) 0x28});
            must_reject_io(() -> AttoM10RestrictedActorCompiler.main(new String[] {
                invalidUtf8.toString(), output.toString()}), "invalid UTF-8 accepted");
            require(!Files.exists(output), "invalid input created generated output");
            AttoM10RestrictedActorCompiler.main(new String[] {
                source.toString(), output.toString()});
            require(Files.readString(output).contains(M10GeneratedActor.SOURCE_SHA256),
                "generated output did not reflect original receiver fixture");
            must_reject_io(() -> AttoM10RestrictedActorCompiler.main(new String[] {
                source.toString(), output.toString()}), "existing output overwritten");
        } finally {
            for (Path p : new Path[] {link,output,source,oversized,invalidUtf8,bait})
                Files.deleteIfExists(p);
            Files.deleteIfExists(dir);
        }
        System.out.println("M10_RECEIVER_SOURCE_FD_UTF8_AND_OUTPUT_NOFOLLOW=PASS");
        System.out.println("M10_RECEIVER_COMPILED_SCALAR_HANDLER_CHECKED_ARITHMETIC=PASS");
    }
}
