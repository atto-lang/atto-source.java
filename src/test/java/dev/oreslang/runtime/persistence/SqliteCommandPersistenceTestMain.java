package dev.oreslang.runtime.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import dev.oreslang.runtime.persistence.SqliteCommandPersistence.*;

/** Real SQLite JDBC host tests; NOT compiled .ores actor or native-image evidence. */
public final class SqliteCommandPersistenceTestMain {
    private SqliteCommandPersistenceTestMain() {}
    private static int checks;
    private static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);checks++;}
    private static Identity id(){return new Identity("tenant-a","bank-1","main");}
    private static Command c(String operation,long epoch,long expected,byte[] command,List<byte[]> events) {
        return new Command(id(),operation,epoch,expected,1,command,events,new byte[]{100});
    }
    private static byte[] bytes(String value){return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    private static Command deposit(String id,long epoch,long expected,int value){
        return c(id,epoch,expected,bytes("deposit:"+value),List.of(bytes("+"+value)));
    }
    private static void fails(String expected,Throwing action)throws Exception{
        try {action.run();throw new AssertionError("did not reject "+expected);}
        catch(CommandError error){check(expected.equals(error.code),"wrong error: "+error.code);}
    }
    private interface Throwing {void run()throws Exception;}
    private static void sql(Path file,String statement)throws SQLException{
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+file);
            Statement s=c.createStatement()) {s.execute(statement);}
    }
    public static void main(String[] args)throws Exception{
        Class.forName("org.sqlite.JDBC");
        if(args.length==2 && args[0].equals("abrupt-child")){
            // No resource close, shutdown hook or actor ACK after committed write.
            SqliteCommandPersistence p=new SqliteCommandPersistence(Path.of(args[1]),"tenant-a");
            p.grantEpoch("bank-1",1);
            p.append(deposit("abrupt-commit",1,0,100));
            Runtime.getRuntime().halt(137);
            return;
        }
        Path dir=Files.createTempDirectory("ores-command-v2-");
        Path file=dir.resolve("commands.sqlite");
        try{
            try(SqliteCommandPersistence p=new SqliteCommandPersistence(file,"tenant-a")){
                p.grantEpoch("bank-1",1);
                Command two=c("two-events",1,0,bytes("sum:3,4"),List.of(bytes("+3"),bytes("+4")));
                Receipt first=p.appendAsync(two).toCompletableFuture().get(5,TimeUnit.SECONDS);
                check(first.lastVersion()==2 && first.firstVersion()==1 && first.eventCount()==2,
                    "two-event commit not atomic");
                check(!first.duplicate(),"unexpected duplicate");
                check(p.loadFencedAsync(id(),1).toCompletableFuture().get(5,TimeUnit.SECONDS)
                    .events().size()==2,"journal not durable");
                check(p.verifyAsync(id(),"two-events",1,bytes("sum:3,4"))
                    .toCompletableFuture().get(5,TimeUnit.SECONDS).lastVersion()==2,"no durable receipt");
                fails("VERSION_CONFLICT",()->p.append(deposit("wrong-version",1,0,12)));
                fails("OP_ID_CONFLICT",()->p.append(c("two-events",1,0,bytes("changed"),List.of(bytes("+3")))));
                p.grantEpoch("bank-1",2);
                fails("STALE_EPOCH",()->p.verify(id(),"two-events",1,bytes("sum:3,4")));
                Receipt duplicate=p.append(c("two-events",2,2,bytes("sum:3,4"),List.of(bytes("+3"))));
                check(duplicate.duplicate() && duplicate.lastVersion()==2,"epoch-2 idempotency failed");
                check(p.loadFenced(id(),2).version()==2,"epoch-2 recovery failed");
                // Trust boundary: SQL mutation of a durable receipt must be refused.
                try{sql(file,"DELETE FROM command_v2_receipts");throw new AssertionError("delete accepted");}
                catch(SQLException expected){check(expected.toString().contains("IMMUTABLE_COMMAND_RECEIPT"),"receipt delete not fenced");}
            }
            // In a separate database, inject a failure on the second event of a batch.
            Path rollback=dir.resolve("rollback.sqlite");
            try(SqliteCommandPersistence p=new SqliteCommandPersistence(rollback,"tenant-a")){
                p.grantEpoch("bank-1",1);
                sql(rollback,"CREATE TRIGGER deny_second BEFORE INSERT ON command_v2_events WHEN NEW.version=2 BEGIN SELECT RAISE(ABORT,'second event denied'); END");
                try{p.append(c("sum",1,0,bytes("sum:3,4"),List.of(bytes("+3"),bytes("+4"))));
                    throw new AssertionError("partial commit accepted");}
                catch(SQLException expected){checks++;}
                check(p.loadFenced(id(),1).version()==0,"partial journal survived rollback");
                check(p.verify(id(),"sum",1,bytes("sum:3,4"))==null,"orphaned receipt survived rollback");
                // Event digests share the exact append transaction: an
                // aborted command cannot leave a certified digest behind.
                try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+rollback);
                    Statement q=c.createStatement();
                    var rows=q.executeQuery("SELECT count(*) FROM command_v2_event_digests")) {
                    check(rows.next() && rows.getInt(1)==0,"orphaned event digest survived rollback");
                }
                sql(rollback,"DROP TRIGGER deny_second");
                check(p.append(c("sum",1,0,bytes("sum:3,4"),List.of(bytes("+3"),bytes("+4")))).lastVersion()==2,
                    "retry after rollback failed");
            }
            // Real abrupt subprocess death after COMMIT but before any caller ACK.
            Path abrupt=dir.resolve("abrupt.sqlite");
            Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",System.getProperty("java.class.path"),
                SqliteCommandPersistenceTestMain.class.getName(),"abrupt-child",abrupt.toString())
                .redirectErrorStream(true).start();
            check(child.waitFor(15,TimeUnit.SECONDS),"abrupt writer timed out");
            check(child.exitValue()==137,"abrupt writer did not halt after commit");
            try(SqliteCommandPersistence p=new SqliteCommandPersistence(abrupt,"tenant-a")){
                p.grantEpoch("bank-1",2);
                check(p.loadFenced(id(),2).version()==1,"abrupt write not recovered");
                check(p.append(deposit("abrupt-commit",2,1,100)).duplicate(),"lost ACK duplicated effect");
                check(p.loadFenced(id(),2).events().size()==1,"duplicate journal row after crash");
            }
            // A privileged corruption of an un-compacted command receipt fails closed.
            Path corrupt=dir.resolve("corrupt.sqlite");
            try(SqliteCommandPersistence p=new SqliteCommandPersistence(corrupt,"tenant-a")){
                p.grantEpoch("bank-1",1);p.append(deposit("one",1,0,100));
                sql(corrupt,"DROP TRIGGER command_v2_immutable_delete");
                sql(corrupt,"DELETE FROM command_v2_receipts");
                fails("UNINDEXED_JOURNAL",()->p.loadFenced(id(),1));
            }
            // Detect byte modification without changing versions or operation IDs.
            Path tamper=dir.resolve("tamper-event.sqlite");
            try(SqliteCommandPersistence p=new SqliteCommandPersistence(tamper,"tenant-a")){
                p.grantEpoch("bank-1",1);
                p.append(deposit("immutable-event",1,0,100));
                check(p.loadFenced(id(),1).events().size()==1,"baseline digest not readable");
                sql(tamper,"UPDATE command_v2_events SET payload=X'2B393939' WHERE version=1");
                fails("CORRUPT_JOURNAL_PAYLOAD",()->p.loadFenced(id(),1));
                // The original bytes remain recoverable after repairing the
                // corrupt event: digest is immutable and cannot be rewritten.
                sql(tamper,"UPDATE command_v2_events SET payload=X'2B313030' WHERE version=1");
                check(p.loadFenced(id(),1).events().size()==1,"repaired event cannot replay");
                sql(tamper,"UPDATE command_v2_events SET schema=2 WHERE version=1");
                fails("CORRUPT_JOURNAL_PAYLOAD",()->p.loadFenced(id(),1));
                sql(tamper,"UPDATE command_v2_events SET schema=1 WHERE version=1");
                try {
                    sql(tamper,"DELETE FROM command_v2_event_digests");
                    throw new AssertionError("immutable event checksum deletion accepted");
                }catch(SQLException expected) {
                    check(expected.toString().contains("IMMUTABLE_EVENT_DIGEST"),
                        "event checksum deletion was not fenced");
                }
                // Simulate a privileged database migration that accidentally
                // removes the checksum after bypassing the provider trigger.
                sql(tamper,"DROP TRIGGER command_v2_event_digest_no_delete");
                sql(tamper,"DELETE FROM command_v2_event_digests");
                fails("CORRUPT_JOURNAL_PAYLOAD",()->p.loadFenced(id(),1));
            }
            // An older draft database with receipts but no event-checksum
            // history must fail closed rather than mint a fabricated proof.
            Path legacy=dir.resolve("legacy-no-digest.sqlite");
            try(SqliteCommandPersistence p=new SqliteCommandPersistence(legacy,"tenant-a")){
                p.grantEpoch("bank-1",1);
                p.append(deposit("legacy",1,0,5));
                sql(legacy,"DROP TRIGGER command_v2_event_digest_no_delete");
                sql(legacy,"DELETE FROM command_v2_event_digests");
                fails("CORRUPT_JOURNAL_PAYLOAD",()->p.loadFenced(id(),1));
            }
            // A bounded stream may contain valid, individually sized
            // events whose aggregate replay exceeds the configured budget.
            // Rejection must not mutate durable receipts or the journal.
            Path bounded=dir.resolve("bounded-replay.sqlite");
            try(SqliteCommandPersistence p=new SqliteCommandPersistence(bounded,"tenant-a",4)){
                p.grantEpoch("bank-1",1);
                p.append(deposit("first",1,0,100)); // 4 bytes: +100
                check(p.loadFenced(id(),1).events().size()==1,"budget boundary failed");
                p.append(deposit("second",1,1,2)); // 2 more bytes, > 4
                fails("REPLAY_BYTES_LIMIT_EXCEEDED",()->p.loadFenced(id(),1));
                check(p.verify(id(),"second",1,bytes("deposit:2"))!=null,
                    "replay byte guard destroyed durable command proof");
            }
            fails("INVALID_CONFIG",()->{
                try(SqliteCommandPersistence invalid=
                        new SqliteCommandPersistence(dir.resolve("bad-budget.sqlite"),"tenant-a",0)) {
                    throw new AssertionError("invalid replay configuration accepted: "+invalid);
                }
            });
            System.out.println("PASS "+checks+" Java SQLite v2 command integration checks");
        }finally{
            try(var stream=Files.walk(dir)){
                for(Path path:stream.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
            }
        }
    }
}
