package dev.oreslang.runtime.persistence;

import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Real JDBC/SQLite host integration, NOT compiled guest AttoLang. */
public final class DurableCommandInboxTestMain {
    private DurableCommandInboxTestMain() {}
    private static void check(boolean condition,String reason) {
        if (!condition) throw new AssertionError(reason);
    }
    private static byte[] b(String s) { return s.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    private static SqlitePersistenceStore.CommandEvent event(String payload) {
        return new SqlitePersistenceStore.CommandEvent(1,b(payload));
    }
    private static boolean denied(SqlTask task) throws Exception {
        try {task.run();return false;}catch(SecurityException expected){return true;}
    }
    @FunctionalInterface private interface SqlTask {void run() throws Exception;}
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("atto-durable-command-");
        Path path=dir.resolve("bank.sqlite");
        Identity id=new Identity("tenant-a","bank-42","main");
        try {
            try(SqlitePersistenceStore store=new SqlitePersistenceStore(path)) {
                store.grantEpoch(id.tenant(),id.actor(),1);
                byte[] request=b("Deposit(100)"),response=b("Ok(balance=100)");
                var original=store.commitCommand(id,1,"op-42",0,request,
                    List.of(event("MoneyDeposited(60)"),event("MoneyDeposited(40)")),response);
                check(!original.duplicate() && original.firstVersion()==1 &&
                    original.lastVersion()==2 && Arrays.equals(original.reply(),response),
                    "original atomic commit/reply mismatch");
                var durable=store.loadFenced(id,1);
                check(durable.version()==2 && durable.events().size()==2,
                    "multi-event command did not atomically persist two journal rows");
                var repeat=store.commitCommand(id,1,"op-42",0,request,
                    List.of(event("a different reducer must not be invoked")),b("wrong"));
                check(repeat.duplicate() && repeat.lastVersion()==2 &&
                    Arrays.equals(repeat.reply(),response),
                    "same request did not reuse original durable reply");
                check(denied(()->store.resolveCommandFenced(
                    id,1,"op-42",b("Deposit(200)"))),
                    "conflicting command with same op-id was accepted");
                store.grantEpoch(id.tenant(),id.actor(),2);
                check(denied(()->store.resolveCommandFenced(id,1,"op-42",request)),
                    "stale incarnation read command reply");
                var restored=store.resolveCommandFenced(id,2,"op-42",request).orElseThrow();
                check(restored.duplicate() && Arrays.equals(restored.reply(),response),
                    "new actor epoch did not resolve original command reply");
                check(store.resolveCommandFenced(id,2,"missing",request).isEmpty(),
                    "unknown operation was fabricated");
                check(store.loadFenced(id,2).version()==2,
                    "duplicate command modified event ledger");
                check(denied(()->store.commitCommand(id,2,"op-42",2,b("Deposit(200)"),
                    List.of(event("MoneyDeposited(200)")),b("wrong"))),
                    "same operation ID accepted different request after epoch switch");
                var after=store.commitCommand(id,2,"op-43",2,b("Deposit(5)"),
                    List.of(event("MoneyDeposited(5)")),b("Ok(balance=105)"));
                check(after.lastVersion()==3,"new command after recovery not admitted");
            }
            // New provider process view: lookup is durable across connection close.
            try(SqlitePersistenceStore reopened=new SqlitePersistenceStore(path)) {
                var saved=reopened.resolveCommandFenced(
                    id,2,"op-42",b("Deposit(100)")).orElseThrow();
                check(saved.lastVersion()==2 && Arrays.equals(saved.reply(),b("Ok(balance=100)")),
                    "original reply did not survive SQLite restart");
                saved.reply()[0]=0;
                check(Arrays.equals(reopened.resolveCommandFenced(id,2,"op-42",
                    b("Deposit(100)")).orElseThrow().reply(),b("Ok(balance=100)")),
                    "mutable reply leaked from provider");
            }
            // Transaction rollback under deliberate failure of second event:
            Identity fault=new Identity("tenant-a","bank-fault","main");
            try(SqlitePersistenceStore store=new SqlitePersistenceStore(path);
                Connection injector=DriverManager.getConnection("jdbc:sqlite:"+path)) {
                store.grantEpoch(fault.tenant(),fault.actor(),1);
                try(Statement trigger=injector.createStatement()){
                    trigger.execute("CREATE TRIGGER fail_second BEFORE INSERT ON events "
                        +"WHEN NEW.actor='bank-fault' AND NEW.version=2 "
                        +"BEGIN SELECT RAISE(ABORT,'injected'); END");
                }
                boolean failed=false;
                try {store.commitCommand(fault,1,"op-fault",0,b("Deposit(10)"),
                    List.of(event("6"),event("4")),b("Ok(10)"));}
                catch(SQLException expected){failed=true;}
                check(failed,"fault injection did not abort second event");
                check(store.loadFenced(fault,1).version()==0 &&
                    store.resolveCommandFenced(fault,1,"op-fault",b("Deposit(10)")).isEmpty(),
                    "failed event batch partially committed journal or reply");
                try(Statement trigger=injector.createStatement()){
                    trigger.execute("DROP TRIGGER fail_second");
                }
                check(store.commitCommand(fault,1,"op-fault",0,b("Deposit(10)"),
                    List.of(event("6"),event("4")),b("Ok(10)")).lastVersion()==2,
                    "valid retry after confirmed rollback did not commit");
            }
            System.out.println("PASS real SQLite durable command inbox + atomic event batch + cross-epoch reply");
        }finally{
            try(var paths=Files.walk(dir)){
                paths.sorted(Comparator.reverseOrder()).forEach(p->{
                    try {Files.deleteIfExists(p);}
                    catch(Exception e){throw new IllegalStateException(e);}
                });
            }
        }
    }
}
