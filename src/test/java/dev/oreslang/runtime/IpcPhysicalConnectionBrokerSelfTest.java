package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    public static void main(String[] ignored) throws Exception {
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

        // Host-only guards must run before broker monitor entry. Hold the
        // broker monitor and prove a marked actor carrier is denied without
        // parking or advancing generation.
        CountDownLatch brokerMonitorHeld = new CountDownLatch(1);
        CountDownLatch releaseBrokerMonitor = new CountDownLatch(1);
        AtomicReference<Throwable> brokerHolderFailure = new AtomicReference<>();
        Thread brokerHolder = new Thread(() -> {
            synchronized (broker) {
                brokerMonitorHeld.countDown();
                try {
                    if (!releaseBrokerMonitor.await(3, TimeUnit.SECONDS)) {
                        throw new AssertionError("broker monitor hold timed out");
                    }
                } catch (Throwable failure) {
                    brokerHolderFailure.set(failure);
                }
            }
        }, "ipc-test-broker-monitor-holder");
        brokerHolder.start();
        check(brokerMonitorHeld.await(3, TimeUnit.SECONDS));

        AtomicReference<Throwable> brokerActorFailure = new AtomicReference<>();
        AtomicInteger brokerActorDenials = new AtomicInteger();
        Thread brokerActor = new Thread(() -> {
            RuntimeBlockingGuard.enterActorCarrier();
            try {
                try {
                    broker.mint(103, nextKey);
                    throw new AssertionError(
                            "actor carrier entered broker mint monitor");
                } catch (IllegalStateException expected) {
                    brokerActorDenials.incrementAndGet();
                }
            } catch (Throwable failure) {
                brokerActorFailure.set(failure);
            } finally {
                RuntimeBlockingGuard.exitActorCarrier();
            }
        }, "ipc-test-broker-actor-carrier");
        brokerActor.start();
        brokerActor.join(500);
        check(!brokerActor.isAlive());
        check(brokerActorFailure.get() == null);
        check(brokerActorDenials.get() == 1);

        releaseBrokerMonitor.countDown();
        brokerHolder.join(3000);
        check(!brokerHolder.isAlive());
        check(brokerHolderFailure.get() == null);

        var guardedAuthority = broker.mint(103, nextKey);

        // The same fail-before-monitor property applies to one-shot authority
        // consume/diagnostic/destroy operations.
        CountDownLatch authorityMonitorHeld = new CountDownLatch(1);
        CountDownLatch releaseAuthorityMonitor = new CountDownLatch(1);
        AtomicReference<Throwable> authorityHolderFailure =
                new AtomicReference<>();
        Thread authorityHolder = new Thread(() -> {
            synchronized (guardedAuthority) {
                authorityMonitorHeld.countDown();
                try {
                    if (!releaseAuthorityMonitor.await(3, TimeUnit.SECONDS)) {
                        throw new AssertionError(
                                "authority monitor hold timed out");
                    }
                } catch (Throwable failure) {
                    authorityHolderFailure.set(failure);
                }
            }
        }, "ipc-test-authority-monitor-holder");
        authorityHolder.start();
        check(authorityMonitorHeld.await(3, TimeUnit.SECONDS));

        AtomicReference<Throwable> authorityActorFailure =
                new AtomicReference<>();
        AtomicInteger authorityActorDenials = new AtomicInteger();
        Thread authorityActor = new Thread(() -> {
            RuntimeBlockingGuard.enterActorCarrier();
            try {
                try {
                    guardedAuthority.consume();
                    throw new AssertionError(
                            "actor carrier entered authority consume monitor");
                } catch (IllegalStateException expected) {
                    authorityActorDenials.incrementAndGet();
                }
                try {
                    guardedAuthority.consumedOrDestroyed();
                    throw new AssertionError(
                            "actor carrier entered authority diagnostic monitor");
                } catch (IllegalStateException expected) {
                    authorityActorDenials.incrementAndGet();
                }
                try {
                    guardedAuthority.close();
                    throw new AssertionError(
                            "actor carrier entered authority destroy monitor");
                } catch (IllegalStateException expected) {
                    authorityActorDenials.incrementAndGet();
                }
            } catch (Throwable failure) {
                authorityActorFailure.set(failure);
            } finally {
                RuntimeBlockingGuard.exitActorCarrier();
            }
        }, "ipc-test-authority-actor-carrier");
        authorityActor.start();
        authorityActor.join(500);
        check(!authorityActor.isAlive());
        check(authorityActorFailure.get() == null);
        check(authorityActorDenials.get() == 3);

        releaseAuthorityMonitor.countDown();
        authorityHolder.join(3000);
        check(!authorityHolder.isAlive());
        check(authorityHolderFailure.get() == null);
        check(!guardedAuthority.consumedOrDestroyed());
        guardedAuthority.close();
        check(guardedAuthority.consumedOrDestroyed());

        // Broker construction itself is host-only because SecureRandom setup
        // may perform platform work. Reject actor carriers before that setup.
        AtomicInteger constructorDenials = new AtomicInteger();
        AtomicReference<Throwable> constructorActorFailure =
                new AtomicReference<>();
        Thread constructorActor = new Thread(() -> {
            RuntimeBlockingGuard.enterActorCarrier();
            try {
                try {
                    new IpcPhysicalConnectionBroker(
                            target -> Arrays.fill(target, (byte) 1));
                    throw new AssertionError(
                            "actor carrier constructed physical broker");
                } catch (IllegalStateException expected) {
                    constructorDenials.incrementAndGet();
                }
            } catch (Throwable failure) {
                constructorActorFailure.set(failure);
            } finally {
                RuntimeBlockingGuard.exitActorCarrier();
            }
        }, "ipc-test-broker-constructor-actor-carrier");
        constructorActor.start();
        constructorActor.join(500);
        check(!constructorActor.isAlive());
        check(constructorActorFailure.get() == null);
        check(constructorDenials.get() == 1);

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
