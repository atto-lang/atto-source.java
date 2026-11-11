package dev.oreslang;
import dev.oreslang.runtime.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
final class RuntimeObservationTest {
 @Test void revocationScopeForeignRuntimeAndGenerationFence() throws Exception {
  try (ActorRuntime r = new ActorRuntime(); ActorRuntime other = new ActorRuntime()) {
   var root = r.rootSupervisor(); var child = r.createChildSupervisor(root);
   var expired = r.grantObservation(root,1);
   Thread.sleep(5);
   assertThrows(SecurityException.class, () -> r.observe(expired,100));
   var full = r.grantObservation(root,60000); var narrow = r.grantObservation(child,60000);
   var actor = r.<String>spawnShared(() -> (m,c) -> {});
   actor.send("SECRET");
   var observation = r.observe(full,100);
   assertTrue(observation.actors().stream().anyMatch(a -> a.id().equals(actor.id().toString())));
   assertTrue(r.observe(narrow,100).actors().isEmpty());
   assertThrows(SecurityException.class, () -> other.observe(full,100));
   assertTrue(r.observe(full,1).partial());
   assertFalse(observation.toString().contains("SECRET"));
   full.revoke(); assertThrows(SecurityException.class, () -> r.observe(full,100));
   var old = r.grantObservation(root,60000);
   var start = new CountDownLatch(1);
   var pool = Executors.newSingleThreadExecutor();
   try {
    var race = pool.submit(() -> { start.countDown(); for (int i=0;i<1000;i++) {
      try { r.observe(old,100); } catch (SecurityException expected) { return; }
    }});
    start.await(); r.advanceObservationGeneration(); race.get(5,TimeUnit.SECONDS);
    assertThrows(SecurityException.class, () -> r.observe(old,100));
    assertEquals(2,r.observe(r.grantObservation(root,60000),100).generation());
   } finally { pool.shutdownNow(); }
   actor.stop();
  }
 }
 @Test void actualSupervisorOwnershipIsDistinctFromSpawnLineage() throws Exception {
  try (ActorRuntime r = new ActorRuntime()) {
   var supervisor = r.createChildSupervisor(r.rootSupervisor());
   var group = r.createActorGroup(supervisor);
   
   var childRef = new CompletableFuture<ActorRuntime.ActorRef<String>>();
   var parent = r.<String>spawnShared(() -> (m,c) -> {
    var nested = c.runtime().<String>spawnShared(factory -> (n,ctx) -> {});
    childRef.complete(nested);
   });
   parent.send("spawn"); var nested = childRef.get(5,TimeUnit.SECONDS);
   group.manageWorker(nested);
   var scoped = r.observe(r.grantObservation(supervisor,60000),100);
   assertEquals(1,scoped.actors().size());
   var child = scoped.actors().getFirst();
   assertEquals(supervisor.id().toString(),child.supervisorId());
   assertNull(child.spawnedBy(),"out-of-scope parent's ID must be redacted");
   assertTrue(scoped.partial(),"redacted ancestry must be honest");
   var all = r.observe(r.grantObservation(r.rootSupervisor(),60000),100);
   var actual = all.actors().stream().filter(a -> a.id().equals(nested.id().toString())).findFirst().orElseThrow();
   assertEquals(parent.id().toString(),actual.spawnedBy());
   assertNotEquals(actual.spawnedBy(),actual.supervisorId());
   group.close(); parent.stop();
  }
 }
 @Test void guestActorCannotMintOrUseObservation() throws Exception {
  try (ActorRuntime r = new ActorRuntime()) {
   var root = r.rootSupervisor(); var grant = r.grantObservation(root,60000);
   var done = new CompletableFuture<Void>();
   var actor = r.<String>spawnShared(() -> (m,c) -> {
    try {
     assertThrows(SecurityException.class, () -> r.grantObservation(root,1000));
     assertThrows(SecurityException.class, () -> r.observe(grant,100));
     done.complete(null);
    } catch (Throwable failure) { done.completeExceptionally(failure); }
   });
   actor.send("go"); done.get(5,TimeUnit.SECONDS); actor.stop();
  }
 }
}
