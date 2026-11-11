package dev.oreslang.runtime.persistence;

import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.*;
import dev.oreslang.runtime.persistence.SqliteCommandTransactionStore.Command;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Comparator;
import java.util.List;

/**
 * Real JDBC admission limits for BOTH legacy and atomic event writers.
 * The synthetic prepopulated journal deliberately lacks older proofs:
 * this fixture verifies write admission ONLY, not historic replay integrity.
 * Must be executed on an approved exact-source runner.
 */
public final class SqliteReplayAdmissionTestMain {
    private SqliteReplayAdmissionTestMain() {}
    private static void check(boolean valid,String why) {
        if(!valid)throw new AssertionError(why);
    }
    private static Command command(Identity id,long version,String op) {
        return new Command(id,op,1,version,1,new byte[]{1},
                           List.of(new byte[]{2}),new byte[]{3});
    }
    public static void main(String[] args) throws Exception {
        Path directory=Files.createTempDirectory("ores-replay-limit-");
        Path database=directory.resolve("ledger.sqlite");
        Identity countId=new Identity("t-replay","account-count","ledger");
        Identity bytesId=new Identity("t-replay","account-bytes","ledger");
        Identity otherId=new Identity("t-replay","account-other","ledger");
        try {
            try(SqlitePersistenceStore legacy=new SqlitePersistenceStore(database);
                SqliteCommandTransactionStore commands=new SqliteCommandTransactionStore(database)) {
                for(Identity id:List.of(countId,bytesId,otherId))
                    legacy.grantEpoch(id.tenant(),id.actor(),1);
                // Seed just-below-boundary heads and exact limits within ONE
                // trusted SQLite transaction. No private source is mirrored.
                try(Connection raw=DriverManager.getConnection("jdbc:sqlite:"+database)) {
                    raw.setAutoCommit(false);
                    try(PreparedStatement head=raw.prepareStatement(
                            "INSERT INTO state(tenant,actor,slot,version," +
                            "snapshot_version,snapshot_schema,snapshot) VALUES(?,?,?,?,0,0,NULL)");
                        PreparedStatement event=raw.prepareStatement(
                            "INSERT INTO events(tenant,actor,slot,version,schema,payload)" +
                            " VALUES(?,?,?,?,1,?)")) {
                        for(Identity id:List.of(countId,bytesId)) {
                            head.setString(1,id.tenant());
                            head.setString(2,id.actor());
                            head.setString(3,id.state());
                            head.setLong(4,id.equals(countId)?10000:16);
                            head.executeUpdate();
                        }
                        for(int i=1;i<=10000;i++) {
                            event.setString(1,countId.tenant());
                            event.setString(2,countId.actor());
                            event.setString(3,countId.state());
                            event.setInt(4,i);
                            event.setBytes(5,new byte[0]);
                            event.executeUpdate();
                        }
                        byte[] oneMiB=new byte[1_048_576];
                        for(int i=1;i<=16;i++) {
                            event.setString(1,bytesId.tenant());
                            event.setString(2,bytesId.actor());
                            event.setString(3,bytesId.state());
                            event.setInt(4,i);
                            event.setBytes(5,oneMiB);
                            event.executeUpdate();
                        }
                        raw.commit();
                    } catch(Exception fail) {
                        raw.rollback();
                        throw fail;
                    }
                }
                check(commands.commit(command(countId,10000,"count-overflow"))
                      .status()==Status.REPLAY_LIMIT_EXCEEDED,
                      "atomic command exceeded 10k event replay limit");
                check(legacy.commit(List.of(new Write(countId,"legacy-count",
                            1,10000,1,Kind.EVENT,new byte[]{42})))
                      .get(0).status()==Status.REPLAY_LIMIT_EXCEEDED,
                      "legacy event exceeded 10k event replay limit");
                check(commands.commit(command(bytesId,16,"bytes-overflow"))
                      .status()==Status.REPLAY_LIMIT_EXCEEDED,
                      "atomic command exceeded 16 MiB replay limit");
                check(legacy.commit(List.of(new Write(bytesId,"legacy-bytes",
                            1,16,1,Kind.EVENT,new byte[]{42})))
                      .get(0).status()==Status.REPLAY_LIMIT_EXCEEDED,
                      "legacy event exceeded 16 MiB replay limit");
                check(commands.commit(command(otherId,0,"other-actor")).durable(),
                      "full journal in one actor blocked unrelated actor");
                List<Result> mixed=legacy.commit(List.of(
                        new Write(countId,"blocked-in-batch",1,10000,1,
                                  Kind.EVENT,new byte[]{1}),
                        new Write(otherId,"accepted-in-batch",1,1,1,
                                  Kind.EVENT,new byte[]{2})));
                check(mixed.get(0).status()==Status.REPLAY_LIMIT_EXCEEDED &&
                      mixed.get(1).status()==Status.COMMITTED && mixed.get(1).durable(),
                      "replay-capacity rejection incorrectly blocked unrelated actor in batch");
                // A separately durable checkpoint resets the bounded suffix
                // but never releases ownership of logical operation IDs.
                Result countSnapshot=legacy.commit(List.of(new Write(countId,
                        "count-checkpoint",1,10000,1,Kind.SNAPSHOT,new byte[]{10}))).get(0);
                check(countSnapshot.durable() && countSnapshot.version()==10001,
                      "snapshot barrier could not break replay backlog");
                Result afterCheckpoint=legacy.commit(List.of(new Write(countId,
                        "after-checkpoint",1,10001,1,Kind.EVENT,new byte[]{2}))).get(0);
                check(afterCheckpoint.durable() && afterCheckpoint.version()==10002,
                      "bounded journal failed to resume after snapshot");
                check(commands.loadVerified(countId,1).events().size()==1,
                      "post-checkpoint verified restore did not replay one event");
            }
            System.out.println("PASS Java JDBC 10k-event and 16MiB admission boundaries");
        } finally {
            try(var paths=Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path->{
                    try { Files.deleteIfExists(path); }
                    catch(Exception failure) { throw new IllegalStateException(failure); }
                });
            }
        }
    }
}
