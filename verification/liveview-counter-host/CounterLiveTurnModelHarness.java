package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Stock-JDK offline assertions. NOT native Oreslang or Maven/JUnit evidence. */
public final class CounterLiveTurnModelHarness {
  private static int checks;
  private static void ok(boolean yes, String message) {
    checks++;
    if (!yes) throw new AssertionError(message);
  }
  private static void rejects(Runnable callback) {
    checks++;
    try {callback.run();} catch (IllegalArgumentException|IllegalStateException e) {return;}
    throw new AssertionError("expected fail-closed exception");
  }
  private static String id(int n) {return String.format("00000000-0000-0000-0000-%012d",n);}
  public static void main(String[] args) throws Exception {
    var machine = new CounterLiveTurnModel();
    var first = machine.increment("alice",id(1),0);
    ok(first.applied(),"first applies");
    ok(first.revision()==1,"revision increments");
    ok(machine.count()==1,"count increments");
    ok(first.frames().size()==2,"patch and ack");
    ok(first.frames().get(0).equals("{\"v\":1,\"type\":\"patch\",\"prev\":0,\"rev\":1,\"ops\":[{\"op\":\"set_text\",\"id\":\"count_value\",\"text\":\"1\"}]}"),"canonical patch bytes");
    ok(first.frames().get(1).equals("{\"v\":1,\"type\":\"event_ack\",\"event_id\":\""+id(1)+"\",\"rev\":1}"),"canonical ack bytes");
    var replay=machine.increment("alice",id(1),0);
    ok(!replay.applied() && replay.frames().size()==1,"replay ack only");
    ok(machine.count()==1 && machine.revision()==1,"replay idempotent");
    rejects(()->machine.increment("bob",id(1),0));
    rejects(()->machine.increment("alice",id(1),1));
    ok(!machine.increment("alice",id(2),0).applied(),"stale rejected");
    ok(machine.revision()==1,"stale unchanged");
    rejects(()->machine.increment("",id(3),1));
    rejects(()->machine.increment("alice","bad",1));
    rejects(()->machine.increment("alice",id(3),-1));
    rejects(()->machine.increment("alice",id(3),CounterLiveTurnModel.MAX_REV+1));
    var second=machine.increment("alice",id(2),1);
    ok(second.applied() && machine.count()==2,"stale rejected ID can be used with fresh revision");
    ok(machine.increment("alice",id(2),1).frames().size()==1,"2nd replay");
    var reentry=new CounterLiveTurnModel();
    rejects(()->reentry.increment("alice",id(4),0,()->reentry.increment("alice",id(5),0)));
    ok(reentry.revision()==0 && reentry.count()==0,"nested receive rolled back");
    ok(reentry.increment("alice",id(4),0).applied(),"guard released after callback failure");
    var closing=new CounterLiveTurnModel();
    rejects(()->closing.increment("alice",id(6),0,closing::retire));
    ok(closing.count()==0 && closing.revision()==0,"retirement cancels commit");
    rejects(()->closing.increment("alice",id(6),0));
    var invalid=new CounterLiveTurnModel();
    rejects(()->invalid.increment("unpaired\ud800",id(7),0));
    ok(invalid.increment("alice",id(7),0).applied(),"unicode failure released guard");
    ok(CounterLiveTurnModel.jsonString("hi\n\"\\🌐").equals("\"hi\\n\\\"\\\\🌐\""),"JSON escaped exact");
    ok(CounterLiveTurnModel.sha256("abc").equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),"SHA256 exact");
    // Dedicated worker competition: every command starts at revision 0.
    // Exactly one command may mutate the actor; all others get stale revisions.
    var parallel=new CounterLiveTurnModel();
    var applied=new AtomicInteger();
    try(var pool=Executors.newFixedThreadPool(8)) {
      List<Future<?>> futures=new ArrayList<>();
      for(int i=0;i<100;i++) {
        int n=i+100;
        futures.add(pool.submit(()->{if(parallel.increment("alice",id(n),0).applied())applied.incrementAndGet();}));
      }
      for(var future:futures)future.get(10,TimeUnit.SECONDS);
    }
    ok(applied.get()==1 && parallel.count()==1 && parallel.revision()==1,"100 racing submits linearized");
    // Capacity bound: commands after first 256 distinct accepted revisions may
    // be forgotten but old event replay must never re-apply at the new revision.
    var bounded=new CounterLiveTurnModel();
    for(int i=0;i<300;i++)ok(bounded.increment("alice",id(i+1000),i).applied(),"unique ordered command " + i);
    ok(bounded.count()==300,"dedup bounded state");
    ok(!bounded.increment("alice",id(1000),0).applied(),"evicted id never re-applies stale base");
    bounded.retire();
    rejects(()->bounded.increment("alice",id(9999),300));
    System.out.println("PASS CounterLiveTurnModel JDK21 assertions="+checks);
  }
}
