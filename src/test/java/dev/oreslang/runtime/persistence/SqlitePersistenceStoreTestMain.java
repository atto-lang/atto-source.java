package dev.oreslang.runtime.persistence;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.*;

/** Real SQLite/JDBC provider integration and subprocess crash/restart test. */
public final class SqlitePersistenceStoreTestMain {
    private static void check(boolean okay,String reason) {if(!okay)throw new AssertionError(reason);}
    private static Write write(String actor,String operation,long epoch,long version,Kind kind,int value) {
        return new Write(new Identity("tenant-a",actor,"main"),operation,epoch,version,1,kind,new byte[]{(byte)value});
    }
    public static void main(String[] args) throws Exception {
        if(args.length==2 && args[0].equals("child")){
            try(var store=new SqlitePersistenceStore(Path.of(args[1]))){
                store.grantEpoch("tenant-a","child",1);
                var result=store.commit(List.of(write("child","committed",1,0,Kind.SNAPSHOT,77)));
                check(result.size()==1 && result.get(0).durable(),"child write was not durable");
                // Never run shutdown hooks or close the connection. Simulate process loss.
                Runtime.getRuntime().halt(0);
            }
            return;
        }
        Path dir=Files.createTempDirectory("oreslang-java-sqlite-");
        Path file=dir.resolve("tenant.db");
        try {
            try(var store=new SqlitePersistenceStore(file);
                var sidecar=new BatchPersistenceSidecar("tenant-a",store,2000,1<<20,1024,256,1<<16,5000)){
                // Direct trusted-host epoch calls must not bypass the same
                // actor-key Unicode/canonicalization checks as Write admission.
                for (String invalid : new String[] {
                        "e" + (char)0x0301,
                        new String(new char[]{(char)0xD800}),
                        new String(new char[]{(char)0xDC00}) }) {
                    boolean rejected = false;
                    try { store.grantEpoch("tenant-a", invalid, 1); }
                    catch (IllegalArgumentException expected) { rejected = true; }
                    check(rejected, "direct SQLite epoch gate accepted noncanonical identity");
                }
                List<CompletionStage<Result>> results=new ArrayList<>();
                for(int i=0;i<1000;i++){
                    String actor="a"+i;
                    store.grantEpoch("tenant-a",actor,1);
                    results.add(sidecar.submit(write(actor,"op"+i,1,0,Kind.SNAPSHOT,42)));
                }
                sidecar.drain().toCompletableFuture().get(30,TimeUnit.SECONDS);
                for(var result:results)check(result.toCompletableFuture().get().durable(),"uncommitted actor state");
            }
            try(var store=new SqlitePersistenceStore(file)){
                var restored=store.loadFenced(new Identity("tenant-a","a999","main"),1);
                check(restored.version()==1 && restored.snapshot()[0]==42,"reopen lost snapshot");
                Write original=write("a999","op999",1,0,Kind.SNAPSHOT,42);
                Result committedAgain=store.commit(List.of(original)).get(0);
                check(committedAgain.status()==Status.DUPLICATE && committedAgain.durable(),
                        "dedupe lost or falsely nondurable");
                check(store.verifyDurableCommit(original,committedAgain),
                        "exact durable SQLite operation proof failed");
                check(!store.verifyDurableCommit(
                        write("a999","op999",1,0,Kind.SNAPSHOT,43),committedAgain),
                        "mutated payload was accepted as durable proof");
                check(!store.verifyDurableCommit(
                        write("a999","op999",1,1,Kind.SNAPSHOT,42),committedAgain),
                        "wrong expected version was accepted as durable proof");
                store.grantEpoch("tenant-a","a999",2);
                check(!store.verifyDurableCommit(original,committedAgain),
                        "stale epoch re-used old durable proof");
                boolean staleReadDenied=false;
                try { store.loadFenced(new Identity("tenant-a","a999","main"),1); }
                catch (SecurityException expected) { staleReadDenied=true; }
                check(staleReadDenied,"stale incarnation bypassed fenced recovery read");
                check(store.loadFenced(new Identity("tenant-a","a999","main"),2).version()==1,
                        "new incarnation could not restore after epoch rotation");
                check(store.commit(List.of(write("a999","op999",1,0,Kind.SNAPSHOT,42))).get(0).status()==Status.STALE_EPOCH,"fence bypassed");
                check(store.commit(List.of(write("a999","event",2,1,Kind.EVENT,65))).get(0).durable(),"journal commit not durable");
                var replay=store.load(new Identity("tenant-a","a999","main"));
                check(replay.version()==2 && replay.snapshotVersion()==1 && replay.events().size()==1 && replay.events().get(0).payload()[0]==65,"journal replay mismatch");
                store.grantEpoch("tenant-a","event-only",1);
                check(store.commit(List.of(write("event-only","first-event",1,0,Kind.EVENT,99))).get(0).durable(),
                    "event-first write failed");
                var eventOnly=store.load(new Identity("tenant-a","event-only","main"));
                check(eventOnly.version()==1 && eventOnly.snapshotVersion()==0 &&
                    eventOnly.snapshot().length==0 && eventOnly.events().size()==1 &&
                    eventOnly.events().get(0).payload()[0]==99,"event-first replay invented snapshot");
                store.grantEpoch("tenant-a","a",1);store.grantEpoch("tenant-a","b",1);
                var mixed=store.commit(List.of(write("a","conflict",1,0,Kind.SNAPSHOT,1),write("b","first",1,0,Kind.SNAPSHOT,9)));
                check(mixed.get(0).status()==Status.VERSION_CONFLICT && mixed.get(1).durable(),"cross-actor isolation failure");
            }
            // Corrupt the journal deliberately: never silently restore a
            // partial committed event stream as if it were complete state.
            try(var store=new SqlitePersistenceStore(file)) {
                store.grantEpoch("tenant-a","tampered",1);
                check(store.commit(List.of(write("tampered","evt1",1,0,Kind.EVENT,70),
                    write("tampered","evt2",1,1,Kind.EVENT,71))).stream()
                    .allMatch(Result::durable),"tamper fixture did not commit");
            }
            try(var raw=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);
                var statement=raw.createStatement()) {
                check(statement.executeUpdate(
                    "DELETE FROM events WHERE tenant='tenant-a' AND actor='tampered' AND version=1")==1,
                    "tamper setup failed");
            }
            try(var store=new SqlitePersistenceStore(file)) {
                boolean rejected=false;
                try { store.loadFenced(new Identity("tenant-a","tampered","main"),1); }
                catch (java.sql.SQLException expected) {
                    rejected=expected.getMessage().contains("INCOMPLETE_ACTOR_JOURNAL");
                }
                check(rejected,"missing committed event did not block actor restoration");
                Write missing=write("tampered","evt1",1,0,Kind.EVENT,70);
                Result oldReceipt=new Result("evt1",Status.DUPLICATE,1,true);
                check(!store.verifyDurableCommit(missing,oldReceipt),
                        "missing event was accepted as durable from operation ledger alone");
                boolean duplicateBlocked=false;
                try { store.commit(List.of(missing)); }
                catch(java.sql.SQLException expected) {
                    duplicateBlocked=expected.toString().contains("INCOMPLETE_OPERATION_LEDGER");
                }
                check(duplicateBlocked,"missing event received a direct durable duplicate ACK");
                boolean checkpointBlocked=false;
                try {store.commit(List.of(write("tampered","unsafe-checkpoint",
                        1,2,Kind.SNAPSHOT,9)));}
                catch(java.sql.SQLException expected) {
                    checkpointBlocked=expected.toString().contains("INCOMPLETE_ACTOR_JOURNAL");
                }
                check(checkpointBlocked,"missing event was laundered behind a new checkpoint");
            }
            // A byte-compatible operation record does not prove that the
            // actual event BLOB has not been rewritten after the commit.
            try(var store=new SqlitePersistenceStore(file)) {
                store.grantEpoch("tenant-a","proof-mutated",1);
                check(store.commit(List.of(write("proof-mutated","event",1,0,
                        Kind.EVENT,7))).get(0).durable(),"proof mutation fixture");
            }
            try(var raw=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);
                var statement=raw.createStatement()) {
                check(statement.executeUpdate(
                    "UPDATE events SET payload=x'63' WHERE actor='proof-mutated' " +
                    "AND version=1")==1,"event rewrite fixture");
            }
            try(var store=new SqlitePersistenceStore(file)) {
                check(!store.verifyDurableCommit(
                        write("proof-mutated","event",1,0,Kind.EVENT,7),
                        new Result("event",Status.DUPLICATE,1,true)),
                        "rewritten event was accepted as durable proof");
                boolean duplicateBlocked=false;
                try { store.commit(List.of(
                        write("proof-mutated","event",1,0,Kind.EVENT,7))); }
                catch(java.sql.SQLException expected) {
                    duplicateBlocked=expected.toString().contains("INCOMPLETE_OPERATION_LEDGER");
                }
                check(duplicateBlocked,"altered event received a direct durable duplicate ACK");
                boolean replayDenied=false;
                try { store.loadFenced(new Identity("tenant-a","proof-mutated","main"),1); }
                catch(java.sql.SQLException expected) {
                    replayDenied=expected.toString().contains("UNVERIFIED_ACTOR_EVENT");
                }
                check(replayDenied,"same-size rewritten event replayed without operation proof");
                boolean checkpointBlocked=false;
                try {store.commit(List.of(write("proof-mutated","unsafe-checkpoint",
                        1,1,Kind.SNAPSHOT,42)));}
                catch(java.sql.SQLException expected) {
                    checkpointBlocked=expected.toString().contains("UNVERIFIED_ACTOR_EVENT");
                }
                check(checkpointBlocked,"rewritten event was laundered behind a new checkpoint");
            }
            // Correct payload bytes do not excuse a rewritten origin
            // operation version. This is rejected even after epoch changes.
            try(var store=new SqlitePersistenceStore(file)) {
                store.grantEpoch("tenant-a","metadata-event",1);
                check(store.commit(List.of(write("metadata-event","event",1,0,
                        Kind.EVENT,11))).get(0).durable(),
                        "event metadata proof fixture");
                store.grantEpoch("tenant-a","metadata-event",2);
            }
            try(var raw=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);
                var statement=raw.createStatement()) {
                check(statement.executeUpdate(
                    "UPDATE ops SET expected=99 WHERE actor='metadata-event' " +
                    "AND slot='main' AND op='event'")==1,
                    "event expected-version tamper fixture");
            }
            try(var store=new SqlitePersistenceStore(file)) {
                boolean denied=false;
                try { store.loadFenced(new Identity("tenant-a","metadata-event","main"),2); }
                catch(java.sql.SQLException expected) {
                    denied=expected.toString().contains("UNVERIFIED_ACTOR_EVENT");
                }
                check(denied,"rewritten legacy expected version was trusted on recovery");
            }
            try(var store=new SqlitePersistenceStore(file)) {
                store.grantEpoch("tenant-a","metadata-snapshot",1);
                check(store.commit(List.of(write("metadata-snapshot","checkpoint",1,0,
                        Kind.SNAPSHOT,21))).get(0).durable(),
                        "snapshot metadata proof fixture");
            }
            try(var raw=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);
                var statement=raw.createStatement()) {
                check(statement.executeUpdate(
                    "UPDATE ops SET epoch=0 WHERE actor='metadata-snapshot' " +
                    "AND slot='main' AND op='checkpoint'")==1,
                    "snapshot origin epoch tamper fixture");
            }
            try(var store=new SqlitePersistenceStore(file)) {
                boolean denied=false;
                try { store.loadFenced(new Identity("tenant-a","metadata-snapshot","main"),1); }
                catch(java.sql.SQLException expected) {
                    denied=expected.toString().contains("UNVERIFIED_ACTOR_SNAPSHOT");
                }
                check(denied,"rewritten snapshot origin epoch was trusted on recovery");
            }
            // A correct-size altered snapshot is also forbidden. Length and
            // stream contiguity alone are not a sufficient recovery witness.
            try(var store=new SqlitePersistenceStore(file)) {
                store.grantEpoch("tenant-a","proof-snapshot",1);
                check(store.commit(List.of(write("proof-snapshot","checkpoint",1,0,
                        Kind.SNAPSHOT,21))).get(0).durable(),"snapshot proof fixture");
            }
            try(var raw=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);
                var statement=raw.createStatement()) {
                check(statement.executeUpdate(
                    "UPDATE state SET snapshot=x'2A' WHERE actor='proof-snapshot'")==1,
                    "snapshot rewrite fixture");
            }
            try(var store=new SqlitePersistenceStore(file)) {
                boolean replayDenied=false;
                try { store.loadFenced(new Identity("tenant-a","proof-snapshot","main"),1); }
                catch(java.sql.SQLException expected) {
                    replayDenied=expected.toString().contains("UNVERIFIED_ACTOR_SNAPSHOT");
                }
                check(replayDenied,"same-size rewritten snapshot replayed without operation proof");
            }
            // Corrupted snapshots and events must abort fenced reactivation.
            try(var store=new SqlitePersistenceStore(file)) {
                for(String actor:List.of("snapshot-null","snapshot-huge","event-huge"))
                    store.grantEpoch("tenant-a",actor,1);
                check(store.commit(List.of(
                    write("snapshot-null","one",1,0,Kind.SNAPSHOT,5),
                    write("snapshot-huge","one",1,0,Kind.SNAPSHOT,6),
                    write("event-huge","one",1,0,Kind.EVENT,7)))
                    .stream().allMatch(Result::durable),"corruption fixture not durably written");
            }
            try(var raw=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);
                var statement=raw.createStatement()) {
                check(statement.executeUpdate("UPDATE state SET snapshot=NULL WHERE actor='snapshot-null'")==1,
                    "snapshot NULL fixture failed");
                check(statement.executeUpdate("UPDATE state SET snapshot=zeroblob(1048577) WHERE actor='snapshot-huge'")==1,
                    "oversized snapshot fixture failed");
                check(statement.executeUpdate("UPDATE events SET payload=zeroblob(1048577) WHERE actor='event-huge'")==1,
                    "oversized event fixture failed");
            }
            try(var store=new SqlitePersistenceStore(file)) {
                for(String actor:List.of("snapshot-null","snapshot-huge","event-huge")) {
                    boolean rejected=false;
                    try { store.loadFenced(new Identity("tenant-a",actor,"main"),1); }
                    catch(java.sql.SQLException expected) {
                        rejected=expected.getMessage().startsWith("INVALID_ACTOR_") ||
                            expected.getMessage().contains("REPLAY_BYTES_LIMIT");
                    }
                    check(rejected,"malformed persisted actor state was published: "+actor);
                }
            }
            // A future event row beyond the durable head is also a
            // corruption signal. Never interpret COUNT(*) as spare capacity
            // or advance the actor head around the forged version.
            try(var store=new SqlitePersistenceStore(file)) {
                store.grantEpoch("tenant-a","future-row",1);
                check(store.commit(List.of(write("future-row","event-1",
                        1,0,Kind.EVENT,4))).get(0).durable(),
                        "future-row setup failed");
            }
            try(var raw=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);
                var statement=raw.createStatement()) {
                check(statement.executeUpdate(
                    "INSERT INTO events(tenant,actor,slot,version,schema,payload) " +
                    "VALUES('tenant-a','future-row','main',3,1,x'09')")==1,
                    "future-row corruption injection failed");
            }
            try(var store=new SqlitePersistenceStore(file)) {
                boolean denied=false;
                try {store.commit(List.of(write("future-row","event-2",
                        1,1,Kind.EVENT,5)));}
                catch(java.sql.SQLException expected) {
                    denied=expected.toString().contains("INCOMPLETE_ACTOR_JOURNAL");
                }
                check(denied,"future-version event row bypassed replay admission");
            }
            // Historical legacy operation IDs remain durable after a
            // verified checkpoint permits compaction and a supervisor
            // advances to a new epoch. The old actor stays fenced.
            try(var store=new SqlitePersistenceStore(file)) {
                store.grantEpoch("tenant-a","compacted-legacy",1);
                check(store.commit(List.of(write("compacted-legacy","event-1",
                        1,0,Kind.EVENT,40))).get(0).durable(),
                        "compacted legacy event setup");
                check(store.commit(List.of(write("compacted-legacy","snapshot-2",
                        1,1,Kind.SNAPSHOT,40))).get(0).durable(),
                        "compacted legacy checkpoint setup");
            }
            try(var raw=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);
                var statement=raw.createStatement()) {
                check(statement.executeUpdate(
                    "DELETE FROM events WHERE actor='compacted-legacy' AND version=1")==1,
                    "legacy event compaction setup");
            }
            try(var store=new SqlitePersistenceStore(file)) {
                store.grantEpoch("tenant-a","compacted-legacy",2);
                Write retry=write("compacted-legacy","event-1",2,0,Kind.EVENT,40);
                Result receipt=store.commit(List.of(retry)).get(0);
                check(receipt.status()==Status.DUPLICATE && receipt.durable() &&
                      receipt.version()==1,"compacted event lost durable idempotency");
                check(store.verifyDurableCommit(retry,receipt),
                      "valid new epoch could not prove archived original event");
                check(!store.verifyDurableCommit(
                        write("compacted-legacy","event-1",1,0,Kind.EVENT,40),receipt),
                        "stale actor epoch re-used archived proof");
                Result changed=store.commit(List.of(write("compacted-legacy",
                        "event-1",2,0,Kind.EVENT,41))).get(0);
                check(changed.status()==Status.OP_ID_CONFLICT && !changed.durable(),
                      "altered historical payload received a durable ACK");
                check(store.loadFenced(new Identity("tenant-a","compacted-legacy","main"),2)
                        .snapshotVersion()==2,"compacted checkpoint not restored");
            }
            // A newer checkpoint also covers the durable identity of its
            // superseded predecessor, but only with original op metadata.
            try(var store=new SqlitePersistenceStore(file)) {
                store.grantEpoch("tenant-a","superseded-legacy",1);
                check(store.commit(List.of(write("superseded-legacy","snapshot-old",
                        1,0,Kind.SNAPSHOT,11))).get(0).durable(),
                        "first snapshot setup");
                check(store.commit(List.of(write("superseded-legacy","snapshot-new",
                        1,1,Kind.SNAPSHOT,12))).get(0).durable(),
                        "second snapshot setup");
                store.grantEpoch("tenant-a","superseded-legacy",2);
                Write retry=write("superseded-legacy","snapshot-old",2,0,Kind.SNAPSHOT,11);
                Result r=store.commit(List.of(retry)).get(0);
                check(r.status()==Status.DUPLICATE && r.durable() && r.version()==1,
                      "superseded checkpoint incorrectly lost operation identity");
                check(store.verifyDurableCommit(retry,r),
                      "newer checkpoint failed to prove historic snapshot");
            }
            // The compaction fallback must never turn a corrupted active
            // snapshot into proof for a deleted legacy event.
            try(var store=new SqlitePersistenceStore(file)) {
                store.grantEpoch("tenant-a","compacted-corrupt",1);
                check(store.commit(List.of(write("compacted-corrupt","old-event",
                        1,0,Kind.EVENT,17))).get(0).durable(),
                        "corrupt compacted event setup");
                check(store.commit(List.of(write("compacted-corrupt","checkpoint",
                        1,1,Kind.SNAPSHOT,17))).get(0).durable(),
                        "corrupt compacted checkpoint setup");
            }
            try(var raw=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);
                var statement=raw.createStatement()) {
                check(statement.executeUpdate(
                    "DELETE FROM events WHERE actor='compacted-corrupt' AND version=1")==1,
                    "corrupt compacted delete setup");
                check(statement.executeUpdate(
                    "UPDATE state SET snapshot=x'12' WHERE actor='compacted-corrupt'")==1,
                    "corrupt compacted snapshot mutation setup");
            }
            try(var store=new SqlitePersistenceStore(file)) {
                store.grantEpoch("tenant-a","compacted-corrupt",2);
                boolean denied=false;
                try {store.commit(List.of(write("compacted-corrupt","old-event",
                        2,0,Kind.EVENT,17)));}
                catch(java.sql.SQLException expected) {
                    denied=expected.toString().contains("UNVERIFIED_ACTOR_SNAPSHOT") ||
                           expected.toString().contains("CORRUPT_SNAPSHOT_PROOF");
                }
                check(denied,"corrupted checkpoint authenticated compacted duplicate");
            }
            Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",System.getProperty("java.class.path"),SqlitePersistenceStoreTestMain.class.getName(),"child",file.toString())
                .redirectErrorStream(true).start();
            boolean exited=child.waitFor(15,TimeUnit.SECONDS);
            if(!exited)child.destroyForcibly();
            check(exited && child.exitValue()==0,"abrupt child did not commit");
            try(var store=new SqlitePersistenceStore(file)){
                var childState=store.load(new Identity("tenant-a","child","main"));
                check(childState.version()==1 && childState.snapshot()[0]==77,"abrupt exit lost confirmed state");
            }
            System.out.println("PASS Java SQLite WAL/FULL provider (1000 actors, reopen, journal, fencing, abrupt halt)");
        } finally {
            try(var paths=Files.walk(dir)){
                paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception e){throw new RuntimeException(e);}});
            }
        }
    }
}
