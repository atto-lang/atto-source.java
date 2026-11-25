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
    private static final long BOOL_CREDIT=64;
    private static IpcPhysicalConnectionBroker.Authority authority(long generation,byte[] connection,byte[] key){
        return new IpcPhysicalConnectionBroker(target->System.arraycopy(connection,0,target,0,target.length)).mint(generation,key);
    }
    @FunctionalInterface private interface BoolDecode { Boolean run(byte[] bytes) throws IOException; }
    private static IpcPinnedActorIngressV1.Decoder<Boolean> boolDecoder(BoolDecode action) {
        return new IpcPinnedActorIngressV1.Decoder<>() {
            public long preflight(java.nio.ByteBuffer body) throws IOException {
                if(body.remaining()!=1 || body.get()!=(byte)0xf5)throw new IOException("not bool true");
                return BOOL_CREDIT;
            }
            public Boolean decode(java.nio.ByteBuffer body,long admitted) throws IOException {
                assertEquals(BOOL_CREDIT,admitted);
                assertTrue(body.isReadOnly());
                assertEquals(1,body.remaining());
                return action.run(new byte[]{body.get()});
            }
        };
    }
    @Test void authenticatedDecodeRacingAncestorFailureCannotPublish() throws Exception {
        byte[] key = new byte[32], connection = new byte[16], digest = new byte[32];
        Arrays.fill(key,(byte)7); Arrays.fill(connection,(byte)8); Arrays.fill(digest,(byte)9);
        var binding = new IpcSchemaBoundDataV1.Binding(
                IpcSchemaBoundDataV1.Codec.CBOR,1,"bool.v1",digest);
        try (ActorRuntime runtime = new ActorRuntime();
             ExecutorService control = Executors.newSingleThreadExecutor();
             ExecutorService worker = Executors.newSingleThreadExecutor()) {
            var budget=runtime.registerIpcIngressTenant("tenant",16L*1024*1024,8);
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            var recovery = new SupervisorEscrowSubtreeRecoveryV1(runtime,bridge,control,ignored->false);
            ActorRuntime.ActorRef<String> supervisor = runtime.spawnPrivate(
                    ignored -> (m,turn)->{throw new IllegalStateException("crash");});
            ActorRuntime.ActorRef<Boolean> child = runtime.spawnPrivate(ignored -> (m,turn)->{});
            recovery.attach(supervisor,null);
            long node = recovery.attach(child,supervisor);
            runtime.authorizeIpcIngressActor(child,"tenant",budget);
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
            var ingress = new IpcPinnedActorIngressV1<>(child,first,authority(5,connection,key),binding,budget,BOOL_CREDIT,boolDecoder(bytes->{
                if (!Arrays.equals(bytes,new byte[]{(byte)0xf5})) throw new IOException("not bool true");
                if (calls.incrementAndGet()==2) {
                    decoded.countDown();
                    try {
                        if (!resume.await(10,TimeUnit.SECONDS)) throw new IOException("fixture deadline");
                    } catch (InterruptedException e) {Thread.currentThread().interrupt();throw new IOException(e);}
                }
                return Boolean.TRUE;
            }));
            ingress.admit(IpcAuthenticatedSocketTransport.encodeDataFrame(first,body,5,connection,key));
            assertEquals(1,bridge.dataAdmissionStatus(node).committed());
            var late = worker.submit(()->{
                ingress.admit(IpcAuthenticatedSocketTransport.encodeDataFrame(second,body,5,connection,key));
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
    @Test void authenticatedOldFrameCannotReachReplacementAfterRestart() throws Exception {
        byte[] key=new byte[32], connection=new byte[16], digest=new byte[32];
        Arrays.fill(key,(byte)7); Arrays.fill(connection,(byte)8); Arrays.fill(digest,(byte)9);
        var binding=new IpcSchemaBoundDataV1.Binding(IpcSchemaBoundDataV1.Codec.CBOR,1,"bool.v1",digest);
        try (ActorRuntime runtime=new ActorRuntime();
             ExecutorService control=Executors.newSingleThreadExecutor();
             ExecutorService worker=Executors.newSingleThreadExecutor()) {
            var budget=runtime.registerIpcIngressTenant("tenant",16L*1024*1024,8);
            var bridge=new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            // Test-only release oracle: actual Java restart, not native/FFI reclamation proof.
            var recovery=new SupervisorEscrowSubtreeRecoveryV1(runtime,bridge,control,ignored->true);
            ActorRuntime.ActorRef<String> oldParent=runtime.spawnPrivate(
                    ignored->(m,turn)->{throw new IllegalStateException("crash");});
            ActorRuntime.ActorRef<Boolean> oldChild=runtime.spawnPrivate(ignored->(m,turn)->{});
            long parentNode=recovery.attach(oldParent,null);
            long childNode=recovery.attach(oldChild,oldParent);
            runtime.authorizeIpcIngressActor(oldChild,"tenant",budget);
            oldParent.getReady().get(5,TimeUnit.SECONDS);
            oldChild.getReady().get(5,TimeUnit.SECONDS);
            byte[] body=IpcSchemaBoundDataV1.wrap(binding,new byte[]{(byte)0xf5});
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            var oldEpoch=new IpcReceiptCorrelation.Envelope("tenant","sender",
                    oldChild.id().value().toString(),"old-session",hash,1,oldChild.generation(),3,4,1,1);
            byte[] oldFrame=IpcAuthenticatedSocketTransport.encodeDataFrame(oldEpoch,body,5,connection,key);
            CountDownLatch decoding=new CountDownLatch(1), resume=new CountDownLatch(1);
            var oldIngress=new IpcPinnedActorIngressV1<>(oldChild,oldEpoch,authority(5,connection,key),binding,budget,BOOL_CREDIT,boolDecoder(bytes->{
                decoding.countDown();
                try {if(!resume.await(10,TimeUnit.SECONDS))throw new IOException("fixture deadline");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
                return Boolean.TRUE;
            }));
            var late=worker.submit(()->{oldIngress.admit(oldFrame);return null;});
            try {
                assertTrue(decoding.await(5,TimeUnit.SECONDS));
                oldParent.send("crash");
                assertThrows(ExecutionException.class,()->oldParent.done().get(5,TimeUnit.SECONDS));
                assertTrue(oldChild.awaitTermination(5,TimeUnit.SECONDS));
                assertTrue(bridge.commitAndAcknowledge(bridge.deliver(parentNode),()->{}));
                ActorRuntime.ActorRef<String> newParent=runtime.spawnPrivate(ignored->(m,turn)->{});
                recovery.attachReplacement(recovery.reserveRestart(parentNode),newParent,null);
                ActorRuntime.ActorRef<Boolean> newChild=runtime.spawnPrivate(ignored->(m,turn)->{});
                long newNode=recovery.attachReplacement(recovery.reserveRestart(childNode),newChild,newParent);
                runtime.authorizeIpcIngressActor(newChild,"tenant",budget);
                assertTrue(newChild.generation()>oldChild.generation());
                newChild.getReady().get(5,TimeUnit.SECONDS);
                var newEpoch=new IpcReceiptCorrelation.Envelope("tenant","sender",
                        newChild.id().value().toString(),"new-session",hash,1,newChild.generation(),3,4,1,1);
                var newIngress=new IpcPinnedActorIngressV1<>(newChild,newEpoch,authority(6,connection,key),binding,budget,BOOL_CREDIT,boolDecoder(bytes->{
                    if(!Arrays.equals(bytes,new byte[]{(byte)0xf5}))throw new IOException("invalid bool");
                    return Boolean.TRUE;
                }));
                assertThrows(IOException.class,()->newIngress.admit(oldFrame));
                assertThrows(IllegalArgumentException.class,()->new IpcPinnedActorIngressV1<>(
                        newChild,oldEpoch,authority(5,connection,key),binding,budget,BOOL_CREDIT,boolDecoder(bytes->Boolean.TRUE)));
                resume.countDown();
                var rejected=assertThrows(ExecutionException.class,()->late.get(5,TimeUnit.SECONDS));
                assertInstanceOf(ActorRuntime.SendError.class,rejected.getCause());
                assertEquals(0,bridge.dataAdmissionStatus(childNode).committed());
                assertEquals(0,bridge.dataAdmissionStatus(newNode).committed());
                newIngress.admit(IpcAuthenticatedSocketTransport.encodeDataFrame(newEpoch,body,6,connection,key));
                assertEquals(1,bridge.dataAdmissionStatus(newNode).committed());
                assertEquals(0,bridge.dataAdmissionStatus(newNode).inFlight());
                assertFalse(oldChild.isAlive());
                assertTrue(newChild.isAlive());
            } finally {resume.countDown();}
        }
    }

    @Test void tenantStagingRefusalPrecedesBodyReadAndReleasesOnEveryFailure() throws Exception {
        byte[] key=new byte[32], connection=new byte[16], digest=new byte[32];
        Arrays.fill(key,(byte)7); Arrays.fill(connection,(byte)8); Arrays.fill(digest,(byte)9);
        var binding=new IpcSchemaBoundDataV1.Binding(IpcSchemaBoundDataV1.Codec.CBOR,1,"bool.v1",digest);
        try(ActorRuntime runtime=new ActorRuntime()) {
            ActorRuntime.ActorRef<Boolean> child=runtime.spawnPrivate(ignored->(m,turn)->{});
            byte[] body=IpcSchemaBoundDataV1.wrap(binding,new byte[]{(byte)0xf5});
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            var epoch=new IpcReceiptCorrelation.Envelope("tenant","sender",child.id().value().toString(),
                    "budget-session",hash,1,child.generation(),3,4,1,1);
            byte[] frame=IpcAuthenticatedSocketTransport.encodeDataFrame(epoch,body,5,connection,key);
            long charge=IpcIngressBudgetV1.chargeFor(frame.length);
            var budget=runtime.registerIpcIngressTenant("tenant",charge+BOOL_CREDIT,1);
            runtime.authorizeIpcIngressActor(child,"tenant",budget);
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            var ingress=new IpcPinnedActorIngressV1<>(child,epoch,authority(5,connection,key),binding,budget,BOOL_CREDIT,boolDecoder(bytes->{
                calls.incrementAndGet();return Boolean.TRUE;
            }));
            var raw=new java.io.ByteArrayOutputStream();
            IpcAuthenticatedSocketTransport.writeWireFrame(raw,frame);
            var stream=new java.io.ByteArrayInputStream(raw.toByteArray());
            var outstanding=budget.reserve(frame.length);
            try {
                assertThrows(IllegalStateException.class,()->ingress.readAndAdmit(stream));
                assertEquals(frame.length,stream.available(),"only prefix may be consumed before credit");
                assertEquals(0,calls.get());
                assertEquals(new IpcIngressBudgetV1.Usage(charge,1),budget.usage());
            } finally {outstanding.close();}
            assertEquals(new IpcIngressBudgetV1.Usage(0,0),budget.usage());
            byte[] truncated=Arrays.copyOf(raw.toByteArray(),raw.size()-1);
            assertThrows(java.io.EOFException.class,()->ingress.readAndAdmit(
                    new java.io.ByteArrayInputStream(truncated)));
            assertEquals(new IpcIngressBudgetV1.Usage(0,0),budget.usage());
            byte[] tampered=frame.clone();tampered[tampered.length-1]^=1;
            assertThrows(IOException.class,()->ingress.admit(tampered));
            assertEquals(new IpcIngressBudgetV1.Usage(0,0),budget.usage());
            assertEquals(0,calls.get());
            ingress.readAndAdmit(new java.io.ByteArrayInputStream(raw.toByteArray()));
            assertEquals(1,calls.get());
            assertEquals(new IpcIngressBudgetV1.Usage(0,0),budget.usage());
            assertThrows(IOException.class,()->ingress.admit(frame));
            assertEquals(new IpcIngressBudgetV1.Usage(0,0),budget.usage());
        }
    }

    @Test void decodedCreditDeniedBeforeMaterializationAndHeldUntilPublication() throws Exception {
        byte[] key=new byte[32], connection=new byte[16], digest=new byte[32];
        Arrays.fill(key,(byte)7); Arrays.fill(connection,(byte)8); Arrays.fill(digest,(byte)9);
        var binding=new IpcSchemaBoundDataV1.Binding(IpcSchemaBoundDataV1.Codec.CBOR,1,"bool.v1",digest);
        try(ActorRuntime runtime=new ActorRuntime()) {
            ActorRuntime.ActorRef<Boolean> child=runtime.spawnPrivate(ignored->(m,turn)->{});
            byte[] body=IpcSchemaBoundDataV1.wrap(binding,new byte[]{(byte)0xf5});
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            var epoch=new IpcReceiptCorrelation.Envelope("tenant","sender",child.id().value().toString(),
                    "decoded-credit",hash,1,child.generation(),3,4,1,1);
            byte[] frame=IpcAuthenticatedSocketTransport.encodeDataFrame(epoch,body,5,connection,key);
            long charge=IpcIngressBudgetV1.chargeFor(frame.length);
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            var shortBudget=runtime.registerIpcIngressTenant("tenan2",charge+BOOL_CREDIT-1,1);
            ActorRuntime.ActorRef<Boolean> shortChild=runtime.spawnPrivate(ignored->(m,turn)->{});
            runtime.authorizeIpcIngressActor(shortChild,"tenan2",shortBudget);
            var shortEpoch=new IpcReceiptCorrelation.Envelope("tenan2","sender",shortChild.id().value().toString(),
                    "decoded-credit",hash,1,shortChild.generation(),3,4,1,1);
            byte[] shortFrame=IpcAuthenticatedSocketTransport.encodeDataFrame(shortEpoch,body,5,connection,key);
            assertEquals(frame.length,shortFrame.length);
            var refused=new IpcPinnedActorIngressV1<>(shortChild,shortEpoch,authority(5,connection,key),binding,shortBudget,BOOL_CREDIT,
                    boolDecoder(bytes->{calls.incrementAndGet();return Boolean.TRUE;}));
            assertThrows(IllegalStateException.class,()->refused.admit(shortFrame));
            assertEquals(0,calls.get());
            assertEquals(new IpcIngressBudgetV1.Usage(0,0),shortBudget.usage());
            var exact=runtime.registerIpcIngressTenant("tenant",charge+BOOL_CREDIT,1);
            runtime.authorizeIpcIngressActor(child,"tenant",exact);
            var ingress=new IpcPinnedActorIngressV1<>(child,epoch,authority(5,connection,key),binding,exact,BOOL_CREDIT,
                    boolDecoder(bytes->{
                        assertEquals(new IpcIngressBudgetV1.Usage(charge+BOOL_CREDIT,1),exact.usage());
                        calls.incrementAndGet();return Boolean.TRUE;
                    }));
            ingress.admit(frame);
            assertEquals(1,calls.get());
            assertEquals(new IpcIngressBudgetV1.Usage(0,0),exact.usage());
            var limited=new IpcPinnedActorIngressV1<>(child,epoch,authority(5,connection,key),binding,exact,BOOL_CREDIT-1,
                    boolDecoder(bytes->{calls.incrementAndGet();return Boolean.TRUE;}));
            assertThrows(IOException.class,()->limited.admit(frame));
            assertEquals(1,calls.get());
            assertEquals(new IpcIngressBudgetV1.Usage(0,0),exact.usage());
            var failing=new IpcPinnedActorIngressV1<>(child,epoch,authority(5,connection,key),binding,exact,BOOL_CREDIT,
                    boolDecoder(bytes->{throw new IOException("decoder failure");}));
            assertThrows(IOException.class,()->failing.admit(frame));
            assertEquals(new IpcIngressBudgetV1.Usage(0,0),exact.usage());
        }
    }

    @Test void runtimeRegistryRejectsDuplicateForeignAndRetiredTenantBudgets() throws Exception {
        byte[] key=new byte[32], connection=new byte[16], digest=new byte[32];
        Arrays.fill(key,(byte)7); Arrays.fill(connection,(byte)8); Arrays.fill(digest,(byte)9);
        var binding=new IpcSchemaBoundDataV1.Binding(IpcSchemaBoundDataV1.Codec.CBOR,1,"bool.v1",digest);
        try(ActorRuntime runtime=new ActorRuntime();ActorRuntime foreign=new ActorRuntime()) {
            ActorRuntime.ActorRef<Boolean> child=runtime.spawnPrivate(ignored->(m,turn)->{});
            byte[] body=IpcSchemaBoundDataV1.wrap(binding,new byte[]{(byte)0xf5});
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            var epoch=new IpcReceiptCorrelation.Envelope("tenant","sender",child.id().value().toString(),
                    "registry",hash,1,child.generation(),3,4,1,1);
            byte[] frame=IpcAuthenticatedSocketTransport.encodeDataFrame(epoch,body,5,connection,key);
            long charge=IpcIngressBudgetV1.chargeFor(frame.length);
            var budget=runtime.registerIpcIngressTenant("tenant",charge+BOOL_CREDIT,1);
            var wrong=foreign.registerIpcIngressTenant("tenant",charge+BOOL_CREDIT,1);
            runtime.authorizeIpcIngressActor(child,"tenant",budget);
            assertThrows(IllegalStateException.class,()->runtime.registerIpcIngressTenant("tenant",charge*2,2));
            assertThrows(IllegalArgumentException.class,()->new IpcPinnedActorIngressV1<>(
                    child,epoch,authority(5,connection,key),binding,wrong,BOOL_CREDIT,boolDecoder(bytes->Boolean.TRUE)));
            var ingress=new IpcPinnedActorIngressV1<>(child,epoch,authority(5,connection,key),binding,budget,BOOL_CREDIT,
                    boolDecoder(bytes->Boolean.TRUE));
            var held=budget.reserve(frame.length);
            try {
                assertThrows(IllegalStateException.class,()->ingress.admit(frame));
                runtime.retireIpcIngressTenant("tenant");
                assertThrows(IllegalStateException.class,()->held.reserveMaterialization(BOOL_CREDIT));
                assertEquals(new IpcIngressBudgetV1.Usage(charge,1),budget.usage());
            } finally {held.close();}
            assertEquals(new IpcIngressBudgetV1.Usage(0,0),budget.usage());
            assertThrows(IOException.class,()->ingress.admit(frame));
            assertThrows(IllegalStateException.class,()->runtime.registerIpcIngressTenant("tenant",charge,1));
            assertThrows(IllegalArgumentException.class,()->new IpcPinnedActorIngressV1<>(
                    child,epoch,authority(5,connection,key),binding,budget,BOOL_CREDIT,boolDecoder(bytes->Boolean.TRUE)));
        }
    }

    @Test void tenantRetirementFencesDecodedPublicationAndCrossDomainAuthority() throws Exception {
        byte[] key=new byte[32], connection=new byte[16], digest=new byte[32];
        Arrays.fill(key,(byte)7); Arrays.fill(connection,(byte)8); Arrays.fill(digest,(byte)9);
        var binding=new IpcSchemaBoundDataV1.Binding(IpcSchemaBoundDataV1.Codec.CBOR,1,"bool.v1",digest);
        try(ActorRuntime runtime=new ActorRuntime();
            ExecutorService control=Executors.newSingleThreadExecutor();
            ExecutorService worker=Executors.newSingleThreadExecutor()) {
            var bridge=new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            var recovery=new SupervisorEscrowSubtreeRecoveryV1(runtime,bridge,control,ignored->false);
            ActorRuntime.ActorRef<Boolean> child=runtime.spawnPrivate(ignored->(m,turn)->{});
            long node=recovery.attach(child,null);
            var budget=runtime.registerIpcIngressTenant("tenant",1024*1024,2);
            var other=runtime.registerIpcIngressTenant("other",1024*1024,2);
            byte[] body=IpcSchemaBoundDataV1.wrap(binding,new byte[]{(byte)0xf5});
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            var epoch=new IpcReceiptCorrelation.Envelope("tenant","sender",child.id().value().toString(),
                    "retire",hash,1,child.generation(),3,4,1,1);
            var wrongEpoch=new IpcReceiptCorrelation.Envelope("other","sender",child.id().value().toString(),
                    "retire",hash,1,child.generation(),3,4,1,1);
            assertThrows(IllegalArgumentException.class,()->new IpcPinnedActorIngressV1<>(
                    child,epoch,authority(5,connection,key),binding,budget,BOOL_CREDIT,boolDecoder(bytes->Boolean.TRUE)));
            runtime.authorizeIpcIngressActor(child,"tenant",budget);
            assertThrows(IllegalStateException.class,()->runtime.authorizeIpcIngressActor(child,"other",other));
            assertThrows(IllegalArgumentException.class,()->new IpcPinnedActorIngressV1<>(
                    child,wrongEpoch,authority(5,connection,key),binding,other,BOOL_CREDIT,boolDecoder(bytes->Boolean.TRUE)));
            CountDownLatch decoding=new CountDownLatch(1),resume=new CountDownLatch(1);
            var ingress=new IpcPinnedActorIngressV1<>(child,epoch,authority(5,connection,key),binding,budget,BOOL_CREDIT,
                    boolDecoder(bytes->{
                        decoding.countDown();
                        try{if(!resume.await(10,TimeUnit.SECONDS))throw new IOException("fixture deadline");}
                        catch(InterruptedException x){Thread.currentThread().interrupt();throw new IOException(x);}
                        return Boolean.TRUE;
                    }));
            byte[] frame=IpcAuthenticatedSocketTransport.encodeDataFrame(epoch,body,5,connection,key);
            var late=worker.submit(()->{ingress.admit(frame);return null;});
            try{
                assertTrue(decoding.await(5,TimeUnit.SECONDS));
                assertEquals(new IpcIngressBudgetV1.Usage(IpcIngressBudgetV1.chargeFor(frame.length)+BOOL_CREDIT,1),budget.usage());
                runtime.retireIpcIngressTenant("tenant");
                assertEquals(0,bridge.dataAdmissionStatus(node).committed());
            }finally{resume.countDown();}
            var denied=assertThrows(ExecutionException.class,()->late.get(5,TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class,denied.getCause());
            assertTrue(denied.getCause().getMessage().contains("publication fenced"));
            assertEquals(0,bridge.dataAdmissionStatus(node).committed());
            assertEquals(0,bridge.dataAdmissionStatus(node).inFlight());
            assertEquals(new IpcIngressBudgetV1.Usage(0,0),budget.usage());
            child.send(Boolean.TRUE); // local path unchanged
            assertEquals(1,bridge.dataAdmissionStatus(node).committed());
        }
    }

    @Test void brokerIngressAuthorityIsOneShotAndCloseFencesInFlightPublication() throws Exception {
        byte[] key=new byte[32], connection=new byte[16], digest=new byte[32];
        Arrays.fill(key,(byte)7);Arrays.fill(connection,(byte)8);Arrays.fill(digest,(byte)9);
        var binding=new IpcSchemaBoundDataV1.Binding(IpcSchemaBoundDataV1.Codec.CBOR,1,"bool.v1",digest);
        try(ActorRuntime runtime=new ActorRuntime();
            ExecutorService control=Executors.newSingleThreadExecutor();
            ExecutorService worker=Executors.newSingleThreadExecutor()){
            var bridge=new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            var recovery=new SupervisorEscrowSubtreeRecoveryV1(runtime,bridge,control,ignored->false);
            ActorRuntime.ActorRef<Boolean> child=runtime.spawnPrivate(ignored->(m,turn)->{});
            long node=recovery.attach(child,null);
            var budget=runtime.registerIpcIngressTenant("tenant",1024*1024,2);
            runtime.authorizeIpcIngressActor(child,"tenant",budget);
            byte[] body=IpcSchemaBoundDataV1.wrap(binding,new byte[]{(byte)0xf5});
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            var epoch=new IpcReceiptCorrelation.Envelope("tenant","sender",child.id().value().toString(),
                    "broker-ingress",hash,1,child.generation(),3,4,1,1);
            var auth=authority(5,connection,key);
            CountDownLatch decoding=new CountDownLatch(1),resume=new CountDownLatch(1);
            var ingress=new IpcPinnedActorIngressV1<>(child,epoch,auth,binding,budget,BOOL_CREDIT,
                    boolDecoder(bytes->{
                        decoding.countDown();
                        try{if(!resume.await(10,TimeUnit.SECONDS))throw new IOException("fixture deadline");}
                        catch(InterruptedException x){Thread.currentThread().interrupt();throw new IOException(x);}
                        return Boolean.TRUE;
                    }));
            assertTrue(auth.consumedOrDestroyed());
            assertThrows(IllegalStateException.class,()->new IpcPinnedActorIngressV1<>(
                    child,epoch,auth,binding,budget,BOOL_CREDIT,boolDecoder(bytes->Boolean.TRUE)));
            byte[] frame=IpcAuthenticatedSocketTransport.encodeDataFrame(epoch,body,5,connection,key);
            var late=worker.submit(()->{ingress.admit(frame);return null;});
            try{
                assertTrue(decoding.await(5,TimeUnit.SECONDS));
                ingress.close();
                ingress.close();
                assertTrue(ingress.authenticationErased());
                assertThrows(IOException.class,()->ingress.admit(frame));
            }finally{resume.countDown();}
            var denied=assertThrows(ExecutionException.class,()->late.get(5,TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class,denied.getCause());
            assertEquals(0,bridge.dataAdmissionStatus(node).committed());
            assertEquals(new IpcIngressBudgetV1.Usage(0,0),budget.usage());
            var next=new IpcPinnedActorIngressV1<>(child,epoch,authority(6,connection,key),
                    binding,budget,BOOL_CREDIT,boolDecoder(bytes->Boolean.TRUE));
            runtime.retireIpcIngressTenant("tenant");
            assertTrue(next.authenticationErased());
            assertThrows(IOException.class,()->next.admit(frame));
        }
    }

    private static IpcCborDataPlanV1 orderPlan(){
        return new IpcCborDataPlanV1("order.v1",1,"Order",java.util.List.of(
                new IpcCborDataPlanV1.Node("Order",IpcCborDataPlanV1.Kind.RECORD,java.util.List.of(
                        new IpcCborDataPlanV1.Field(1,"total","I64"),
                        new IpcCborDataPlanV1.Field(0,"paid","Bool"))),
                new IpcCborDataPlanV1.Node("Bool",IpcCborDataPlanV1.Kind.BOOL,java.util.List.of()),
                new IpcCborDataPlanV1.Node("I64",IpcCborDataPlanV1.Kind.I64,java.util.List.of())),
                new IpcCborDataPlanV1.Limits(1024,8192,4096,100,100,8));
    }
    @Test void canonicalCborPlanMatchesReferenceDigestAndPublishesActualOwnedRecord()throws Exception{
        var plan=orderPlan();
        var reference=new IpcSchemaBoundDataV1.Binding(IpcSchemaBoundDataV1.Codec.CBOR,1,"order.v1",
                HexFormat.of().parseHex("610eef10e566bf78d2741deea68e26171a2c03f901ccb2a7c2f032090afa969f"));
        plan.requireBinding(reference);
        assertThrows(IllegalArgumentException.class,()->plan.requireBinding(
                new IpcSchemaBoundDataV1.Binding(IpcSchemaBoundDataV1.Codec.CBOR,1,"order.v1",new byte[32])));
        byte[] key=new byte[32],connection=new byte[16];Arrays.fill(key,(byte)7);Arrays.fill(connection,(byte)8);
        var delivered=new java.util.concurrent.atomic.AtomicReference<Object>();
        try(ActorRuntime runtime=new ActorRuntime()){
            var child=runtime.<Object>spawnPrivateTrusted(ignored->(message,turn)->delivered.set(message));
            var budget=runtime.registerIpcIngressTenant("tenant",1024*1024,2);
            runtime.authorizeIpcIngressActor(child,"tenant",budget);
            byte[] good=IpcSchemaBoundDataV1.wrap(reference,new byte[]{(byte)0x82,(byte)0xf5,0x18,42});
            byte[] bad=IpcSchemaBoundDataV1.wrap(reference,new byte[]{(byte)0x82,1,(byte)0xf5});
            var first=new IpcReceiptCorrelation.Envelope("tenant","sender",child.id().value().toString(),
                    "real-cbor",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bad)),
                    1,child.generation(),3,4,1,1);
            var second=new IpcReceiptCorrelation.Envelope("tenant","sender",child.id().value().toString(),
                    "real-cbor",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(good)),
                    1,child.generation(),3,4,2,2);
            try(var ingress=new IpcPinnedActorIngressV1<>(child,first,authority(5,connection,key),reference,budget,8192,plan)){
                assertThrows(IOException.class,()->ingress.admit(IpcAuthenticatedSocketTransport.encodeDataFrame(first,bad,5,connection,key)));
                assertEquals(new IpcIngressBudgetV1.Usage(0,0),budget.usage());
                ingress.admit(IpcAuthenticatedSocketTransport.encodeDataFrame(second,good,5,connection,key));
                child.drain().get(5,TimeUnit.SECONDS);
                assertEquals(java.util.List.of(true,42L),delivered.get());
                assertEquals(new IpcIngressBudgetV1.Usage(0,0),budget.usage());
            }
        }
    }
    @Test void boundedCborPlanRejectsMalformedAndOneUnderCredit()throws Exception{
        var plan=orderPlan();
        var good=java.nio.ByteBuffer.wrap(new byte[]{(byte)0x82,(byte)0xf5,0x18,42}).asReadOnlyBuffer();
        long credit=plan.preflight(good);
        assertEquals(java.util.List.of(true,42L),plan.decode(good,credit));
        assertThrows(IOException.class,()->plan.decode(good,credit-1));
        for(byte[] bad:new byte[][]{{(byte)0x81,(byte)0xf5},{(byte)0x82,(byte)0xf5,0x18,1},
                {(byte)0x82,(byte)0xf5,0x18},{(byte)0x82,(byte)0xf5,1,0},{(byte)0x9f,(byte)0xf5,1,(byte)0xff}}){
            var input=java.nio.ByteBuffer.wrap(bad).asReadOnlyBuffer();
            assertThrows(IOException.class,()->plan.preflight(input));
        }
        var textPlan=new IpcCborDataPlanV1("text.v1",1,"Text",java.util.List.of(
                new IpcCborDataPlanV1.Node("Text",IpcCborDataPlanV1.Kind.TEXT,java.util.List.of())),
                new IpcCborDataPlanV1.Limits(32,1024,128,4,4,2));
        var valid=java.nio.ByteBuffer.wrap(new byte[]{0x62,(byte)0xc3,(byte)0xa9}).asReadOnlyBuffer();
        assertEquals("é",textPlan.decode(valid,textPlan.preflight(valid)));
        for(byte[] bad:new byte[][]{{0x62,(byte)0xc0,(byte)0x80},{0x63,(byte)0xed,(byte)0xa0,(byte)0x80},{0x61,(byte)0xc2}}){
            assertThrows(IOException.class,()->textPlan.preflight(java.nio.ByteBuffer.wrap(bad).asReadOnlyBuffer()));
        }
    }

    @Test void cborNamedRecursionAndScalarLimitsAreBounded()throws Exception{
        var recursive=new IpcCborDataPlanV1("recursive.v1",1,"List",java.util.List.of(
                new IpcCborDataPlanV1.Node("List",IpcCborDataPlanV1.Kind.LIST,
                        java.util.List.of(new IpcCborDataPlanV1.Field(0,"element","List")))),
                new IpcCborDataPlanV1.Limits(32,1024,128,8,8,2));
        var finite=java.nio.ByteBuffer.wrap(new byte[]{(byte)0x81,(byte)0x81,(byte)0x80}).asReadOnlyBuffer();
        assertEquals(java.util.List.of(java.util.List.of(java.util.List.of())),recursive.decode(finite,recursive.preflight(finite)));
        var deep=java.nio.ByteBuffer.wrap(new byte[]{(byte)0x81,(byte)0x81,(byte)0x81,(byte)0x80}).asReadOnlyBuffer();
        assertThrows(IOException.class,()->recursive.preflight(deep));
        var scalar=new IpcCborDataPlanV1("int.v1",1,"Int",java.util.List.of(
                new IpcCborDataPlanV1.Node("Int",IpcCborDataPlanV1.Kind.I64,java.util.List.of())),
                new IpcCborDataPlanV1.Limits(32,1024,128,8,8,2));
        for(long expected:new long[]{Long.MIN_VALUE,Long.MAX_VALUE}){
            byte[] wire=java.nio.ByteBuffer.allocate(9).put((byte)(expected<0?0x3b:0x1b)).putLong(Long.MAX_VALUE).array();
            var input=java.nio.ByteBuffer.wrap(wire).asReadOnlyBuffer();
            assertEquals(expected,scalar.decode(input,scalar.preflight(input)));
        }
        var floatPlan=new IpcCborDataPlanV1("float.v1",1,"F64",java.util.List.of(
                new IpcCborDataPlanV1.Node("F64",IpcCborDataPlanV1.Kind.F64,java.util.List.of())),
                new IpcCborDataPlanV1.Limits(32,1024,128,8,8,2));
        var negativeZero=java.nio.ByteBuffer.wrap(java.nio.ByteBuffer.allocate(9).put((byte)0xfb).putLong(Long.MIN_VALUE).array()).asReadOnlyBuffer();
        assertEquals(Long.MIN_VALUE,Double.doubleToRawLongBits((Double)floatPlan.decode(negativeZero,floatPlan.preflight(negativeZero))));
        var nan=java.nio.ByteBuffer.wrap(java.nio.ByteBuffer.allocate(9).put((byte)0xfb).putLong(0x7ff8000000000000L).array()).asReadOnlyBuffer();
        assertThrows(IOException.class,()->floatPlan.preflight(nan));
    }

}
