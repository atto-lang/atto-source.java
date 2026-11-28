// HOST ONLY: PostgreSQL single-statement transactional dispatch admission.
// The runtime principal gets only EXECUTE on the audited SECURITY DEFINER
// function, never direct UPDATE/DELETE on claims or actor fences.
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

public final class PostgresActorDispatchClaim {
    private static final String CLAIM_SQL =
        "SELECT ores_dispatch.claim_once_v1(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private final DataSource source;
    public PostgresActorDispatchClaim(DataSource source) {
        if (source == null) throw new IllegalArgumentException("trusted DataSource required");
        this.source = source;
    }
    public boolean claimOnce(AgentHostDispatchGate.Scope s) {
        if (s == null || s.utf8Bytes() < 1 || s.utf8Bytes() > 65536 ||
            s.measuredTokens() < 1 || s.measuredTokens() > 16384 ||
            s.actorGeneration() < 1 || s.activationEpoch() < 1 ||
            s.expiresAtMs() < 1) return false;
        try (Connection db = source.getConnection()) {
            // An unknown transaction / caller-supplied connection could defer
            // commit until AFTER the provider effect. Fail closed instead.
            if (!db.getAutoCommit() || db.isReadOnly()) return false;
            try (PreparedStatement ps = db.prepareStatement(CLAIM_SQL)) {
                ps.setQueryTimeout(10);
                ps.setString(1, s.tenantId());
                ps.setString(2, s.actorId());
                ps.setString(3, s.dispatchId());
                ps.setString(4, s.contextSha256());
                ps.setLong(5, s.actorGeneration());
                ps.setLong(6, s.activationEpoch());
                ps.setString(7, s.exactGitRevision());
                ps.setLong(8, s.expiresAtMs());
                ps.setInt(9, (int) s.utf8Bytes());
                ps.setInt(10, (int) s.measuredTokens());
                try (ResultSet r = ps.executeQuery()) {
                    return r.next() && r.getBoolean(1) && !r.wasNull() && !r.next();
                }
            }
        } catch (SQLException | RuntimeException error) {
            // Timeouts, disconnects and unknown commit results are NOT retried.
            return false;
        }
    }
}
