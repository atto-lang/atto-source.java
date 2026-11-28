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

final class DataPoolStdlibTest {
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
    void postgresAndRedisCapabilitiesSelectDifferentLanes() throws Exception {
        assertEquals("""
                true
                false
                true
                false
                false
                true
                false
                true
                false
                true
                false
                false
                """, run("""
                import * as pool from "std/data_pool";
                pub routine main(): void {
                  val pg = pool.driver_capabilities(true, true, true, false, false, true);
                  stdio.println(pg.can_serve(1, true));
                  stdio.println(pg.can_serve(2, true));
                  stdio.println(pg.can_serve(2, false));
                  stdio.println(pg.can_serve(3, false));
                  stdio.println(pg.can_serve(4, false));
                  stdio.println(pg.can_share_connection(1));
                  stdio.println(pg.can_share_connection(2));
                  val redis = pool.driver_capabilities(true, true, true, true, true, true);
                  stdio.println(redis.can_serve(3, false));
                  stdio.println(redis.can_serve(3, true));
                  stdio.println(redis.can_serve(4, false));
                  stdio.println(redis.can_share_connection(4));
                  stdio.println(redis.can_serve(99, false));
                  return;
                }
                """));
    }

    @Test
    void retryPolicyRejectsUnknownCommitAndRequiresWholeOperationReplay() throws Exception {
        assertEquals("""
                true
                false
                false
                false
                5
                10
                20
                -1
                1
                -1
                """, run("""
                import * as pool from "std/data_pool";
                pub routine main(): void {
                  val retry = pool.retry_policy(4, 5, 20);
                  stdio.println(retry.may_retry(1, true, true));
                  stdio.println(retry.may_retry(1, false, true));
                  stdio.println(retry.may_retry(1, true, false));
                  stdio.println(retry.may_retry(4, true, true));
                  stdio.println(retry.backoff_ms(1));
                  stdio.println(retry.backoff_ms(2));
                  stdio.println(retry.backoff_ms(3));
                  stdio.println(retry.backoff_ms(4));
                  stdio.println(pool.shard_for(7, 3));
                  stdio.println(pool.shard_for(-1, 3));
                  return;
                }
                """));
    }

    @Test
    void partitionKeyCannotHaveMissingIsolationDimensionsOrInvalidLane() {
        assertThrows(PolyglotException.class, () -> run("""
                import * as pool from "std/data_pool";
                pub routine main(): void {
                  val key = pool.partition("redis", "cache-east", "", "tenant-1",
                                           "tls-1", "resp3:db0", 1);
                  return;
                }
                """));
        assertThrows(PolyglotException.class, () -> run("""
                import * as pool from "std/data_pool";
                pub routine main(): void {
                  val key = pool.partition("pgwire", "db-east", "svc", "tenant-1",
                                           "tls-1", "db:default", 8);
                  return;
                }
                """));
        assertThrows(PolyglotException.class, () -> run("""
                import * as pool from "std/data_pool";
                pub routine main(): void {
                  val retry = pool.retry_policy(0, 5, 20);
                  return;
                }
                """));
    }
}
