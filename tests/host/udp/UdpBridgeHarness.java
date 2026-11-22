package dev.oreslang.runtime;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.nio.channels.*;
import java.io.*;

// Minimal, standalone JDK21 HOST API shims. They are not the repo's real
// OresVM/OresFuture/NioReadinessGroup. The tested AsyncNioUdp and kernel files
// are byte-for-byte Git-blob verified.
final class IsolatePolicy { enum Capability { NETWORK } }
final class RuntimePermissions { enum Permission { NET } }
final class OresContext {
  final OresVM vm = new OresVM();
  final NativeIo io = new NativeIo();
  final AtomicBoolean granted = new AtomicBoolean(true);
  volatile String deniedPeer = "";
  final AtomicInteger recvAttempts = new AtomicInteger();
  volatile int blockReceiveAttempt = -1;
  final CountDownLatch physicalReceiveEntered = new CountDownLatch(1);
  final CountDownLatch physicalReceiveMayExit = new CountDownLatch(1);
  void requireCapability(IsolatePolicy.Capability c,String op) {
    if(!granted.get())throw new SecurityException("NETWORK revoked: "+op);
  }
  void requirePermission(RuntimePermissions.Permission p,String endpoint,String op) {
    requireCapability(IsolatePolicy.Capability.NETWORK,op);
    if(op.equals("udp.send_to") && endpoint.equals(deniedPeer))
      throw new SecurityException("destination denied");
    if(op.equals("udp.recv_from") && recvAttempts.incrementAndGet()==blockReceiveAttempt){
      physicalReceiveEntered.countDown();
      try{
        if(!physicalReceiveMayExit.await(5,TimeUnit.SECONDS))
          throw new IllegalStateException("physical authorization gate not released");
      }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
  }
  OresVM vm(){return vm;}
  NativeIo nativeIo(){return io;}
}
final class NativeIo {
  final Set<AutoCloseable> active=ConcurrentHashMap.newKeySet();
  void register(AutoCloseable resource){active.add(resource);}
  void unregister(AutoCloseable resource){active.remove(resource);}
}
final class OresVM {final NioReadinessGroup reactor=new NioReadinessGroup();NioReadinessGroup nioReadiness(){return reactor;}}
final class NioReadinessGroup {
  final Set<SelectableChannel> busy=ConcurrentHashMap.newKeySet();
  OresFuture<Integer> watch(SelectableChannel channel,int interest) {
    if(!busy.add(channel))return OresFuture.failed(new IllegalStateException("duplicate watch"));
    AtomicReference<Selector> ref=new AtomicReference<>();
    OresFuture<Integer> next=OresFuture.withCancel(()->{Selector s=ref.get(); if(s!=null)s.wakeup();});
    Thread.ofPlatform().daemon(true).name("udp-harness-selector").start(()->{
      try(Selector selector=Selector.open()) {
        ref.set(selector);
        channel.register(selector,interest);
        while(!next.isDone()) {
          if(selector.select(100)>0) {
            if(next.isDone())break;
            next.complete(interest);
            break;
          }
          if(!channel.isOpen())throw new IOException("socket closed");
        }
      }catch(Throwable t){next.completeExceptionally(t);}
      finally{busy.remove(channel);}
    });
    return next;
  }
}
final class OresFuture<T> extends CompletableFuture<T> {
  private Runnable onCancel=()->{};
  interface Callback<T> {void resolve(T value);void reject(Throwable failure);}
  static <T> OresFuture<T> completed(T value){OresFuture<T> x=new OresFuture<>();x.complete(value);return x;}
  static <T> OresFuture<T> failed(Throwable x){OresFuture<T> f=new OresFuture<>();f.completeExceptionally(x);return f;}
  static <T> OresFuture<T> withCancel(Runnable hook){OresFuture<T> f=new OresFuture<>();f.onCancel=hook;return f;}
  static <T> OresFuture<T> fromCallback(Consumer<? super Callback<T>> registrar,Runnable cancelHook) {
    OresFuture<T> f=withCancel(cancelHook);
    try{registrar.accept(new Callback<>() {
      public void resolve(T v){f.complete(v);}
      public void reject(Throwable t){f.completeExceptionally(t);}
    });}catch(Throwable t){f.completeExceptionally(t);}return f;
  }
  void whenCompleteRuntime(BiConsumer<? super T,? super Throwable> callback){whenComplete(callback);}
  static Throwable unwrap(Throwable e){return e instanceof CompletionException && e.getCause()!=null?e.getCause():e;}
  @Override public boolean cancel(boolean interrupt){boolean changed=super.cancel(interrupt);if(changed)onCancel.run();return changed;}
}

public final class UdpBridgeHarness {
  static void assertTrue(boolean yes,String reason){if(!yes)throw new AssertionError(reason);}
  static Throwable failure(CompletableFuture<?> f){
    try{f.join();throw new AssertionError("expected failure");}
    catch(CompletionException | CancellationException ex){return ex.getCause()==null?ex:ex.getCause();}
  }
  static final class PoisonBytes extends AbstractList<Integer> {
    int reads;
    public int size(){reads++;throw new AssertionError("guest list read without permit");}
    public Integer get(int i){reads++;throw new AssertionError("guest value read without permit");}
  }
  public static void main(String[] args)throws Exception {
    OresContext context=new OresContext();
    var bound=AsyncNioUdp.bindAsync(context,"127.0.0.1",0).join();
    int port=bound.localPort();
    assertTrue(bound.sendTo(port,List.of(42,0,255)).get(2,TimeUnit.SECONDS)==3,"sent length");
    var packet=bound.receiveFrom(16).get(2,TimeUnit.SECONDS);
    assertTrue(packet.bytes().equals(List.of(42L,0L,255L)),"owned datagram contents");
    assertTrue(packet.sourcePort()==port,"source port identity");
    assertTrue(context.io.active.size()==1,"registered resource");
    PoisonBytes poison=new PoisonBytes();context.deniedPeer="127.0.0.1:30001";
    assertTrue(failure(bound.sendTo(30001,poison)) instanceof SecurityException,"deny destination");
    assertTrue(poison.reads==0,"no guest read before destination grant");
    assertTrue(failure(bound.sendTo(port,List.of(256))) instanceof IllegalArgumentException,"reject byte>255");
    assertTrue(failure(bound.sendTo(port,List.of(1.5))) instanceof IllegalArgumentException,"reject fractional byte");
    var pending=bound.receiveFrom(8);
    assertTrue(!pending.isDone(),"pending NIO receive");
    assertTrue(failure(bound.sendTo(port,List.of(8))) instanceof IllegalStateException,"single physical op owner");
    assertTrue(pending.cancel(false),"cancel pending");
    assertTrue(context.io.active.isEmpty(),"cancellation unregisters socket");
    try{bound.localPort();throw new AssertionError("closed socket escaped after cancellation");}
    catch(IllegalStateException expected){}
    var replacement=AsyncNioUdp.bindAsync(context,"127.0.0.1",0).join();
    int nport=replacement.localPort();
    replacement.sendTo(nport,List.of(7)).get(2,TimeUnit.SECONDS);
    assertTrue(replacement.receiveFrom(8).get(2,TimeUnit.SECONDS).bytes().equals(List.of(7L)),"rebind after cancel");
    replacement.closeAsync().get(2,TimeUnit.SECONDS);
    assertTrue(context.io.active.isEmpty(),"resource retirement");
    // Force a real selector completion to enter a checked physical receive,
    // cancel from another thread while the gate is blocked, and verify the
    // socket lease is not released until the physical step actually retires.
    OresContext raceContext=new OresContext();
    raceContext.blockReceiveAttempt=2;
    var raceReceiver=AsyncNioUdp.bindAsync(raceContext,"127.0.0.1",0).join();
    var racingRead=raceReceiver.receiveFrom(8);
    try(var raceSender=UdpDatagramKernel.bindLoopback(0,(a,op)->{})){
      raceSender.trySendTo(raceReceiver.localPort(),new byte[]{99});
      assertTrue(raceContext.physicalReceiveEntered.await(3,TimeUnit.SECONDS),"reactor entered physical authorization");
      assertTrue(racingRead.cancel(false),"racing receive cancellation accepted");
      var field=AsyncNioUdp.Socket.class.getDeclaredField("operating");
      field.setAccessible(true);
      AtomicBoolean lease=(AtomicBoolean)field.get(raceReceiver);
      assertTrue(lease.get(),"physical socket lease held despite visible future cancellation");
      assertTrue(raceContext.io.active.contains(raceReceiver),"resource retained during physical callback");
      raceContext.physicalReceiveMayExit.countDown();
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
      while(lease.get() && System.nanoTime()<deadline)Thread.sleep(1);
      assertTrue(!lease.get(),"lease released only after physical callback finished");
      assertTrue(raceContext.io.active.isEmpty(),"resource unregistered after physical callback retired");
      assertTrue(racingRead.isCancelled(),"late packet cannot settle canceled future");
    }finally{
      raceContext.physicalReceiveMayExit.countDown();
      raceReceiver.close();
    }
    for(int i=0;i<40;i++) {
      var s=AsyncNioUdp.bindAsync(context,"127.0.0.1",0).join();
      int dest=s.localPort();
      assertTrue(s.sendTo(dest,List.of(i)).get(2,TimeUnit.SECONDS)==1,"stress send "+i);
      assertTrue(s.receiveFrom(8).get(2,TimeUnit.SECONDS).bytes().equals(List.of((long)i)),"stress recv "+i);
      s.closeAsync().get(2,TimeUnit.SECONDS);
    }
    assertTrue(context.io.active.isEmpty(),"all sockets retired");
    System.out.println("JDK21: exact-blob AsyncNioUdp + UDP kernel physical loopback with shim VM: PASS (not GraalVM/Atto guest)");
  }
}
