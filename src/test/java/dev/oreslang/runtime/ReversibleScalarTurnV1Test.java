package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static dev.oreslang.runtime.ReversibleScalarTurnV1.Kind.*;
import static dev.oreslang.runtime.ReversibleScalarTurnV1.Status.*;
import static dev.oreslang.runtime.BoundedRetryPolicyV1.Decision.*;

final class ReversibleScalarTurnV1Test {
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }

    @Test void historyAndDuplicates() {
        var turn = new ReversibleScalarTurnV1(100, 2, 8);
        var first = turn.apply(1, 0, COMMIT, 0);
        equal(OK, first.status());
        equal(0L, turn.value());
        equal(OK, turn.apply(2, 1, COMMIT, 7).status());
        equal(first, turn.apply(1, 0, COMMIT, 0));
        equal(ID_REUSE, turn.apply(1, 0, COMMIT, 9).status());
        equal(CONFLICT, turn.apply(3, 1, UNDO, 2).status());
        equal(INVALID_HISTORY, turn.apply(3, 2, UNDO, 1).status());
        equal(2L, turn.version());
        equal(7L, turn.value());
        equal(QUOTA, turn.apply(3, 2, COMMIT, 11).status());
        equal(OK, turn.apply(3, 2, UNDO, 2).status());
        equal(0L, turn.value());
        equal(OK, turn.apply(4, 3, UNDO, 1).status());
        equal(100L, turn.value());
        equal(OK, turn.apply(5, 4, REDO, 1).status());
        equal(0L, turn.value());
        equal(OK, turn.apply(6, 5, COMMIT, 42).status());
        equal(INVALID_HISTORY, turn.apply(7, 6, REDO, 2).status());
        equal(6L, turn.version());
        equal(42L, turn.value());
    }

    @Test void receiptQuotaAndAtomicFailure() {
        var turn = new ReversibleScalarTurnV1(Long.MIN_VALUE, 1, 2);
        var receipt = turn.apply(1, 0, COMMIT, Long.MAX_VALUE);
        equal(OK, receipt.status());
        equal(OK, turn.apply(2, 1, UNDO, 1).status());
        equal(Long.MIN_VALUE, turn.value());
        equal(QUOTA, turn.apply(3, 2, REDO, 1).status());
        equal(receipt, turn.apply(1, 0, COMMIT, Long.MAX_VALUE));
        equal(2L, turn.version());
        equal(Long.MIN_VALUE, turn.value());
    }

    @Test void retryBounds() {
        var p = new BoundedRetryPolicyV1(5, 100, 250, 1000,
                BoundedRetryPolicyV1.Backoff.EXPONENTIAL);
        equal(100L, p.afterFailure(1, 0, true, false).delayNanos());
        equal(200L, p.afterFailure(2, 100, true, false).delayNanos());
        equal(250L, p.afterFailure(3, 300, true, false).delayNanos());
        equal(EXHAUSTED, p.afterFailure(5, 800, true, false).decision());
        equal(DEADLINE, p.afterFailure(4, 750, true, false).decision());
        equal(CANCELLED, p.afterFailure(1, 0, true, true).decision());
        equal(INELIGIBLE, p.afterFailure(1, 0, false, false).decision());
        var huge = new BoundedRetryPolicyV1(Integer.MAX_VALUE, 1, Long.MAX_VALUE,
                Long.MAX_VALUE, BoundedRetryPolicyV1.Backoff.EXPONENTIAL);
        equal(DEADLINE, huge.afterFailure(Integer.MAX_VALUE - 1, 0, true, false).decision());
        var zero = new BoundedRetryPolicyV1(2, 0, 0, 1,
                BoundedRetryPolicyV1.Backoff.FIXED);
        equal(WAIT, zero.afterFailure(1, 0, true, false).decision());
        equal(DEADLINE, zero.afterFailure(1, 1, true, false).decision());
    }

    public static void main(String[] args) {
        var tests = new ReversibleScalarTurnV1Test();
        tests.historyAndDuplicates();
        tests.receiptQuotaAndAtomicFailure();
        tests.retryBounds();
        System.out.println("3 runtime primitive tests passed");
    }
}
