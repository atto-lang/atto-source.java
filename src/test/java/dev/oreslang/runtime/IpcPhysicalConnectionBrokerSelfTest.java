package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Host-only proof of broker-minted one-shot physical connection authority. */
public final class IpcPhysicalConnectionBrokerSelfTest {
    private IpcPhysicalConnectionBrokerSelfTest() { }
    private static int checks;

    private static void check(boolean value) {
        ++checks;
        if (!value) {
            throw new AssertionError("IPC physical broker invariant failed");
        }
    }

    private static void denied(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            ++checks;
            return;
        }
        throw new AssertionError("invalid IPC physical authority operation succeeded");
    }

    private static byte[] key(int seed) {
        byte[] value = new byte[32];
        for (int i = 0; i < value.length; ++i) {
            value[i] = (byte) (seed + i + 1);
        }
        return value;
    }

    private static boolean allZero(byte[] value) {
        for (byte element : value) {
            if (element != 0) return false;
        }
        return true;
    }

    public static void main(String[] ignored) {
        AtomicInteger bindingSequence = new AtomicInteger();
        var broker = new IpcPhysicalConnectionBroker(target -> {
            int seed = bindingSequence.incrementAndGet();
            for (int i = 0; i < target.length; ++i) {
                target[i] = (byte) (seed + i);
            }
        });

        byte[] callerKey = key(10);
        byte[] expectedPrivateKey = callerKey.clone();
        var first = broker.mint(100, callerKey);

        // Caller mutation after mint cannot alter the broker-owned key copy.
        Arrays.fill(callerKey, (byte) 0);
        var firstTransfer = first.consume();
        check(first.consumedOrDestroyed());
        check(firstTransfer.generation() == 100);
        check(Arrays.equals(firstTransfer.macKey(), expectedPrivateKey));
        check(firstTransfer.connectionBinding().length
                == IpcPhysicalConnectionBroker.CONNECTION_BINDING_BYTES);
        check(!allZero(firstTransfer.connectionBinding()));
        Object firstOwner = firstTransfer.physicalOwner();
        byte[] firstBinding = firstTransfer.connectionBinding().clone();
        denied(first::consume);

        // Generation is monotonic within one broker and cannot be replayed or
        // rolled back even after the prior authority is consumed.
        byte[] nextKey = key(40);
        denied(() -> broker.mint(100, nextKey));
        denied(() -> broker.mint(99, nextKey));

        var second = broker.mint(101, nextKey);
        var secondTransfer = second.consume();
        check(secondTransfer.generation() == 101);
        check(secondTransfer.physicalOwner() != firstOwner);
        check(!Arrays.equals(
                secondTransfer.connectionBinding(), firstBinding));

        // An unconsumed capability can be explicitly destroyed and never
        // resurrected.
        var abandoned = broker.mint(102, nextKey);
        check(!abandoned.consumedOrDestroyed());
        abandoned.close();
        check(abandoned.consumedOrDestroyed());
        denied(abandoned::consume);

        // Broken all-zero binding generation fails closed.
        AtomicInteger zeroAttempts = new AtomicInteger();
        var zeroBroker = new IpcPhysicalConnectionBroker(target -> {
            zeroAttempts.incrementAndGet();
            Arrays.fill(target, (byte) 0);
        });
        denied(() -> zeroBroker.mint(1, nextKey));
        check(zeroAttempts.get() == 4);

        // A failed mint does not consume its generation. Trusted setup may
        // retry the same generation after its binding source recovers.
        AtomicInteger flakyCalls = new AtomicInteger();
        var flakyBroker = new IpcPhysicalConnectionBroker(target -> {
            if (flakyCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("test binding source failure");
            }
            Arrays.fill(target, (byte) 7);
        });
        denied(() -> flakyBroker.mint(7, nextKey));
        var recovered = flakyBroker.mint(7, nextKey);
        var recoveredTransfer = recovered.consume();
        check(recoveredTransfer.generation() == 7);
        check(!allZero(recoveredTransfer.connectionBinding()));

        // Production SecureRandom-backed minting yields nonzero broker-owned
        // connection material without a caller-provided binding.
        var productionBroker = new IpcPhysicalConnectionBroker();
        var randomAuthority = productionBroker.mint(1, nextKey);
        var randomTransfer = randomAuthority.consume();
        check(randomTransfer.generation() == 1);
        check(!allZero(randomTransfer.connectionBinding()));

        firstTransfer.destroy();
        secondTransfer.destroy();
        recoveredTransfer.destroy();
        randomTransfer.destroy();
        check(allZero(firstTransfer.macKey()));
        check(allZero(firstTransfer.connectionBinding()));
        check(allZero(secondTransfer.macKey()));
        check(allZero(secondTransfer.connectionBinding()));
        check(allZero(recoveredTransfer.macKey()));
        check(allZero(recoveredTransfer.connectionBinding()));
        check(allZero(randomTransfer.macKey()));
        check(allZero(randomTransfer.connectionBinding()));

        System.out.println("JAVA_IPC_PHYSICAL_BROKER_PASS checks=" + checks);
    }
}
