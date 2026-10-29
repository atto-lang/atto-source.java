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
                "094cebe33e39cfe9c78133d726cbe2ea792b663bd24c596b77b903dc1dbf3e66.claim"));
            check(store.claimOnce(s)); check(!store.claimOnce(s));
            check(!store.claimOnce(scope("dispatch", "c".repeat(64))));
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
            System.out.println("DURABLE_LOCAL_CLAIM_PASS host-only; no remote provider");
        } finally {delete(dir);}
    }
}
