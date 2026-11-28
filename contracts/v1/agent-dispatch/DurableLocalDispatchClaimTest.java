// Local filesystem host-only durability test; no guest or provider proof.
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public final class DurableLocalDispatchClaimTest {
    private static void check(boolean v) { if (!v) throw new AssertionError(); }
    private static AgentHostDispatchGate.Scope scope(String dispatch, String digest) {
        return new AgentHostDispatchGate.Scope("tenant", "worker", 7, 9,
            "a".repeat(40), dispatch, digest, 10000, 1024, 24);
    }
    private static void delete(Path dir) throws Exception {
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) Files.delete(f);
        }
        Files.delete(dir);
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("ores-agent-claim-");
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        try {
            var store = new DurableLocalDispatchClaim(dir);
            var s = scope("dispatch", "b".repeat(64));
            check(DurableLocalDispatchClaim.claimFilename(s).equals(
                "0d308e5482aa6701e9dc55b9c1e70e311eb83188e26c8bf86befa838c2d11e21.claim"));
            check(store.claimOnce(s)); check(!store.claimOnce(s));
            // Mutable JVM properties must not supply filesystem ownership authority.
            String originalHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", "/nonexistent/ores-spoofed-owner");
                check(store.claimOnce(scope("os-owner", "b".repeat(64))));
            } finally {
                if (originalHome != null) System.setProperty("user.home", originalHome);
                else System.clearProperty("user.home");
            }
            check(!store.claimOnce(scope("dispatch", "c".repeat(64))));
            // Stable idempotency key must survive reincarnation and reload.
            var reincarnated = new AgentHostDispatchGate.Scope("tenant", "worker", 8, 10,
                "c".repeat(40), "dispatch", "b".repeat(64), 10000, 1024, 24);
            check(DurableLocalDispatchClaim.claimFilename(s).equals(
                DurableLocalDispatchClaim.claimFilename(reincarnated)));
            check(!store.claimOnce(reincarnated));
            check(store.claimOnce(scope("independent", "b".repeat(64))));
            // Independent OS processes must compete on the same exclusive name.
            List<Process> processes = new ArrayList<>();
            for(int i=0;i<8;i++) {
                processes.add(new ProcessBuilder(System.getProperty("java.home") + "/bin/java",
                    "-cp", System.getProperty("java.class.path"),
                    "AgentClaimProbe", dir.toString(), "b".repeat(64), "racing")
                    .redirectErrorStream(true).start());
            }
            // The probe defaults to dispatch/racing only when third arg provided.
            int winners=0;
            for(Process p:processes) {
                check(p.waitFor(10,TimeUnit.SECONDS));
                int code=p.exitValue();
                if(code==0) winners++;
                else check(code==2);
            }
            check(winners==1);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
            check(!store.claimOnce(scope("insecure", "b".repeat(64))));
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            Path link = Path.of(dir.toString() + "-link");
            Files.createSymbolicLink(link,dir);
            try {check(!new DurableLocalDispatchClaim(link).claimOnce(
                scope("symlink", "b".repeat(64))));}
            finally {Files.delete(link);}
            // A symlink at an ancestor (rather than the final directory)
            // must not relocate the durable claim namespace.
            Path outer = Files.createTempDirectory("ores-claim-ancestor-");
            Path real = Files.createDirectory(outer.resolve("real"));
            Path inner = Files.createDirectory(real.resolve("private"));
            Files.setPosixFilePermissions(inner, PosixFilePermissions.fromString("rwx------"));
            Path ancestorLink = outer.resolve("alias");
            Files.createSymbolicLink(ancestorLink, real);
            try {
                check(!new DurableLocalDispatchClaim(ancestorLink.resolve("private"))
                    .claimOnce(scope("ancestor-symlink", "b".repeat(64))));
                check(!new DurableLocalDispatchClaim(inner.resolve("..").resolve("private"))
                    .claimOnce(scope("noncanonical", "b".repeat(64))));
                check(!new DurableLocalDispatchClaim(Path.of("relative-private"))
                    .claimOnce(scope("relative-path", "b".repeat(64))));
            } finally {
                Files.delete(ancestorLink);
                delete(inner);
                Files.delete(real);
                Files.delete(outer);
            }
            System.out.println("DURABLE_LOCAL_CLAIM_PASS host-only; no remote provider");
        } finally {delete(dir);}
    }
}
