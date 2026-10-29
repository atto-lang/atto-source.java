// Executable host-only acceptance tests; no Oreslang guest or issuer.
public final class AgentHostDispatchGateTest {
    private static AgentHostDispatchGate gate() {
        return new AgentHostDispatchGate("tenant","worker",7,9,"a".repeat(40),100);
    }
    private static AgentHostDispatchGate.Scope scope() {
        return new AgentHostDispatchGate.Scope("tenant","worker",7,9,
            "a".repeat(40),"dispatch","b".repeat(64),10000,1024,24);
    }
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] unused) {
        class Host implements AgentHostDispatchGate.TrustedHost {
            int current, auth, claims, sends; long clock = 9000;
            boolean deny, revokeAfterClaim, throwClaim, advanceClockOnClaim;
            @Override public boolean isCurrent(AgentHostDispatchGate.Scope s) {
                current++; return !revokeAfterClaim || current == 1;
            }
            @Override public boolean authorize(AgentHostDispatchGate.Scope s) {
                auth++; return !deny;
            }
            @Override public boolean claimOnce(AgentHostDispatchGate.Scope s) {
                claims++; if (throwClaim) throw new IllegalStateException("IO");
                if (advanceClockOnClaim) clock = 10000;
                return claims == 1;
            }
            @Override public long nowMs() { return clock; }
            @Override public boolean submitDataOnly(AgentHostDispatchGate.Scope s) {
                sends++; return true;
            }
        }
        Host h = new Host(); AgentHostDispatchGate g=gate();
        check(g.dispatch(scope(),h,9000)); check(!g.dispatch(scope(),h,9000));
        check(h.current==2 && h.auth==2 && h.claims==1 && h.sends==1);
        h=new Host(); check(!gate().dispatch(new AgentHostDispatchGate.Scope(
            "other","worker",7,9,"a".repeat(40),"dispatch","b".repeat(64),
            10000,1024,24),h,9000)); check(h.claims==0);
        h=new Host(); check(!gate().dispatch(new AgentHostDispatchGate.Scope(
            "tenant","worker",8,9,"a".repeat(40),"dispatch","b".repeat(64),
            10000,1024,24),h,9000)); check(h.claims==0);
        h=new Host(); check(!gate().dispatch(new AgentHostDispatchGate.Scope(
            "tenant","worker",7,10,"a".repeat(40),"dispatch","b".repeat(64),
            10000,1024,24),h,9000)); check(h.claims==0);
        h=new Host(); check(!gate().dispatch(new AgentHostDispatchGate.Scope(
            "tenant","worker",7,9,"a".repeat(40),"dispatch","X".repeat(64),
            10000,1024,24),h,9000));
        h=new Host(); check(!gate().dispatch(new AgentHostDispatchGate.Scope(
            "tenant","worker",7,9,"a".repeat(40),"dispatch","b".repeat(64),
            9000,1024,24),h,9000));
        h=new Host(); check(!gate().dispatch(new AgentHostDispatchGate.Scope(
            "tenant","worker",7,9,"a".repeat(40),"dispatch","b".repeat(64),
            10000,1024,101),h,9000));
        h=new Host(); check(!gate().dispatch(new AgentHostDispatchGate.Scope(
            "tenant","worker",7,9,"a".repeat(40),"dispatch","b".repeat(64),
            10000,65537,24),h,9000));
        h=new Host(); h.deny=true; check(!gate().dispatch(scope(),h,9000));
        check(h.claims==0 && h.sends==0);
        h=new Host(); h.revokeAfterClaim=true; check(!gate().dispatch(scope(),h,9000));
        check(h.claims==1 && h.sends==0);
        h=new Host(); h.throwClaim=true; check(!gate().dispatch(scope(),h,9000));
        check(h.sends==0);
        check(!gate().dispatch(scope(),null,9000));
        h = new Host(); h.advanceClockOnClaim = true;
        check(!gate().dispatch(scope(),h,9000));
        check(h.claims == 1 && h.sends == 0);
        h = new Host(); h.clock = 10000;
        check(!gate().dispatch(scope(),h,9000)); check(h.sends == 0);
        h = new Host(); h.clock = 0;
        check(!gate().dispatch(scope(),h,9000)); check(h.claims == 0);
        h = new Host(); final Host rollback = h;
        final long[] ticks = {9000, 8999};
        // The trusted clock can be controlled independently of other callbacks.
        AgentHostDispatchGate.TrustedHost backing = new AgentHostDispatchGate.TrustedHost() {
            int reads;
            @Override public long nowMs() {return ticks[Math.min(reads++, 1)];}
            @Override public boolean isCurrent(AgentHostDispatchGate.Scope x) {return true;}
            @Override public boolean authorize(AgentHostDispatchGate.Scope x) {return true;}
            @Override public boolean claimOnce(AgentHostDispatchGate.Scope x) {return true;}
            @Override public boolean submitDataOnly(AgentHostDispatchGate.Scope x) {throw new AssertionError("clock rollback submitted");}
        };
        check(!gate().dispatch(scope(),backing,9000));
        System.out.println("AGENT_HOST_DISPATCH_CONTRACT PASS (host only, no native guest)");
    }
}
