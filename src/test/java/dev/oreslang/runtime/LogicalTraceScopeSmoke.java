package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.List;

public class LogicalTraceScopeSmoke {
  static int assertions;
  static void check(boolean yes, String msg) {
    if (!yes) throw new AssertionError(msg);
    assertions++;
  }
  static TraceContextV1 ctx(long span) {return new TraceContextV1(1,2,span,1);}
  public static void main(String[] args) throws Exception {
    LogicalTraceScopeV1 rt = new LogicalTraceScopeV1(100, 5, ctx(7),true,3);
    check(rt.context().spanId()==7, "inherited context");
    var outer = rt.enter(1,ctx(8));
    var inner = rt.enter(2,ctx(9));
    check(rt.depth()==2 && rt.context().spanId()==9,"nested scopes");
    boolean nonLifo=false;
    try { rt.exit(outer,ScopedTelemetryProbe.Outcome.OK);}
    catch(IllegalStateException expected){nonLifo=true;}
    check(nonLifo,"LIFO enforced");
    var c = rt.suspend();
    check(rt.context().spanId()==9,"context retained on suspension");
    var pool=Executors.newSingleThreadExecutor();
    try{
      check(pool.submit(()->rt.resume(c)).get(5,TimeUnit.SECONDS),"resume on foreign carrier");
    } finally { pool.shutdownNow(); }
    check(!rt.resume(c),"replayed continuation rejected");
    check(rt.exit(inner, ScopedTelemetryProbe.Outcome.OK)!=null,"inner exited");
    check(rt.context().spanId()==8,"parent restored");
    check(rt.exit(outer, ScopedTelemetryProbe.Outcome.OK)!=null,"outer exited");
    check(rt.exit(outer, ScopedTelemetryProbe.Outcome.OK)==null,"double exit denied");
    check(rt.context().spanId()==7,"inherited restored");
    check(rt.retire(ScopedTelemetryProbe.Outcome.CANCELLED).isEmpty(),"empty retirement");

    LogicalTraceScopeV1 stopped = new LogicalTraceScopeV1(100, 8,ctx(10),true,3);
    var st1=stopped.enter(1,ctx(11));
    var st2=stopped.enter(2,ctx(12));
    var frozen=stopped.suspend();
    var outcomes=stopped.retire(ScopedTelemetryProbe.Outcome.ACTOR_STOPPED);
    check(outcomes.size()==2,"cancel drains all nested scopes");
    check(outcomes.stream().allMatch(q->q.outcome()==ScopedTelemetryProbe.Outcome.ACTOR_STOPPED),"stop status");
    check(stopped.depth()==0 && stopped.isRetired(),"retired state");
    check(!stopped.resume(frozen),"retire invalidates ticket");
    check(stopped.exit(st1,ScopedTelemetryProbe.Outcome.OK)==null,"stale outer scope");
    check(stopped.exit(st2,ScopedTelemetryProbe.Outcome.OK)==null,"stale inner scope");
    check(stopped.retire(ScopedTelemetryProbe.Outcome.OK).isEmpty(),"no double retire");

    LogicalTraceScopeV1 disabled = new LogicalTraceScopeV1(3, 4,ctx(14),false,2);
    check(disabled.enter(0,null)==null,"disabled enter without allocation");
    check(disabled.suspend()==null,"disabled suspend receipt");
    check(disabled.resume(null),"disabled resume");
    check(disabled.context().spanId()==14,"context propagation independent of recording");
    check(disabled.retire(ScopedTelemetryProbe.Outcome.OK).isEmpty(),"disabled retire");

    LogicalTraceScopeV1 cap = new LogicalTraceScopeV1(5, 5,ctx(20),true,1);
    cap.enter(1,ctx(21));
    boolean denied=false;
    try{cap.enter(2,ctx(22));}catch(IllegalStateException expected){denied=true;}
    check(denied,"depth cap");
    cap.retire(ScopedTelemetryProbe.Outcome.CANCELLED);

    LogicalTraceScopeV1 forgery = new LogicalTraceScopeV1(9, 9,ctx(20),true,2);
    denied=false;
    try{forgery.enter(3,new TraceContextV1(3,4,22,1));}
    catch(IllegalArgumentException expected){denied=true;}
    check(denied,"mismatched trace rejected");
    denied=false;
    try{forgery.enter(3,ctx(20));}catch(IllegalArgumentException expected){denied=true;}
    check(denied,"reused parent span id rejected");

    LogicalTraceScopeV1 raced = new LogicalTraceScopeV1(11,10,ctx(30),true,3);
    raced.enter(1,ctx(31));
    var ticket = raced.suspend();
    var gate = new CountDownLatch(1);
    var duo = Executors.newFixedThreadPool(2);
    try{
      var resume = duo.submit(()->{gate.await();return raced.resume(ticket);});
      var retire = duo.submit(()->{gate.await();return raced.retire(ScopedTelemetryProbe.Outcome.CANCELLED);});
      gate.countDown();
      boolean resumed=resume.get(5,TimeUnit.SECONDS);
      var receipts=retire.get(5,TimeUnit.SECONDS);
      check(receipts.size()==1 && raced.isRetired(),"race retires exactly once");
      check(!raced.resume(ticket),"post-stop replay fails");
    }finally{duo.shutdownNow();}
    System.out.println("LOGICAL_TRACE_SCOPE_PASS assertions="+assertions);
  }
}
