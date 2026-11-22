// Host-owned proxy actor ACK gate v1. NOT a wire protocol, peer verifier,
// socket gateway, compiled .atto guest or Native Image execution proof.
// Trusted host I/O must authenticate AND authorize destination/provider
// receipts before calling apply_verified. This class validates correlation
// and stage semantics only; untrusted guest code cannot hold this object.
public final class ProxyReceiptGateV1 {
    public record Identity(int protocolMajor, long tenant, long callerIncarnation,
                           long targetActor, long targetIncarnation, long associationGeneration,
                           long placementEpoch,
                           long messageId, long attempt) {
        public boolean valid() {
            return protocolMajor == 1 && tenant > 0 && callerIncarnation > 0 &&
                   targetActor > 0 && targetIncarnation > 0 && associationGeneration > 0 &&
                   placementEpoch > 0 &&
                   messageId > 0 && attempt > 0;
        }
    }
    public enum Event {
        ADMITTED, STARTED, PROCESSED, DURABLE_INBOX, DURABLE_RESULT,
        REJECTED, FAILED, INDETERMINATE
    }
    public enum Stage { DISPATCHED, ADMITTED, STARTED, PROCESSED }
    public enum Outcome { PENDING, REJECTED, FAILED, INDETERMINATE }

    private final Identity expected;
    private Stage stage = Stage.DISPATCHED;
    private Outcome outcome = Outcome.PENDING;
    private boolean durableInbox;
    private boolean durableResult;
    public ProxyReceiptGateV1(Identity expected) {
        this.expected = java.util.Objects.requireNonNull(expected);
    }
    public synchronized Stage stage() { return stage; }
    public synchronized Outcome outcome() { return outcome; }
    public synchronized boolean destination_admitted() {
        return stage.ordinal() >= Stage.ADMITTED.ordinal();
    }
    public synchronized boolean durable_inbox() { return durableInbox; }
    public synchronized boolean durable_result() { return durableResult; }

    public synchronized boolean apply_verified(Identity observed, Event event) {
        if (!expected.valid() || !expected.equals(observed) ||
            outcome != Outcome.PENDING || event == null) return false;
        switch (event) {
            case ADMITTED -> {
                if (stage.ordinal() < Stage.ADMITTED.ordinal()) stage = Stage.ADMITTED;
                return true;
            }
            case STARTED -> {
                if (stage.ordinal() < Stage.ADMITTED.ordinal()) return false;
                if (stage.ordinal() < Stage.STARTED.ordinal()) stage = Stage.STARTED;
                return true;
            }
            case PROCESSED -> {
                if (stage.ordinal() < Stage.STARTED.ordinal()) return false;
                stage = Stage.PROCESSED;
                return true;
            }
            case DURABLE_INBOX -> {
                if (stage.ordinal() < Stage.ADMITTED.ordinal()) return false;
                durableInbox = true;
                return true;
            }
            case DURABLE_RESULT -> {
                if (stage != Stage.PROCESSED) return false;
                durableResult = true;
                return true;
            }
            case REJECTED -> {
                if (stage != Stage.DISPATCHED) return false;
                outcome = Outcome.REJECTED;
                return true;
            }
            case FAILED -> {
                if (stage == Stage.DISPATCHED || stage == Stage.PROCESSED) return false;
                outcome = Outcome.FAILED;
                return true;
            }
            case INDETERMINATE -> {
                if (stage == Stage.PROCESSED) return false;
                outcome = Outcome.INDETERMINATE;
                return true;
            }
            default -> { return false; }
        }
    }
}
