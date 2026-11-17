package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ActorDatabaseStdlibTest {
    @TempDir Path temp;

    private String run(String content) throws Exception {
        Path entry = temp.resolve("app.ores");
        Files.writeString(entry, content);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out,
                new ByteArrayOutputStream());
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void poolAdmissionIsBoundedAndReleaseUnderflowCannotMutateCounters() throws Exception {
        assertEquals("""
                true
                false
                true
                false
                false
                2
                6
                true
                true
                2
                6
                """, run("""
                import * as pool from "std/db_pool";
                pub routine main(): void {
                  let budget = pool.admission_budget(2, 6, 4);
                  stdio.println(budget.try_acquire(4));
                  stdio.println(budget.try_acquire(3));
                  stdio.println(budget.try_acquire(2));
                  stdio.println(budget.try_acquire(1));
                  stdio.println(budget.release(7));
                  stdio.println(budget.active());
                  stdio.println(budget.bytes_in_flight());
                  stdio.println(budget.release(4));
                  stdio.println(budget.try_acquire(4));
                  stdio.println(budget.active());
                  stdio.println(budget.bytes_in_flight());
                  return;
                }
                """));
    }

    @Test
    void protocolGateDoesNotOverlapTransactionsAndQuarantinedConnections() throws Exception {
        assertEquals("""
                true
                true
                false
                false
                true
                true
                true
                false
                true
                true
                false
                """, run("""
                import * as pool from "std/db_pool";
                pub routine main(): void {
                  let gate = pool.session_gate(2);
                  stdio.println(gate.begin_query());
                  stdio.println(gate.begin_query());
                  stdio.println(gate.begin_query());
                  stdio.println(gate.begin_transaction());
                  stdio.println(gate.complete_query(true));
                  stdio.println(gate.complete_query(true));
                  stdio.println(gate.begin_transaction());
                  stdio.println(gate.begin_query());
                  stdio.println(gate.end_transaction(false));
                  stdio.println(gate.is_quarantined());
                  stdio.println(gate.begin_query());
                  return;
                }
                """));
    }

    @Test
    void staleGenerationAcknowledgementsAndObsoleteFlushesAreRejected() throws Exception {
        assertEquals("""
                false
                6
                6
                7
                false
                true
                6
                7
                false
                true
                7
                true
                false
                """, run("""
                import * as persistence from "std/actor_persistence";
                pub routine main(): void {
                  let cursor = persistence.cursor(11, 5);
                  stdio.println(cursor.dirty());
                  stdio.println(cursor.mark_changed());
                  stdio.println(cursor.begin_flush());
                  stdio.println(cursor.mark_changed());
                  stdio.println(cursor.ack(12, 6));
                  stdio.println(cursor.ack(11, 6));
                  stdio.println(cursor.durable());
                  stdio.println(cursor.begin_flush());
                  stdio.println(cursor.retry(11, 6));
                  stdio.println(cursor.retry(11, 7));
                  stdio.println(cursor.begin_flush());
                  stdio.println(cursor.ack(11, 7));
                  stdio.println(cursor.dirty());
                  return;
                }
                """));
    }

    @Test
    void invalidLimitsAndWritePlansFailClosed() {
        assertThrows(PolyglotException.class, () -> run("""
                import * as pool from "std/db_pool";
                pub routine main(): void {
                  val bad = pool.limits(0, 4, 1024, 64, 1);
                  return;
                }
                """));
        assertThrows(PolyglotException.class, () -> run("""
                import * as persistence from "std/actor_persistence";
                pub routine main(): void {
                  val bad = persistence.plan("a", 1, 5, 5, 1, "op");
                  return;
                }
                """));
    }

    @Test
    void cacheReplicaRejectsStaleGenerationsAndDeltaGapsWithoutClaimingDurability() throws Exception {
        assertEquals("""
                false
                false
                true
                8
                false
                true
                true
                false
                10
                """, run("""
                import * as persistence from "std/actor_persistence";
                pub routine main(): void {
                  let replica = persistence.replica_cursor(4, 5);
                  stdio.println(replica.apply_snapshot(3, 9));
                  stdio.println(replica.apply_delta(4, 7));
                  stdio.println(replica.apply_snapshot(4, 8));
                  stdio.println(replica.applied());
                  stdio.println(replica.apply_snapshot(4, 6));
                  stdio.println(replica.apply_delta(4, 9));
                  stdio.println(replica.apply_delta(4, 10));
                  stdio.println(replica.apply_delta(4, 10));
                  stdio.println(replica.applied());
                  return;
                }
                """));
    }

    @Test
    void requestReceiptRejectsDuplicateSettlementAndStaleGeneration() throws Exception {
        assertEquals("""
                false
                false
                true
                true
                false
                false
                """, run("""
                import * as pool from "std/db_pool";
                pub routine main(): void {
                  let receipt = pool.request_receipt(7, 22, 4096);
                  stdio.println(receipt.settle(6, 22));
                  stdio.println(receipt.settle(7, 23));
                  stdio.println(receipt.settle(7, 22));
                  stdio.println(receipt.is_settled());
                  stdio.println(receipt.settle(7, 22));
                  stdio.println(receipt.settle(8, 22));
                  return;
                }
                """));
    }
}
