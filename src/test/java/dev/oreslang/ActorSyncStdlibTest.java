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

// Source-level .ores stdlib execution; NOT native durable storage or actor
// activation, and NOT JVM-only host stubs standing in for guest semantics.
final class ActorSyncStdlibTest {
    @TempDir Path temp;

    private String run(String source) throws Exception {
        Path entry=temp.resolve("sync-case.ores");
        Files.writeString(entry,source);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
            ExecutionProfile.serverJit(), Set.of(), Map.of(),
            output, new ByteArrayOutputStream());
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void unknownCommitOutcomePreservesIdempotencyReservation() throws Exception {
        assertEquals("""
                true
                true
                false
                true
                false
                false
                false
                true
                6
                false
                false
                """, run("""
                import * as sync from "std/actor_sync";
                pub routine main(): void {
                  let w=sync.commit_window(2,5);
                  stdio.println(w.begin(2,5,"req"));
                  stdio.println(w.pending());
                  stdio.println(w.begin(2,5,"other"));
                  stdio.println(w.outcome_unknown(2,"req"));
                  stdio.println(w.abort_known_failure(2,"req"));
                  stdio.println(w.acknowledge(1,"req",6));
                  stdio.println(w.acknowledge(2,"req",8));
                  stdio.println(w.acknowledge(2,"req",6));
                  stdio.println(w.committed());
                  stdio.println(w.pending());
                  stdio.println(w.unknown());
                  return;
                }
                """));
    }

    @Test
    void authoritativeReconciliationIsExplicitAndGenerationChecked() throws Exception {
        assertEquals("""
                true
                true
                false
                false
                true
                false
                true
                true
                false
                """, run("""
                import * as sync from "std/actor_sync";
                pub routine main(): void {
                  let w=sync.commit_window(7,0);
                  stdio.println(w.begin(7,0,"one"));
                  stdio.println(w.outcome_unknown(7,"one"));
                  stdio.println(w.reconcile_not_committed(6,"one"));
                  stdio.println(w.reconcile_not_committed(7,"other"));
                  stdio.println(w.reconcile_not_committed(7,"one"));
                  stdio.println(w.pending());
                  stdio.println(w.begin(7,0,"two"));
                  stdio.println(w.abort_known_failure(7,"two"));
                  stdio.println(w.pending());
                  return;
                }
                """));
    }

    @Test
    void gapBlocksDeltasUntilTrustedSnapshotAndFencesOldEpoch() throws Exception {
        assertEquals("""
                false
                true
                false
                true
                false
                true
                false
                false
                true
                false
                4
                8
                """, run("""
                import * as sync from "std/actor_sync";
                pub routine main(): void {
                  let c=sync.replica_cursor(3,5);
                  stdio.println(c.apply_delta(3,7));
                  stdio.println(c.needs_resync());
                  stdio.println(c.apply_delta(3,6));
                  stdio.println(c.apply_snapshot(3,7));
                  stdio.println(c.needs_resync());
                  stdio.println(c.apply_delta(3,8));
                  stdio.println(c.apply_delta(2,9));
                  stdio.println(c.apply_snapshot(2,10));
                  stdio.println(c.apply_snapshot(4,8));
                  stdio.println(c.apply_snapshot(4,8));
                  stdio.println(c.owner_epoch());
                  stdio.println(c.applied());
                  return;
                }
                """));
    }

    @Test
    void independentReplicaCursorsDoNotShareMutableState() throws Exception {
        assertEquals("""
                true
                false
                10
                9
                """,run("""
                import * as sync from "std/actor_sync";
                pub routine main(): void {
                  let a=sync.replica_cursor(1,9);
                  let b=sync.replica_cursor(1,9);
                  stdio.println(a.apply_delta(1,10));
                  stdio.println(b.apply_delta(1,12));
                  stdio.println(a.applied());
                  stdio.println(b.applied());
                  return;
                }
                """));
    }

    @Test
    void versionExhaustionFailsWithoutWrapping() throws Exception {
        assertEquals("""
                false
                2147483646
                false
                2147483646
                """,run("""
                import * as sync from "std/actor_sync";
                pub routine main(): void {
                  let w=sync.commit_window(1,2147483646);
                  let c=sync.replica_cursor(1,2147483646);
                  stdio.println(w.begin(1,2147483646,"last"));
                  stdio.println(w.committed());
                  stdio.println(c.apply_delta(1,2147483647));
                  stdio.println(c.applied());
                  return;
                }
                """));
    }

    @Test
    void canonicalPortableSourceFixtureExecutesWithExactOutput() throws Exception {
        String fixture=Files.readString(Path.of(
            "src/test/resources/actor-sync-v1/cursor-progression.ores"));
        assertEquals("""
                true
                true
                false
                true
                true
                false
                true
                true
                3
                5
                """,run(fixture));
    }


    @Test
    void canonicalCrossEpochFixtureRunsWithoutChangingSourceBytes() throws Exception {
        String fixture=Files.readString(Path.of(
            "src/test/resources/actor-sync-v1/cross-epoch-delta-reject.ores"));
        assertEquals("""
                false
                false
                5
                true
                true
                7
                5
                """,run(fixture));
    }

    @Test
    void crossEpochDeltaRequiresSnapshotBeforeReplicaCanAdvance() throws Exception {
        assertEquals("""
                false
                false
                5
                true
                true
                7
                5
                """,run("""
                import * as sync from "std/actor_sync";
                pub routine main(): void {
                  let c=sync.replica_cursor(4,5);
                  stdio.println(c.apply_delta(5,6));
                  stdio.println(c.needs_resync());
                  stdio.println(c.applied());
                  stdio.println(c.apply_snapshot(5,6));
                  stdio.println(c.apply_delta(5,7));
                  stdio.println(c.applied());
                  stdio.println(c.owner_epoch());
                  return;
                }
                """));
    }

    @Test
    void realOreslangIsoactorCanUseOwnedSyncProgressDuringActorTurn() throws Exception {
        assertEquals("1",run("""
                import * as sync from "std/actor_sync";
                define isoactor SyncWorker as
                  let int version;
                  on_start(): void {
                    self.version = 0;
                    return;
                  }
                  receive(ActorMail<String> mail): void {
                    let window = sync.commit_window(1,self.version);
                    if window.begin(1,self.version,mail.value); do
                      if window.acknowledge(1,mail.value,self.version+1); do
                        self.version = window.committed();
                        self.send(self.version);
                      fi
                    fi
                    self.end();
                    return;
                  }
                end
                pub suspends routine main(): void {
                  val worker = spawn SyncWorker();
                  await worker.get_ready();
                  worker.send("op1");
                  val reply = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(reply.value);
                  await worker.get_done();
                  return;
                }
                """));
    }

    @Test
    void invalidGenerationsAndRevisionsFailClosed() {
        assertThrows(PolyglotException.class,()->run("""
                import * as sync from "std/actor_sync";
                pub routine main(): void { let w=sync.commit_window(0,0); return; }
                """));
        assertThrows(PolyglotException.class,()->run("""
                import * as sync from "std/actor_sync";
                pub routine main(): void { let c=sync.replica_cursor(1,-1); return; }
                """));
        assertThrows(PolyglotException.class,()->run("""
                import * as sync from "std/actor_sync";
                pub routine main(): void { let c=sync.replica_cursor(1,2147483647); return; }
                """));
    }
}
