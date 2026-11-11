package dev.oreslang.runtime.persistence;

import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.Identity;
import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.Status;
import dev.oreslang.runtime.persistence.SqliteCommandTransactionStore.Command;
import dev.oreslang.runtime.persistence.SqliteCommandTransactionStore.Receipt;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * REAL JDBC subprocess crash/reopen and cross-epoch command deduplication.
 * Host-only: a subprocess halt after the provider COMMIT and before the
 * parent receives an ACK; not a hardware power-cut or guest AOT proof.
 */
public final class SqliteCommandCrashRestartTestMain {
    private SqliteCommandCrashRestartTestMain() {}
    private static final Identity ID=new Identity("t-crash","bank-1","ledger");
    private static void check(boolean ok,String reason) {
        if(!ok)throw new AssertionError(reason);
    }
    private static byte[] cents(long amount) {
        return ByteBuffer.allocate(Long.BYTES).putLong(amount).array();
    }
    private static Command deposit(long epoch) {
        return new Command(ID,"deposit-100",epoch,0,1,
                "deposit:100".getBytes(StandardCharsets.UTF_8),
                List.of(cents(40),cents(60)),cents(100));
    }
    private static void child(Path database,String phase) throws Exception {
        try(SqliteCommandTransactionStore commands=
                    new SqliteCommandTransactionStore(database)) {
            Command command=deposit(1);
            if("before-commit".equals(phase))
                Runtime.getRuntime().halt(74);
            if(!"after-commit".equals(phase))
                throw new IllegalArgumentException("INVALID_CRASH_PHASE");
            Receipt persisted=commands.commit(command);
            check(persisted.durable() && persisted.status()==Status.COMMITTED &&
                  persisted.version()==2 && persisted.eventCount()==2,
                  "crash fixture did not commit original logical command");
            // No parent/guest ACK is emitted before terminating the process.
            Runtime.getRuntime().halt(73);
        }
    }
    private static void invokeChild(Path database,String phase,int exit)
            throws Exception {
        Process child=new ProcessBuilder(
                Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",System.getProperty("java.class.path"),
                SqliteCommandCrashRestartTestMain.class.getName(),
                "child",database.toString(),phase)
            .redirectErrorStream(true).start();
        if(!child.waitFor(20,TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new AssertionError("JDBC crash child never terminated: "+phase);
        }
        check(child.exitValue()==exit,
            "unexpected crash exit phase "+phase+": "+child.exitValue());
    }
    private static void scenario(String phase,int childExit,boolean committed)
            throws Exception {
        Path directory=Files.createTempDirectory("ores-jdbc-crash-");
        Path database=directory.resolve("actor.sqlite");
        try {
            try(SqlitePersistenceStore legacy=
                    new SqlitePersistenceStore(database)) {
                legacy.grantEpoch(ID.tenant(),ID.actor(),1);
            }
            invokeChild(database,phase,childExit);
            try(SqlitePersistenceStore legacy=
                    new SqlitePersistenceStore(database);
                SqliteCommandTransactionStore commands=
                    new SqliteCommandTransactionStore(database)) {
                legacy.grantEpoch(ID.tenant(),ID.actor(),2);
                var recovery=commands.loadVerified(ID,2);
                check(recovery.version()==(committed?2:0),
                      "lost ACK recovery mismatch: "+phase);
                check(recovery.events().size()==(committed?2:0),
                      "partial or duplicated journal: "+phase);
                Receipt retried=commands.commit(deposit(2));
                check(retried.durable() && retried.version()==2 &&
                      retried.firstVersion()==1 && retried.eventCount()==2,
                      "epoch-rotated retry did not produce durable original result");
                check(retried.status()==(committed?Status.DUPLICATE:Status.COMMITTED),
                      "crash retry double-applied or skipped a command: "+phase);
                check(java.util.Arrays.equals(retried.resultBytes(),cents(100)),
                      "recovery returned altered result");
                recovery=commands.loadVerified(ID,2);
                check(recovery.version()==2 && recovery.events().size()==2 &&
                      java.util.Arrays.equals(recovery.events().get(0).payload(),cents(40)) &&
                      java.util.Arrays.equals(recovery.events().get(1).payload(),cents(60)),
                      "after-retry balance/version/event payload mismatch");
                check(commands.verify(deposit(2)).durable(),
                      "provider could not re-prove command after retry");
                boolean staleDenied=false;
                try{commands.verify(deposit(1));}
                catch(SecurityException expected){staleDenied=true;}
                check(staleDenied,"stale activation recovered durable ACK");
            }
        } finally {
            try(var paths=Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p->{
                    try {Files.deleteIfExists(p);}
                    catch(Exception failure){throw new IllegalStateException(failure);}
                });
            }
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length==3 && "child".equals(args[0])) {
            child(Path.of(args[1]),args[2]);return;
        }
        if(args.length>0)throw new IllegalArgumentException("INVALID_TEST_ARGS");
        scenario("before-commit",74,false);
        scenario("after-commit",73,true);
        System.out.println("PASS host JDBC crash-before-COMMIT and crash-after-COMMIT-before-ACK");
    }
}
