import java.nio.file.Path;
public final class AgentClaimProbe {
  public static void main(String[] args) {
    if (args.length != 2 && args.length != 3) System.exit(10);
    var s = new AgentHostDispatchGate.Scope("tenant", "worker", 7, 9,
      "a".repeat(40), args.length == 3 ? args[2] : "dispatch", args[1], 10000, 1024, 24);
    var store = new DurableLocalDispatchClaim(Path.of(args[0]));
    var gate = new AgentHostDispatchGate("tenant", "worker", 7, 9, "a".repeat(40), 100);
    var host = new AgentHostDispatchGate.TrustedHost() {
      public boolean isCurrent(AgentHostDispatchGate.Scope x){return true;}
      public boolean authorize(AgentHostDispatchGate.Scope x){return true;}
      public boolean claimOnce(AgentHostDispatchGate.Scope x){return store.claimOnce(x);}
      public long nowMs(){return 9000;}
      public boolean submitDataOnly(AgentHostDispatchGate.Scope x){return true;}
    };
    System.exit(gate.dispatch(s, host, 9000) ? 0 : 2);
  }
}
