// HOST ONLY. Not exposed as an Oreslang guest intrinsic or permission issuer.
// The trusted embedding host must implement durable atomic claims and independently
// revalidate policy again in the provider. No caller-supplied boolean is authority.
import java.util.Objects;

public final class AgentHostDispatchGate {
    public record Scope(String tenantId, String actorId, long actorGeneration,
                        long activationEpoch, String exactGitRevision,
                        String dispatchId, String contextSha256,
                        long expiresAtMs, long utf8Bytes, long measuredTokens) {}
    public interface TrustedHost {
        boolean isCurrent(Scope scope);
        boolean authorize(Scope scope);
        boolean claimOnce(Scope scope);
        boolean submitDataOnly(Scope scope);
        long nowMs(); // mandatory trusted host clock
    }
    private final String tenantId, actorId, revision;
    private final long generation, epoch, tokenCap;
    private boolean used;

    public AgentHostDispatchGate(String tenantId, String actorId, long generation,
                                 long epoch, String revision, long tokenCap) {
        this.tenantId = tenantId;
        this.actorId = actorId;
        this.generation = generation;
        this.epoch = epoch;
        this.revision = revision;
        this.tokenCap = tokenCap;
    }
    private static boolean id(String s) {
        if (s == null || s.isEmpty() || s.length() > 128) return false;
        for (int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' ||
                  c >= '0' && c <= '9' || c == '_' || c == '-' ||
                  c == '.' || c == ':' || c == '@' || c == '/')) return false;
        }
        return true;
    }
    private static boolean lowerHex(String s, int length) {
        if (s == null || s.length() != length) return false;
        for (int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) return false;
        }
        return true;
    }
    public synchronized boolean dispatch(Scope s, TrustedHost host, long nowMs) {
        if (used) return false;
        used = true; // unknown remote outcomes cannot be replayed in this instance
        if (s == null || host == null || !id(tenantId) || !id(actorId) ||
            !id(s.dispatchId()) || !Objects.equals(s.tenantId(), tenantId) ||
            !Objects.equals(s.actorId(), actorId) ||
            generation <= 0 || s.actorGeneration() != generation ||
            epoch <= 0 || s.activationEpoch() != epoch ||
            !lowerHex(revision, 40) || !Objects.equals(s.exactGitRevision(), revision) ||
            !lowerHex(s.contextSha256(), 64) || s.expiresAtMs() <= nowMs ||
            s.utf8Bytes() <= 0 || s.utf8Bytes() > 65536 ||
            s.measuredTokens() <= 0 || s.measuredTokens() > 16384 ||
            tokenCap <= 0 || s.measuredTokens() > tokenCap) return false;
        try {
            final long admittedAt = host.nowMs();
            if (admittedAt <= 0 || s.expiresAtMs() <= admittedAt) return false;
            if (!host.isCurrent(s) || !host.authorize(s) || !host.claimOnce(s) ||
                !host.isCurrent(s) || !host.authorize(s)) return false;
            final long beforeSubmit = host.nowMs();
            return beforeSubmit >= admittedAt && s.expiresAtMs() > beforeSubmit &&
                   host.submitDataOnly(s);
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
