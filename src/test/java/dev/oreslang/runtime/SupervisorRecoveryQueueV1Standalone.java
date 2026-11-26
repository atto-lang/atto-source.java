package dev.oreslang.runtime;

import java.lang.management.ManagementFactory;

final class SupervisorRecoveryQueueV1Standalone {
    public static void main(String[] args) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new AssertionError("no allocation counter");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        var warm = new SupervisorRecoveryQueueV1(4);
        for (int i = 0; i < 100_000; i++) {
            if (!warm.offer(1000) || warm.poll() != 1000) throw new AssertionError("warmup");
        }
        // Exercise the identical full-capacity path before measurement,
        // including first-use VM/linkage initialization.
        for (int i = 0; i < 3; i++) {
            if (!exercise()) throw new AssertionError("full path warmup");
        }
        var queueForMeasurement = new SupervisorRecoveryQueueV1(SupervisorFailureEscrowV1.MAX_NODES);
        long before = bean.getThreadAllocatedBytes(thread);
        boolean valid = exerciseMeasured(queueForMeasurement);
        long delta = bean.getThreadAllocatedBytes(thread) - before;
        if (!valid || before < 0 || delta != 0) throw new AssertionError("queue failure / bytes=" + delta);
        System.out.println("PASS recovery queue capacity=4096 reuse=100000 allocated_bytes=0");
    }

    private static boolean exercise() {
        return exerciseMeasured(new SupervisorRecoveryQueueV1(SupervisorFailureEscrowV1.MAX_NODES));
    }

    private static boolean exerciseMeasured(SupervisorRecoveryQueueV1 queue) {
        int capacity = SupervisorFailureEscrowV1.MAX_NODES;
        var reserved = queue.capacityFailure();
        boolean valid = true;
        for (int i = 0; i < capacity; i++) valid &= queue.offer(1000L + i);
        for (int i = 0; i < capacity; i++) valid &= queue.offer(1000L + i);
        valid &= !queue.offer(99999) && queue.capacityFailure() == reserved;
        for (int i = 0; i < capacity / 2; i++) valid &= queue.poll() == 1000L + i;
        for (int i = 0; i < capacity / 2; i++) valid &= queue.offer(20000L + i);
        for (int i = capacity / 2; i < capacity; i++) valid &= queue.poll() == 1000L + i;
        for (int i = 0; i < capacity / 2; i++) valid &= queue.poll() == 20000L + i;
        valid &= queue.poll() == 0;
        for (int i = 0; i < 100_000; i++) {
            valid &= queue.offer(99999) && queue.poll() == 99999;
        }
        return valid;
    }
}
