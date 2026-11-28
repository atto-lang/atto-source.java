package dev.oreslang.runtime;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Host-only test with explicit actor API shims, NOT a compiler/real actor test. */
public final class WalStorageActorGateHostTest {
    private WalStorageActorGateHostTest() { }
    private static int checks;
    private static void ok(boolean x,String reason){++checks;if(!x)throw new AssertionError(reason);}
    private static void reject(Class<? extends RuntimeException> cls,Runnable action){
        ++checks;
        try{action.run();throw new AssertionError("expected "+cls.getSimpleName());}
        catch(RuntimeException ex){if(!cls.isInstance(ex))throw ex;}
    }
    private static void i31(byte[] b,int offset,int value){
        for(int i=0;i<4;i++)b[offset+i]=(byte)(value >>> (8*i));
    }
    private static byte[] put(int tenant,int generation,int sequence,int operation){
        byte[] b=new byte[32];
        b[0]='O'; b[1]='D'; b[2]='B'; b[3]='1'; b[4]=1; b[5]=1;
        i31(b,6,tenant); i31(b,10,generation); i31(b,14,sequence); i31(b,18,operation);
        b[22]=1; b[24]=1; b[26]='k'; b[27]='v';
        int a=1,c=0;
        for(int i=0;i<28;i++){a=(a+(b[i]&255))%65521;c=(c+a)%65521;}
        b[28]=(byte)a;b[29]=(byte)(a>>>8);b[30]=(byte)c;b[31]=(byte)(c>>>8);
        return b;
    }
    public static void main(String[] ignored) throws Exception {
        ActorRuntime runtime=new ActorRuntime();
        ActorRuntime otherRuntime=new ActorRuntime();
        var ref=new ActorRuntime.ActorRef<String>(new ActorRuntime.ActorId("owner"),5);
        var foreign=new ActorRuntime.ActorRef<String>(new ActorRuntime.ActorId("foreign"),5);
        var scope=new WalStorageFileOwner.Scope(7,9,3,ref.storageActorOrdinal(),5);
        var staleScope=new WalStorageFileOwner.Scope(7,9,3,ref.storageActorOrdinal(),4);
        var misboundScope=new WalStorageFileOwner.Scope(7,9,3,foreign.storageActorOrdinal(),5);
        var ctx=new OresContext(runtime);
        var domain=new Object();
        Path root=Files.createTempDirectory("wal-gate-");
        Path path=root.resolve("wal.log");
        try {
            reject(SecurityException.class,()->WalStorageActorGate.captureOnActorTurn(ctx,ref,scope,root));
            ActorRuntime.enter(runtime,ref,domain,ActorRuntime.ActorKind.PRIVATE);
            reject(SecurityException.class,()->WalStorageActorGate.captureOnActorTurn(ctx,foreign,scope,root));
            // A forged ActorRef with exactly equal numeric ID and generation
            // must NOT be able to substitute for the runtime-owned ActorCell.
            var forgedSameId=new ActorRuntime.ActorRef<String>(ref.id(),ref.generation());
            reject(SecurityException.class,()->WalStorageActorGate.captureOnActorTurn(
                    ctx,forgedSameId,scope,root));
            reject(SecurityException.class,()->WalStorageActorGate.captureOnActorTurn(ctx,ref,staleScope,root));
            reject(SecurityException.class,()->WalStorageActorGate.captureOnActorTurn(ctx,ref,misboundScope,root));
            ctx.writeAllowed=false;
            reject(SecurityException.class,()->WalStorageActorGate.captureOnActorTurn(ctx,ref,scope,root));
            ctx.writeAllowed=true;
            var captured=WalStorageActorGate.captureOnActorTurn(ctx,ref,scope,root);
            ok(captured!=null,"capture in real actor turn shim");
            ActorRuntime.leave();
            var anotherContext=new OresContext(otherRuntime);
            ActorRuntime.enter(runtime,ref,domain,ActorRuntime.ActorKind.PRIVATE);
            reject(SecurityException.class,()->WalStorageActorGate.captureOnActorTurn(anotherContext,ref,scope,root));
            ActorRuntime.leave();
            ActorRuntime.enter(runtime,ref,domain,ActorRuntime.ActorKind.UNTRUSTED);
            reject(SecurityException.class,()->WalStorageActorGate.captureOnActorTurn(ctx,ref,scope,root));
            ActorRuntime.leave();
            FileChannel fd=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE);
            ActorRuntime.enter(runtime,ref,domain,ActorRuntime.ActorKind.PRIVATE);
            reject(SecurityException.class,()->{
                try{WalStorageActorGate.attachOffCarrier(captured,fd);}
                catch(java.io.IOException e){throw new RuntimeException(e);}
            });
            ActorRuntime.leave();
            var gate=WalStorageActorGate.attachOffCarrier(captured,fd);
            byte[] first=put(7,5,1,42);
            reject(SecurityException.class,()->gate.appendAndSyncOnActorTurn(first));
            ActorRuntime.enter(runtime,foreign,new Object(),ActorRuntime.ActorKind.PRIVATE);
            reject(SecurityException.class,()->gate.appendAndSyncOnActorTurn(first));
            ActorRuntime.leave();
            ActorRuntime.enter(runtime,ref,new Object(),ActorRuntime.ActorKind.PRIVATE);
            reject(SecurityException.class,()->gate.appendAndSyncOnActorTurn(first));
            ActorRuntime.leave();
            ActorRuntime.enter(runtime,ref,domain,ActorRuntime.ActorKind.PRIVATE);
            var pending=gate.appendAndSyncOnActorTurn(first);
            ActorRuntime.leave();
            Arrays.fill(first,(byte)0);
            var receipt=pending.completion().toCompletableFuture().get(5,TimeUnit.SECONDS);
            ok(Arrays.equals(Files.readAllBytes(path),put(7,5,1,42)),"real sync physical byte copy");
            reject(SecurityException.class,()->gate.verifyPhysicalSyncOnActorTurn(pending,receipt));
            ActorRuntime.enter(runtime,ref,domain,ActorRuntime.ActorKind.PRIVATE);
            gate.verifyPhysicalSyncOnActorTurn(pending,receipt);
            reject(SecurityException.class,()->gate.verifyPhysicalSyncOnActorTurn(pending,receipt));
            ok(receipt.operation()==42,"receipt belongs to pending");
            var second=gate.appendAndSyncOnActorTurn(put(7,5,2,43));
            ActorRuntime.leave();
            var secondReceipt=second.completion().toCompletableFuture().get(5,TimeUnit.SECONDS);
            ActorRuntime.enter(runtime,ref,domain,ActorRuntime.ActorKind.PRIVATE);
            reject(SecurityException.class,()->gate.verifyPhysicalSyncOnActorTurn(pending,secondReceipt));
            reject(SecurityException.class,()->gate.verifyPhysicalSyncOnActorTurn(second,receipt));
            gate.verifyPhysicalSyncOnActorTurn(second,secondReceipt);
            reject(SecurityException.class,()->gate.verifyPhysicalSyncOnActorTurn(second,secondReceipt));
            ok(secondReceipt.sequence()==2,"second physical sync and stage identity");
            ok(Files.size(path)==64,"second append physical file length");
            reject(SecurityException.class,()->gate.verifyPhysicalSyncOnActorTurn(null,receipt));
            reject(SecurityException.class,()->gate.verifyPhysicalSyncOnActorTurn(pending,null));
            ctx.writeAllowed=false;
            reject(SecurityException.class,()->gate.verifyPhysicalSyncOnActorTurn(pending,receipt));
            ctx.writeAllowed=true;
            ActorRuntime.leave();
            ActorRuntime.enter(runtime,ref,domain,ActorRuntime.ActorKind.UNTRUSTED);
            reject(SecurityException.class,()->gate.appendAndSyncOnActorTurn(put(7,5,3,44)));
            ActorRuntime.leave();
            gate.revokeAndCloseAsync().toCompletableFuture().get(5,TimeUnit.SECONDS);
            ok(!fd.isOpen(),"released physical file after quiescence");
            ActorRuntime.enter(runtime,ref,domain,ActorRuntime.ActorKind.PRIVATE);
            reject(SecurityException.class,()->gate.appendAndSyncOnActorTurn(put(7,5,2,43)));
            ActorRuntime.leave();
            ref.retire();
            ActorRuntime.enter(runtime,ref,domain,ActorRuntime.ActorKind.PRIVATE);
            reject(SecurityException.class,()->WalStorageActorGate.captureOnActorTurn(ctx,ref,scope,root));
            ActorRuntime.leave();
        } finally {
            ActorRuntime.leave();
            Files.deleteIfExists(path);
            Files.deleteIfExists(root);
        }
        System.out.println("WAL_ACTOR_GATE_SHIM_PASS checks="+checks+"; NOT ACTUAL ACTOR OR GUEST");
    }
}
