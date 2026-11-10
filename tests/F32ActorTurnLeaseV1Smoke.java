package dev.oreslang.runtime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class F32ActorTurnLeaseV1Smoke {
  private static void require(boolean b, String s) {if(!b)throw new AssertionError(s);}
  private static void denies(Runnable r,String label){
    boolean denied=false;
    try{r.run();}catch(IllegalStateException expected){denied=true;}
    require(denied,label);
  }
  public static void main(String[] args) throws Exception {
    ExecutionContext actor=ExecutionContext.actor(1,23,12,44,9,0,0);
    ExecutionContext forged=ExecutionContext.actor(1,23,12,44,9,0,0);
    var host=new F32ActorHostPreflightV1(actor);
    denies(()->host.pinOperation(forged,2,3,3,2,4096),"forged equal numbers");
    denies(()->host.pinOperation(actor,2,3,3,2,15),"quota");
    denies(()->host.pinOperation(actor,2,3,2,2,4096),"inner mismatch");
    var pin=host.pinOperation(actor,2,3,3,2,4096);
    require(host.activePins()==1,"active pin");
    boolean deadlockDenied=false;
    try{host.awaitQuiescent();}catch(IllegalStateException ok){deadlockDenied=true;}
    require(deadlockDenied,"owner deadlock");
    var thiefResult=new AtomicReference<Throwable>();
    var foreign=new Thread(()->{
      try{pin.close();}catch(Throwable t){thiefResult.set(t);}
    });
    foreign.start();foreign.join();
    require(thiefResult.get() instanceof IllegalStateException,"foreign thread must not release pin");
    require(host.activePins()==1,"foreign close retained pin");
    CountDownLatch closed=new CountDownLatch(1), drained=new CountDownLatch(1);
    AtomicBoolean reclaimed=new AtomicBoolean();
    Thread stopper=new Thread(()->{
      host.retireTurn(); closed.countDown();
      try{host.awaitQuiescent(); reclaimed.set(true);}catch(InterruptedException e){Thread.currentThread().interrupt();}
      drained.countDown();
    });
    stopper.start();
    require(closed.await(2,TimeUnit.SECONDS),"stop linearized");
    require(!host.isQuiescent(),"cannot reclaim with live operation");
    require(!drained.await(80,TimeUnit.MILLISECONDS),"STOP raced in-flight host call");
    denies(()->host.pinOperation(actor,2,3,3,2,4096),"no post stop admission");
    pin.close();
    require(drained.await(2,TimeUnit.SECONDS),"quiescence after return");
    require(reclaimed.get()&&host.isQuiescent(),"safe reclaim point");
    denies(pin::close,"double release rejected");
    stopper.join();
    var restarted=new F32ActorHostPreflightV1(ExecutionContext.actor(1,24,12,44,10,0,0));
    denies(()->restarted.pinOperation(actor,2,3,3,2,4096),"old actor context stale after restart");
    restarted.retireTurn();
    restarted.awaitQuiescent();
    System.out.println("ORES_F32_JAVA_PINNED_STOP_RACE_PASS|inflight=RETAINED|post_stop=DENIED|guest=NOT_EXECUTED");
  }
}
