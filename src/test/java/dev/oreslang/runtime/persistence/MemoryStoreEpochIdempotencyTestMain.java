package dev.oreslang.runtime.persistence;

import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.*;
import java.util.List;

/**
 * Non-durable reference store test. This only checks logical/fenced retry
 * semantics, never proof of disk persistence or a durable ACK.
 */
public final class MemoryStoreEpochIdempotencyTestMain {
    private MemoryStoreEpochIdempotencyTestMain() {}
    private static void check(boolean okay,String why) {
        if(!okay)throw new AssertionError(why);
    }
    private static Write event(Identity id,String op,long epoch,long expected,int n) {
        return new Write(id,op,epoch,expected,1,Kind.EVENT,new byte[]{(byte)n});
    }
    public static void main(String[] args) throws Exception {
        Identity id=new Identity("t-memory","actor","ledger");
        var memory=new BatchPersistenceSidecar.MemoryStore();
        memory.grantEpoch(id.tenant(),id.actor(),1);
        Result first=memory.commit(List.of(event(id,"deposit",1,0,40))).get(0);
        check(first.status()==Status.COMMITTED && !first.durable() &&
              first.version()==1,"initial memory model commit failed");
        memory.grantEpoch(id.tenant(),id.actor(),2);
        check(memory.commit(List.of(event(id,"deposit",1,0,40))).get(0)
              .status()==Status.STALE_EPOCH,
              "stale actor read a durable operation result");
        Result retry=memory.commit(List.of(event(id,"deposit",2,0,40))).get(0);
        check(retry.status()==Status.DUPLICATE && !retry.durable() &&
              retry.version()==1,"new actor epoch double-applied logical operation");
        check(memory.commit(List.of(event(id,"deposit",2,0,41))).get(0)
              .status()==Status.OP_ID_CONFLICT,
              "changed historical payload reused an operation ID");
        check(memory.version(id)==1,"dedupe advanced actor head");
        check(memory.commit(List.of(event(id,"new-operation",2,1,20))).get(0)
              .status()==Status.COMMITTED &&
              memory.version(id)==2,"unrelated operation could not advance");
        check(!memory.verifyDurableCommit(event(id,"deposit",2,0,40),retry),
              "non-durable model falsely certified disk persistence");
        System.out.println("PASS memory reference actor epoch2 logical dedupe; NO durable ACK");
    }
}
