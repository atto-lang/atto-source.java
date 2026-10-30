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
