package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-runtime proof of the admission transaction. Guest lowering remains
 * deliberately unavailable until the complete backend contract is verified.
 */
@Timeout(30)
final class QueueAdmissionTransactionTest {
    @Test
    void authenticatedProducerAdmissionReservesIngressAndCompletionAndRevocationWins()
            throws Exception {
        QueueAdmissionTransaction<String> transaction =
                new QueueAdmissionTransaction<>(1, 1);
        AtomicReference<ActorRuntime.ActorRef<String>> queueRef = new AtomicReference<>();
        LinkedBlockingQueue<String> observed = new LinkedBlockingQueue<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> producer = runtime.<String>spawnShared(
                    () -> (command, context) -> {
                        if (command.equals("first")) queueRef.get().send("payload-42");
                        else if (command.equals("second")) queueRef.get().send("payload-43");
                        else if (command.equals("after-revoke")) queueRef.get().send("payload-99");
                    });

            ActorRuntime.ActorRef<String> parent = runtime.<String>spawnShared(
                    () -> (command, context) -> {
                        if (command.equals("setup")) {
                            var child = runtime.<String>spawnChildShared(
                                    startup -> (payload, queueContext) -> {
                                        var mail = queueContext.currentMail().orElseThrow();
                                        var result = runtime.admitCurrentQueueCommand(
                                                transaction, 0, transaction.generation(),
                                                mail.value());
                                        observed.add(result.status().name() + ":" + mail.value());
                                    });
                            queueRef.set(child.ref());
                            runtime.grantCurrentQueueProducer(queueRef.get(), 0, producer);
                            observed.add("GRANTED");
                        } else if (command.equals("revoke")) {
                            runtime.revokeCurrentQueueProducer(queueRef.get(), 0);
                            observed.add("REVOKED");
                        }
                    });

            parent.send("setup");
            assertEquals("GRANTED", observed.poll(5, TimeUnit.SECONDS));

            producer.send("first");
            assertEquals("ADMITTED:payload-42", observed.poll(5, TimeUnit.SECONDS));

            // The first delivery still owns the sole ingress/completion
            // reservation, so a second command cannot overrun either budget.
            producer.send("second");
            assertEquals("FULL:payload-43", observed.poll(5, TimeUnit.SECONDS));
            assertEquals(1, transaction.outstandingIngress());
            assertEquals(1, transaction.reservedCompletions());

            var delivery = transaction.pollDelivery().orElseThrow();
            assertEquals("payload-42", delivery.payload());
            transaction.complete(delivery, QueueAdmissionTransaction.CompletionStatus.CONSUMED);
            var completion = transaction.pollCompletion().orElseThrow();
            assertEquals(delivery.admissionId(), completion.admissionId());
            assertEquals(producer.id(), completion.sender());
            assertEquals(QueueAdmissionTransaction.CompletionStatus.CONSUMED, completion.status());
            assertEquals(0, transaction.outstandingIngress());
            assertEquals(0, transaction.reservedCompletions());

            parent.send("revoke");
            assertEquals("REVOKED", observed.poll(5, TimeUnit.SECONDS));
            producer.send("after-revoke");
            assertEquals("UNAUTHORIZED:payload-99", observed.poll(5, TimeUnit.SECONDS));
            assertEquals(0, transaction.outstandingIngress());
            assertEquals(0, transaction.reservedCompletions());
        }
    }

    @Test
    void completionIsSingleWinnerAndForeignDeliveryCannotComplete() {
        QueueAdmissionTransaction<String> first =
                new QueueAdmissionTransaction<>(2, 2);
        QueueAdmissionTransaction<String> second =
                new QueueAdmissionTransaction<>(2, 2);
        ActorRuntime.ActorId sender = ActorRuntime.ActorId.create();

        var admitted = first.admitAuthorized(sender, 7, first.generation(), "message");
        assertEquals(QueueAdmissionTransaction.AdmissionStatus.ADMITTED, admitted.status());
        var delivery = admitted.delivery().orElseThrow();
        assertThrows(IllegalStateException.class, () ->
                second.complete(delivery, QueueAdmissionTransaction.CompletionStatus.CONSUMED));

        assertEquals("message", first.pollDelivery().orElseThrow().payload());
        first.complete(delivery, QueueAdmissionTransaction.CompletionStatus.CONSUMED);
        assertThrows(IllegalStateException.class, () ->
                first.complete(delivery, QueueAdmissionTransaction.CompletionStatus.CONSUMED));
        assertEquals(1, first.completionCount());
        assertEquals(1, first.reservedCompletions());
        first.pollCompletion().orElseThrow();
        assertEquals(0, first.reservedCompletions());
    }

    @Test
    void closeRejectsNewAdmissionButAllowsCommittedWorkToDrain() {
        QueueAdmissionTransaction<String> transaction =
                new QueueAdmissionTransaction<>(1, 1);
        ActorRuntime.ActorId sender = ActorRuntime.ActorId.create();
        var admitted = transaction.admitAuthorized(
                sender, 3, transaction.generation(), "committed");
        assertEquals(QueueAdmissionTransaction.AdmissionStatus.ADMITTED, admitted.status());

        transaction.close();
        assertEquals(QueueAdmissionTransaction.AdmissionStatus.CLOSED,
                transaction.admitAuthorized(sender, 4, transaction.generation(), "late").status());

        var delivery = transaction.pollDelivery().orElseThrow();
        transaction.complete(delivery, QueueAdmissionTransaction.CompletionStatus.CONSUMED);
        transaction.pollCompletion().orElseThrow();
        assertTrue(transaction.isDrained());
    }
}
