package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SingletonDependencyGraphSmoke {
  static int checks;
  static void ok(boolean v, String message) { checks++; if (!v) throw new AssertionError(message); }
  static void fail(Runnable f, String s) {
    try {f.run();} catch (IllegalStateException ex) { ok(ex.getMessage().contains(s),ex.getMessage());return;}
    throw new AssertionError("not rejected: " + s);
  }
  public static void main(String[] args) throws Exception {
    var g = new SingletonDependencyGraph<String>();
    g.begin("A");g.dependOn("B");g.dependOn("B");g.begin("B");
    fail(()->g.dependOn("A"),"cycle");g.finish("B");g.finish("A");
    g.begin("A");g.dependOn("B");g.begin("B");g.dependOn("C");g.begin("C");
    fail(()->g.dependOn("A"),"cycle");g.finish("C");g.finish("B");g.finish("A");
    var ready = new CountDownLatch(2);var start = new CountDownLatch(1); var attempted = new CountDownLatch(2);
    var cycles = new AtomicInteger();
    var p = Executors.newFixedThreadPool(2);
    try {
      var one=p.submit(()->{g.begin("X");ready.countDown();start.await();try{g.dependOn("Y");}catch(IllegalStateException e){cycles.incrementAndGet();}attempted.countDown();if(!attempted.await(5, TimeUnit.SECONDS))throw new AssertionError("attempt gate timeout");g.finish("X");return 1;});
      var two=p.submit(()->{g.begin("Y");ready.countDown();start.await();try{g.dependOn("X");}catch(IllegalStateException e){cycles.incrementAndGet();}attempted.countDown();if(!attempted.await(5, TimeUnit.SECONDS))throw new AssertionError("attempt gate timeout");g.finish("Y");return 1;});
      ok(ready.await(4,TimeUnit.SECONDS),"both threads started");start.countDown();
      ok(one.get(4,TimeUnit.SECONDS)==1,"first ended");ok(two.get(4,TimeUnit.SECONDS)==1,"second ended");
      ok(cycles.get()==1,"cross-thread cycle caught");
    } finally {p.shutdownNow();}
    g.begin("z");for(int i=0;i<64;i++)g.dependOn("dep"+i);
    fail(()->g.dependOn("extra"),"quota");g.finish("z");
    g.begin("z");g.dependOn("extra");g.finish("z");ok(true,"cleanup");
    // The 65th nested initializer fails before pushing or leaking a key.
    for(int i=0;i<64;i++)g.begin("nested"+i);
    fail(()->g.begin("overflow"),"depth exceeded");
    for(int i=63;i>=0;i--)g.finish("nested"+i);
    g.begin("after-limit");g.finish("after-limit");ok(true,"depth cleanup");
    System.out.println("PASS checks="+checks);
  }
}
