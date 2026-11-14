package dev.oreslang.runtime;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Host-only regression. Never present as Oreslang guest or crash durability evidence. */
public final class WalStorageFileOwnerTest {
    private static int checks;
    private WalStorageFileOwnerTest() { }
    private static void ok(boolean condition, String what) {
        ++checks;
        if (!condition) throw new AssertionError(what);
    }
    private static void denied(Class<? extends Throwable> kind, Runnable code) {
        ++checks;
        try { code.run(); throw new AssertionError("expected " + kind.getSimpleName()); }
        catch (RuntimeException rejected) {
            if (!kind.isInstance(rejected)) throw rejected;
        }
    }
    private static void putU32(byte[] a, int off, int value) {
        for (int i=0;i<4;i++) a[off+i]=(byte)(value >>> (8*i));
    }
    private static byte[] frame(int tenant,int generation,int sequence,int operation) {
        byte[] a = new byte[32];
        a[0]=79;a[1]=68;a[2]=66;a[3]=49;a[4]=1;a[5]=1;
        putU32(a,6,tenant);putU32(a,10,generation);putU32(a,14,sequence);putU32(a,18,operation);
        a[22]=1;a[24]=1;a[26]=107;a[27]=118;
        int s1=1,s2=0;
        for(int i=0;i<28;i++) {s1=(s1+(a[i]&255))%65521;s2=(s2+s1)%65521;}
        a[28]=(byte)s1;a[29]=(byte)(s1>>>8);a[30]=(byte)s2;a[31]=(byte)(s2>>>8);
        return a;
    }
    private static void test() throws Exception {
        Path path = Files.createTempFile("oreswal-owner-", ".log");
        Object authority=new Object();
        var scope=new WalStorageFileOwner.Scope(7,9,3,11,5);
        byte[] first=frame(7,5,1,42);
        byte[] next=frame(7,5,2,43);
        try {
            FileChannel channel=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE);
            var wal=WalStorageFileOwner.openVerified(channel,authority,scope);
            denied(SecurityException.class,()->wal.appendAndSync(new Object(),scope,first));
            denied(SecurityException.class,()->wal.appendAndSync(authority,
                    new WalStorageFileOwner.Scope(7,9,3,11,6),first));
            denied(SecurityException.class,()->wal.appendAndSync(authority,scope,frame(8,5,1,42)));
            denied(IllegalArgumentException.class,()->wal.appendAndSync(authority,scope,new byte[31]));
            var f=wal.appendAndSync(authority,scope,first);
            Arrays.fill(first,(byte)0);
            var receipt=f.toCompletableFuture().get(5,TimeUnit.SECONDS);
            ok(receipt.operation()==42,"operation identity");
            ok(receipt.sequence()==1,"sequence");
            ok(receipt.endOffset()==32,"end offset");
            ok(receipt.scope().equals(scope),"receipt scope");
            byte[] digest=receipt.sha256();Arrays.fill(digest,(byte)0);
            ok(!Arrays.equals(digest,receipt.sha256()),"digest copy");
            ok(Arrays.equals(Files.readAllBytes(path),frame(7,5,1,42)),"physical write persisted before receipt");
            denied(java.util.concurrent.RejectedExecutionException.class,
                    ()->wal.appendAndSync(authority,scope,frame(7,5,2,42)));
            denied(java.util.concurrent.RejectedExecutionException.class,
                    ()->wal.appendAndSync(authority,scope,frame(7,5,3,44)));
            var second=wal.appendAndSync(authority,scope,next).toCompletableFuture().get(5,TimeUnit.SECONDS);
            ok(second.operation()==43 && second.endOffset()==64,"second synced");
            wal.revoke();
            denied(java.util.concurrent.RejectedExecutionException.class,
                    ()->wal.appendAndSync(authority,scope,frame(7,5,3,44)));
            wal.revokeAndCloseAsync().toCompletableFuture().get(5,TimeUnit.SECONDS);
            ok(!channel.isOpen(),"FD closed only after quiescence");
            ok(wal.revokeAndCloseAsync().toCompletableFuture().isDone(),"idempotent close stage");
            try (FileChannel reopened=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE)) {
                ok(reopened.size()==64,"file survives reopen");
                denied(IllegalStateException.class,
                    ()->{try {WalStorageFileOwner.openVerified(reopened,new Object(),scope);}
                        catch (java.io.IOException error) {throw new RuntimeException(error);} });
            }
        } finally {Files.deleteIfExists(path);}
        Path deniedPath=Files.createTempFile("oreswal-readonly-", ".log");
        try (FileChannel readOnly=FileChannel.open(deniedPath, StandardOpenOption.READ)) {
            var deniedWal=WalStorageFileOwner.openVerified(readOnly,authority,scope);
            try {
                deniedWal.appendAndSync(authority,scope,frame(7,5,1,1))
                        .toCompletableFuture().get(5,TimeUnit.SECONDS);
                throw new AssertionError("read-only WAL was accepted");
            } catch (ExecutionException expected) {
                ok(expected.getCause() instanceof WalStorageFileOwner.UnknownOutcome,
                        "read-only physical dispatch is Unknown");
            }
            denied(java.util.concurrent.RejectedExecutionException.class,
                    ()->deniedWal.appendAndSync(authority,scope,frame(7,5,2,2)));
            deniedWal.revokeAndCloseAsync().toCompletableFuture().get(5,TimeUnit.SECONDS);
        } finally {Files.deleteIfExists(deniedPath);}
    }
    public static void main(String[] args) throws Exception {
        test();
        System.out.println("WAL_HOST_RUNTIME_BOUNDARY_PASS checks="+checks+"; NO ORESLANG GUEST OR DURABILITY CLAIM");
    }
}
