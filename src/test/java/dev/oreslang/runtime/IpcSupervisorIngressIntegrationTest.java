package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
final class IpcSupervisorIngressIntegrationTest {
    @Test void authenticatedDecodeRacingAncestorFailureCannotPublish() throws Exception {
        byte[] key = new byte[32], connection = new byte[16], digest = new byte[32];
        Arrays.fill(key,(byte)7); Arrays.fill(connection,(byte)8); Arrays.fill(digest,(byte)9);
        var binding = new IpcSchemaBoundDataV1.Binding(
                IpcSchemaBoundDataV1.Codec.CBOR,1,"bool.v1",digest);
        try (ActorRuntime runtime = new ActorRuntime();
             ExecutorService control = Executors.newSingleThreadExecutor();
             ExecutorService worker = Executors.newSingleThreadExecutor()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            var recovery = new SupervisorEscrowSubtreeRecoveryV1(runtime,bridge,control,ignored->false);
            ActorRuntime.ActorRef<String> supervisor = runtime.spawnPrivate(
                    ignored -> (m,turn)->{throw new IllegalStateException("crash");});
            ActorRuntime.ActorRef<Boolean> child = runtime.spawnPrivate(ignored -> (m,turn)->{});
            recovery.attach(supervisor,null);
            long node = recovery.attach(child,supervisor);
            supervisor.getReady().get(5,TimeUnit.SECONDS);
            child.getReady().get(5,TimeUnit.SECONDS);
            byte[] body = IpcSchemaBoundDataV1.wrap(binding,new byte[]{(byte)0xf5});
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            var first = new IpcReceiptCorrelation.Envelope("tenant","sender",
                    child.id().value().toString(),"session",hash,1,child.generation(),3,4,1,1);
            var second = new IpcReceiptCorrelation.Envelope("tenant","sender",
                    child.id().value().toString(),"session",hash,1,child.generation(),3,4,2,2);
            CountDownLatch decoded = new CountDownLatch(1), resume = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            var ingress = new IpcPinnedActorIngressV1<>(child,first,5,binding,bytes->{
                if (!Arrays.equals(bytes,new byte[]{(byte)0xf5})) throw new IOException("not bool true");
                if (calls.incrementAndGet()==2) {
                    decoded.countDown();
                    try {
                        if (!resume.await(10,TimeUnit.SECONDS)) throw new IOException("fixture deadline");
                    } catch (InterruptedException e) {Thread.currentThread().interrupt();throw new IOException(e);}
                }
                return Boolean.TRUE;
            });
            ingress.admit(IpcAuthenticatedSocketTransport.encodeDataFrame(first,body,5,connection,key),connection,key);
            assertEquals(1,bridge.dataAdmissionStatus(node).committed());
            var late = worker.submit(()->{
                ingress.admit(IpcAuthenticatedSocketTransport.encodeDataFrame(second,body,5,connection,key),connection,key);
                return null;
            });
            try {
                assertTrue(decoded.await(5,TimeUnit.SECONDS));
                supervisor.send("crash");
                assertThrows(ExecutionException.class,()->supervisor.done().get(5,TimeUnit.SECONDS));
                assertTrue(child.awaitTermination(5,TimeUnit.SECONDS));
            } finally {resume.countDown();}
            var refused = assertThrows(ExecutionException.class,()->late.get(5,TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.SendError.class,refused.getCause());
            assertEquals(1,bridge.dataAdmissionStatus(node).committed());
            assertEquals(0,bridge.dataAdmissionStatus(node).inFlight());
        }
    }
}
