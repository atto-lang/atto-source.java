package dev.oreslang.runtime.accel;

import dev.oreslang.runtime.DataOrientedLayout;
import dev.oreslang.runtime.DopRegionSchedule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Random;

/** Dependency-free, host-only regression gate. NOT source-level DOP compilation. */
public final class DopOwnedRegionStandaloneCheck {
    private static long assertions;
    private DopOwnedRegionStandaloneCheck() { }

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }

    private static void reject(Runnable action, String description) {
        assertions++;
        try {
            action.run();
        } catch (IllegalStateException | IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("unexpected admission: " + description);
    }

    private static DataOrientedLayout.Plan shape(DataOrientedLayout.Kind kind, int rows) {
        return new DataOrientedLayout.Plan(kind, rows, 3, 4, 16,
                kind == DataOrientedLayout.Kind.AOSOA ? 4 : 1);
    }

    private static void checkOwnership() throws ReflectiveOperationException {
        var owner = new DopKernelIR.HostBuffer(shape(DataOrientedLayout.Kind.SOA, 7));
        var token = owner.regionToken();
        var root = owner.regionRoot();
        Field ownerField = DopKernelIR.HostBuffer.class.getDeclaredField("state");
        ownerField.setAccessible(true);
        Object state = ownerField.get(owner);
        Field weakField = token.getClass().getDeclaredField("ownerState");
        weakField.setAccessible(true);
        check(weakField.get(token) instanceof WeakReference<?>, "token weak provenance");
        var weak = (WeakReference<?>) weakField.get(token);
        check(weak.get() == state, "weak reference points to actual owned state");
        for (Field field : token.getClass().getDeclaredFields()) {
            check(field.getType() != state.getClass(), "token strongly pins native State");
        }
        try (var read = owner.borrowRead()) {
            check(root.isLiveOwned(), "borrowed root must be live");
            reject(owner::close, "close beneath read loan");
            reject(owner::take, "transfer beneath read loan");
            check(read.read(0, 0) == 0.0f, "zeroed host allocation");
        }
        var successor = owner.take();
        check(successor.regionToken() == token, "move changed allocation identity");
        check(successor.regionRoot().sameRoot(root), "root alias lost under take");
        owner.close();
        check(token.isLive(), "moved-from close retired owner");
        successor.close();
        check(!token.isLive() && !root.isLiveOwned(), "closed owner remained live");
        reject(successor::borrowRead, "borrow after retirement");
    }

    private static void checkRegionExecution() {
        var rng = new Random(0xD0F2026L);
        for (var kind : DataOrientedLayout.Kind.values()) {
            var plan = shape(kind, 7);
            var kernel = new DopKernelIR.Kernel(plan, DopKernelIR.Element.F32,
                    DopKernelIR.Op.SCALE_BIAS, 2.0f, 1.0f);
            var input = new DopKernelIR.HostBuffer(plan);
            var output = new DopKernelIR.HostBuffer(plan);
            for (int row = 0; row < 7; row++) {
                for (int field = 0; field < 3; field++) {
                    input.write(row, field, row * 3 + field);
                }
            }
            for (int trial = 0; trial < 512; trial++) {
                int field = rng.nextInt(3);
                int start = rng.nextInt(8);
                int end = start + rng.nextInt(8 - start);
                var source = DopRegionSchedule.Access.rows(input.regionRoot(), field,
                        start, end - start, DopRegionSchedule.Mode.READ);
                var destination = DopRegionSchedule.Access.rows(output.regionRoot(), field,
                        start, end - start, DopRegionSchedule.Mode.WRITE);
                DopKernelIR.executeCpuRegion(kernel, input, output, source, destination);
                for (int row = start; row < end; row++) {
                    float expected = 2.0f * (row * 3 + field) + 1.0f;
                    check(output.read(row, field) == expected, "owned CPU slice mismatch");
                }
            }
            try (var loan = output.borrowWrite()) {
                reject(() -> output.read(0, 0), "read during whole-batch write");
                reject(() -> output.write(0, 0, -1), "write during whole-batch write");
                reject(output::take, "transfer during whole-batch write");
                reject(output::close, "close during whole-batch write");
                loan.write(0, 0, 37.0f);
            }
            check(output.read(0, 0) == 37.0f, "write loan not visible after release");
            DopKernelIR.executeCpu(kernel, input, output);
            for (int row = 0; row < 7; row++) {
                for (int field = 0; field < 3; field++) {
                    check(output.read(row, field) == 2.0f * (row * 3 + field) + 1.0f,
                            "whole-buffer CPU mismatch");
                }
            }
            input.close();
            output.close();
        }
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        checkOwnership();
        checkRegionExecution();
        System.out.println("PASS: " + assertions
                + " owned Java DOP standalone assertions (no GPU dispatch)");
    }
}
