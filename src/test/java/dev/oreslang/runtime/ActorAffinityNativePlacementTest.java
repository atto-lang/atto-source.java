package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class ActorAffinityNativePlacementTest {
    @Test
    void nativeLanesStartOnOffsetConfiguredCpusBeforeWorkRuns() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(os.contains("linux"));
        int[] allowed = allowedLinuxCpus();
        assumeTrue(allowed.length >= 2);
        int[] cpus = {allowed[0], allowed[1]};
        long offset = 1L;

        try (var dispatcher = new ActorAffinityDispatcher(
                2,
                16,
                cpus,
                offset,
                lane -> new NativeCarrierExecutor(
                        1,
                        1,
                        16,
                        "ores-native-affinity-lane-" + lane + "-",
                        ActorCpuAffinityConfig.cpuForLane(cpus, offset, lane)))) {
            CountDownLatch done = new CountDownLatch(2);
            AtomicInteger lane0 = new AtomicInteger(-1);
            AtomicInteger lane1 = new AtomicInteger(-1);

            dispatcher.executeOnLane(0, () -> {
                lane0.set(NativeCarrierExecutor.currentCpu());
                done.countDown();
            });
            dispatcher.executeOnLane(1, () -> {
                lane1.set(NativeCarrierExecutor.currentCpu());
                done.countDown();
            });

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(cpus[1], lane0.get());
            assertEquals(cpus[0], lane1.get());
        }
    }

    private static int[] allowedLinuxCpus() throws Exception {
        String status = Files.readString(Path.of("/proc/self/status"));
        String line = status.lines()
                .filter(value -> value.startsWith("Cpus_allowed_list:"))
                .findFirst()
                .orElseThrow();
        String spec = line.substring(line.indexOf(':') + 1).trim();
        ArrayList<Integer> cpus = new ArrayList<>();
        for (String part : spec.split(",")) {
            String[] range = part.split("-");
            int first = Integer.parseInt(range[0]);
            int last = range.length == 1 ? first : Integer.parseInt(range[1]);
            for (int cpu = first; cpu <= last; cpu++) cpus.add(cpu);
        }
        return cpus.stream().mapToInt(Integer::intValue).toArray();
    }
}
