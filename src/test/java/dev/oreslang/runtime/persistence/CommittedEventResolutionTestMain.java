package dev.oreslang.runtime.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.*;

/**
 * Direct trusted SQLite provider integration: not guest actor execution.
 * Checks read-only cross-epoch event identity and writer fencing.
 */
public final class CommittedEventResolutionTestMain {
    private CommittedEventResolutionTestMain() {}
    private static void check(boolean okay, String reason) {
        if (!okay) throw new AssertionError(reason);
    }
    private static boolean denied(Runnable work) {
        try { work.run(); return false; }
        catch (SecurityException expected) { return true; }
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("atto-event-lookup-");
        try {
            Path database = dir.resolve("event.sqlite");
            Identity bank = new Identity("tenant-a","bank-42","main");
            byte[] event = new byte[]{42,13};
            try (SqlitePersistenceStore store = new SqlitePersistenceStore(database)) {
                store.grantEpoch(bank.tenant(),bank.actor(),1);
                var w = new Write(bank,"op-42",1,0,1,Kind.EVENT,event);
                var receipts=store.commit(List.of(w));
                check(receipts.size()==1 && receipts.get(0).durable()
                    && receipts.get(0).status()==Status.COMMITTED,"initial event commit failed");
                store.grantEpoch(bank.tenant(),bank.actor(),2);

                var prior=store.resolveCommittedEventFenced(bank,2,"op-42",1,event);
                check(prior.isPresent() && prior.get().version()==1
                    && prior.get().schemaVersion()==1
                    && Arrays.equals(prior.get().payload(),event),
                    "cross-epoch event lookup did not resolve original bytes");
                prior.get().payload()[0]=99; // no mutable alias into provider data.
                check(Arrays.equals(store.resolveCommittedEventFenced(
                    bank,2,"op-42",1,event).orElseThrow().payload(),event),
                    "lookup leaked mutable storage alias");
                check(store.resolveCommittedEventFenced(bank,2,"never-issued",1,event).isEmpty(),
                    "unknown operation was treated as committed");
                check(denied(()->{
                    try {store.resolveCommittedEventFenced(bank,1,"op-42",1,event);}
                    catch (java.sql.SQLException sql) {throw new AssertionError(sql);}
                }),"stale actor incarnation was allowed to inspect durable operation");
                check(denied(()->{
                    try {store.resolveCommittedEventFenced(bank,2,"op-42",1,new byte[]{42,14});}
                    catch (java.sql.SQLException sql) {throw new AssertionError(sql);}
                }),"altered operation event payload was accepted");
                check(denied(()->{
                    try {store.resolveCommittedEventFenced(bank,2,"op-42",2,event);}
                    catch (java.sql.SQLException sql) {throw new AssertionError(sql);}
                }),"changed schema was accepted");
                var retry=store.commit(List.of(new Write(bank,"op-42",2,0,1,Kind.EVENT,event)));
                check(retry.size()==1 && retry.get(0).status()==Status.OP_ID_CONFLICT
                    && !retry.get(0).durable(),"cross-epoch replay re-appended event");
                check(store.loadFenced(bank,2).events().size()==1,
                    "journal grew after duplicate operation");
                store.grantEpoch("tenant-a","another-bank",1);
                check(store.resolveCommittedEventFenced(
                    new Identity("tenant-a","another-bank","main"),1,"op-42",1,event).isEmpty(),
                    "operation proof escaped actor identity");
            }
            System.out.println("PASS trusted host cross-epoch event proof and fencing (NOT guest retry)");
        } finally {
            try (var entries=Files.walk(dir)) {
                entries.sorted(Comparator.reverseOrder()).forEach(p->{
                    try {Files.deleteIfExists(p);}
                    catch (Exception failure) {throw new IllegalStateException(failure);}
                });
            }
        }
    }
}
