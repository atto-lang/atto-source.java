package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** No guest compilation: verifies real JNI boundary and two Java carriers. */
public final class NativeActorTurnKernelStandaloneSmoke {
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("operation should be denied"); }
        catch (IllegalStateException expected) { /* fail closed */ }
    }

    public static void main(String[] args) throws Exception {
        rejects(() -> NativeActorTurnKernel.open(32));
        System.setProperty("ores.native.actor.turn.experimental", "true");
        try (NativeActorTurnKernel kernel = NativeActorTurnKernel.open(128)) {
            var first = kernel.register(1, 1);
            var second = kernel.register(2, 1);
            // JNI leases are OS-carrier-bound: virtual-thread migration is unsafe.
            AtomicReference<Throwable> virtualFailure = new AtomicReference<>();
            Thread virtual = Thread.ofVirtual().start(() -> {
                try { rejects(() -> kernel.enter(first)); }
                catch (Throwable e) { virtualFailure.set(e); }
            });
            virtual.join();
            if (virtualFailure.get() != null) throw new AssertionError(virtualFailure.get());
            kernel.enter(first);
            rejects(() -> kernel.enter(second));
            rejects(() -> kernel.settled(first));
            CountDownLatch ready = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread foreign = new Thread(() -> {
                try {
                    ready.countDown();
                    rejects(() -> kernel.exit(first));
                } catch (Throwable e) { error.set(e); }
            });
            foreign.start();
            ready.await();
            foreign.join();
            if (error.get() != null) throw new AssertionError(error.get());
            kernel.exit(first);
            Thread resumer = new Thread(() -> {
                try { kernel.enter(first); kernel.exit(first); }
                catch (Throwable e) { error.set(e); }
            });
            resumer.start();
            resumer.join();
            if (error.get() != null) throw new AssertionError(error.get());
            kernel.enter(second);
            kernel.settled(first); // a different source owns the actor lane
            kernel.retire(first);
            rejects(() -> kernel.enter(first));
            rejects(kernel::close); // source 2 is alive and owns the carrier
            kernel.exit(second);
            kernel.stop();
            rejects(() -> kernel.enter(second));
            kernel.settled(second);
            kernel.retire(second);
        }
        System.out.println("NATIVE_ACTOR_TURN_JNI_STANDALONE_OK");
    }
}
