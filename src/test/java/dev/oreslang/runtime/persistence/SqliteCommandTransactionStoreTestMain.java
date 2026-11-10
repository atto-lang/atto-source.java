package dev.oreslang.runtime.persistence;

import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.*;
import dev.oreslang.runtime.persistence.SqliteCommandTransactionStore.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Real SQLite JDBC provider: atomic command CAS, epoch2 retry, immutable
 * byte copying, second-event rollback and compaction-safe command identity.
 * HOST ONLY. Not compiled .ores guest, Native Image or power-loss proof.
 */
public final class SqliteCommandTransactionStoreTestMain {
    private SqliteCommandTransactionStoreTestMain() {}
    private static void check(boolean ok, String reason) {
        if(!ok)throw new AssertionError(reason);
    }
    private static byte[] enc(long n) {
        return ByteBuffer.allocate(Long.BYTES).putLong(n).array();
    }
    private static long dec(byte[] bytes) {
        check(bytes.length==Long.BYTES,"BAD_EVENT_SCHEMA");
        return ByteBuffer.wrap(bytes).getLong();
    }
    private static Command command(Identity id,String op,long epoch,long expected,
                                   long first,long second) {
        return new Command(id,op,epoch,expected,1,
                ("deposit:"+first+":"+second).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                List.of(enc(first),enc(second)),enc(first+second));
    }
    private static void expectSqlFailure(SqlAction action,String contains) throws Exception {
        try {action.run();throw new AssertionError("expected storage failure: "+contains);}
        catch(java.sql.SQLException ex) {
            check(ex.toString().contains(contains),"incorrect SQL failure "+ex);
        }
    }
    @FunctionalInterface private interface SqlAction {void run() throws Exception;}

    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("ores-command-sqlite-");
        Path file=root.resolve("bank.sqlite");
        Identity id=new Identity("tenant-command","account-42","ledger");
        try {
            try(SqlitePersistenceStore legacy=new SqlitePersistenceStore(file);
                SqliteCommandTransactionStore commands=new SqliteCommandTransactionStore(file);
                AtomicCommandSidecar sidecar=new AtomicCommandSidecar(id.tenant(),commands,8,32768)) {
                legacy.grantEpoch(id.tenant(),id.actor(),1);
                Command cmd=command(id,"deposit-100",1,0,40,60);
                byte[] input=enc(40);
                Command owned=new Command(id,"copy-proof",1,2,1,
                        "copy-proof".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        List.of(input),enc(40));
                input[0]=99; // command copied event data on construction.
                Receipt first=sidecar.submit(cmd).toCompletableFuture().get(15,TimeUnit.SECONDS);
                check(first.status()==Status.COMMITTED && first.durable() &&
                    first.firstVersion()==1 && first.version()==2 &&
                    first.eventCount()==2,"multi-event commit did not persist atomically");
                // Golden cross-backend digest: JSON([schema,[base64(event)...]]).
                try(Connection proofDb=DriverManager.getConnection("jdbc:sqlite:"+file);
                    java.sql.PreparedStatement proof=proofDb.prepareStatement(
                        "SELECT hex(events_hash) FROM command_commits WHERE actor=? AND op=?")) {
                    proof.setString(1,id.actor());proof.setString(2,"deposit-100");
                    try(java.sql.ResultSet row=proof.executeQuery()) {
                        check(row.next() && row.getString(1).equals(
                          "AFF1AFCBDFC3F0C041700EA55A650ECAB604D16F0AB240C46B505429656A6D16"),
                          "command event digest does not match canonical cross-backend vector");
                    }
                }
                check(dec(commands.loadVerified(id,1).events().get(0).payload())==40,
                        "first event lost");
                check(dec(commands.loadVerified(id,1).events().get(1).payload())==60,
                        "second event lost");
                Receipt second=sidecar.submit(owned).toCompletableFuture().get(15,TimeUnit.SECONDS);
                check(second.version()==3 && second.status()==Status.COMMITTED,
                        "owned byte copy/version regression");
                check(dec(commands.loadVerified(id,1).events().get(2).payload())==40,
                        "caller mutated queued event bytes");
                Command conflicting=command(id,"deposit-100",1,0,40,61);
                boolean conflict=false;
                try{commands.verify(conflicting);}catch(SecurityException expected){conflict=true;}
                check(conflict,"different command with same logical op accepted");
                check(commands.commit(command(id,"bad-version",1,0,1,2)).status() ==
                      Status.VERSION_CONFLICT,"stale CAS committed");
            }
            // Restart provider and change physical activation generation, but
            // keep stable command identity and original committed result.
            try(SqlitePersistenceStore legacy=new SqlitePersistenceStore(file);
                SqliteCommandTransactionStore commands=new SqliteCommandTransactionStore(file)) {
                legacy.grantEpoch(id.tenant(),id.actor(),2);
                Command retry=command(id,"deposit-100",2,0,40,60);
                Receipt duplicate=commands.commit(retry);
                check(duplicate.durable() && duplicate.status()==Status.DUPLICATE &&
                      duplicate.version()==2 && duplicate.firstVersion()==1 &&
                      dec(duplicate.resultBytes())==100,"epoch2 duplicate altered balance");
                check(commands.loadVerified(id,2).version()==3 &&
                      commands.loadVerified(id,2).events().size()==3,"duplicate appended journal");
                boolean stale=false;
                try{commands.verify(command(id,"deposit-100",1,0,40,60));}
                catch(SecurityException expected){stale=true;}
                check(stale,"old activation read a durable success");
                // Produce a trusted snapshot barrier before compacting events.
                var snap=legacy.commit(List.of(new Write(id,"snapshot-1",2,3,1,
                        Kind.SNAPSHOT,enc(140))));
                check(snap.get(0).status()==Status.COMMITTED &&
                      snap.get(0).version()==4,"snapshot barrier not committed");
                try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+file);
                    Statement s=db.createStatement()) {
                    s.execute("DELETE FROM events WHERE version<=3");
                }
                check(commands.loadVerified(id,2).snapshotVersion()==4 &&
                      commands.loadVerified(id,2).events().isEmpty(),"compaction replay mismatch");
                check(commands.verify(retry).version()==2,
                      "logical command not retained after checkpoint compaction");
            }
            // A fresh account: abort the SECOND event; no partial journal/head
            // or durable command record is allowed to survive.
            Identity rollbackId=new Identity("tenant-command","account-rollback","ledger");
            try(SqlitePersistenceStore legacy=new SqlitePersistenceStore(file);
                SqliteCommandTransactionStore commands=new SqliteCommandTransactionStore(file)) {
                legacy.grantEpoch(rollbackId.tenant(),rollbackId.actor(),1);
                try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+file);
                    Statement s=db.createStatement()) {
                    s.execute("CREATE TRIGGER reject_second BEFORE INSERT ON events " +
                        "WHEN NEW.actor='account-rollback' AND NEW.version=2 " +
                        "BEGIN SELECT RAISE(ABORT,'INJECTED_SECOND_EVENT'); END;");
                }
                Command candidate=command(rollbackId,"abort-transaction",1,0,7,8);
                expectSqlFailure(()->commands.commit(candidate),"INJECTED_SECOND_EVENT");
                check(commands.loadVerified(rollbackId,1).version()==0 &&
                      commands.loadVerified(rollbackId,1).events().isEmpty(),
                      "rolled-back transaction published partial journal");
                check(commands.verify(candidate)==null,
                      "failed transaction left durable command result");
                try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+file);
                    Statement s=db.createStatement()) {s.execute("DROP TRIGGER reject_second");}
                Receipt succeeded=commands.commit(candidate);
                check(succeeded.status()==Status.COMMITTED &&
                      succeeded.version()==2 &&
                      commands.verify(candidate)!=null,"retry after rollback did not commit");
                try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+file);
                    Statement s=db.createStatement()) {
                    s.execute("UPDATE events SET payload=x'00000000000003E7' " +
                        "WHERE actor='account-rollback' AND version=1");
                }
                expectSqlFailure(()->commands.verify(candidate),"CORRUPT_COMMAND_EVENTS");
                expectSqlFailure(()->commands.loadVerified(rollbackId,1),"CORRUPT_COMMAND_EVENTS");
            }
            System.out.println("PASS host SQLite atomic multi-event / epoch retry / compaction / trigger rollback");
        } finally {
            try(var files=Files.walk(root)){
                files.sorted(Comparator.reverseOrder()).forEach(path->{
                    try{Files.deleteIfExists(path);}
                    catch(Exception failure){throw new IllegalStateException(failure);}
                });
            }
        }
    }
}
