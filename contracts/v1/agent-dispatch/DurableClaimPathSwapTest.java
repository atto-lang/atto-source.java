// Host-only stress: a parent path is repeatedly replaced with a symlink
// while dispatch attempts race. The decoy namespace must remain empty.
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
public final class DurableClaimPathSwapTest {
    static void ensure(boolean condition) { if (!condition) throw new AssertionError("path swap escaped pinned claim directory"); }
    static void privateDir(Path p) throws Exception {
        Files.createDirectory(p);
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
    }
    static AgentHostDispatchGate.Scope request(int n) {
        return new AgentHostDispatchGate.Scope("tenant", "worker", 7, 9,
            "a".repeat(40), "swap-" + n, "b".repeat(64), 10000, 1024, 24);
    }
    static long fileCount(Path p) throws Exception {
        try (Stream<Path> entries = Files.list(p)) { return entries.count(); }
    }
    public static void main(String[] unused) throws Exception {
        Path root = Files.createTempDirectory("ores-secure-claim-swap-");
        Path parent = root.resolve("alias");
        Path held = root.resolve("held");
        Path decoy = root.resolve("decoy");
        Path privateName = Path.of("private");
        privateDir(parent);
        privateDir(parent.resolve(privateName));
        privateDir(decoy);
        privateDir(decoy.resolve(privateName));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger swapped = new AtomicInteger();
        Thread attacker = new Thread(() -> {
            try {
                for (int i = 0; i < 800; i++) {
                    Files.move(parent, held);
                    Files.createSymbolicLink(parent, decoy);
                    swapped.incrementAndGet();
                    Files.delete(parent);
                    Files.move(held, parent);
                }
            } catch (Throwable t) { failure.compareAndSet(null, t); }
        });
        try {
            attacker.start();
            DurableLocalDispatchClaim store = new DurableLocalDispatchClaim(parent.resolve(privateName));
            for (int i = 0; i < 400; i++) store.claimOnce(request(i));
            attacker.join(15000);
            ensure(!attacker.isAlive() && failure.get() == null);
            ensure(swapped.get() == 800);
            ensure(fileCount(decoy.resolve(privateName)) == 0);
            ensure(store.claimOnce(request(10000)));
            System.out.println("JAVA_SECURE_DIRECTORY_SWAP_PASS (host-only)");
        } finally {
            attacker.join();
            try (Stream<Path> entries = Files.list(parent.resolve(privateName))) {
                for (Path entry : entries.toList()) Files.deleteIfExists(entry);
            }
            Files.delete(parent.resolve(privateName));
            Files.delete(parent);
            Files.delete(decoy.resolve(privateName));
            Files.delete(decoy);
            Files.delete(root);
        }
    }
}
