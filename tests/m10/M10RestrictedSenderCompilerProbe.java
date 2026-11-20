// Compiled restricted .atto sender -> injected host association gate.
// Does NOT verify canonical ProxyActor semantics or tenant-scoped IPC.
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

public final class M10RestrictedSenderCompilerProbe {
    private M10RestrictedSenderCompilerProbe() {}
    private static void require(boolean b, String reason) {
        if (!b) throw new AssertionError(reason);
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 1, "expected only sender .atto fixture");
        String src = Files.readString(Path.of(args[0]));
        require(AttoM10RestrictedSenderCompiler.compile_source(src)
            .contains(M10GeneratedSender.SOURCE_SHA256), "source SHA mismatch");
        var sent = new AtomicInteger();
        long result = M10GeneratedSender.main(value -> {
            require(value == 32L, "wrong compiled payload");
            sent.incrementAndGet();
            return Math.addExact(value, 10);
        });
        require(result == 42 && sent.get() == 1,
            "compiled sender did not invoke only authorized broker");
        for (String malformed : new String[] {
            src + " evil", src.replace("transport ipc", "transport auto"),
            src.replace("remote.add.v1", "other.service"),
            src.replace("proxy.request(32)", "proxy.request(32); os.read_env(\"secret\")"),
            src.replace("ProxyActor<RemoteAdder>", "ProxyActor<Other>"),
            src.replace("main(ProxyActor", "main(Other"),
            src.replace("32", "-1"), src.replace("32", "9999999999"),
            "", "x".repeat(4100)
        }) {
            try {
                AttoM10RestrictedSenderCompiler.compile_source(malformed);
                throw new AssertionError("malformed sender accepted");
            } catch (IllegalArgumentException expected) { }
        }
        try {
            M10GeneratedSender.main(null);
            throw new AssertionError("unbound proxy accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println(
            "M10_COMPILED_RESTRICTED_ATTO_SENDER_BROKER_AND_NEGATIVES=PASS");
    }
}
