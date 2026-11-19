public final class ProxyReceiptGateV1Probe {
    private static void require(boolean value) {
        if (!value) throw new AssertionError("proxy ACK host model regression");
    }
    public static void main(String[] args) {
        var i = new ProxyReceiptGateV1.Identity(1, 11, 12, 13, 14, 17, 15, 16, 1);
        var gate = new ProxyReceiptGateV1(i);
        require(!gate.destination_admitted());
        require(!gate.apply_verified(i, ProxyReceiptGateV1.Event.STARTED));
        require(!gate.apply_verified(i, ProxyReceiptGateV1.Event.PROCESSED));
        require(!gate.apply_verified(i, ProxyReceiptGateV1.Event.DURABLE_INBOX));
        require(!gate.apply_verified(i, ProxyReceiptGateV1.Event.DURABLE_RESULT));
        var wrong = new ProxyReceiptGateV1.Identity(1, 99, 12, 13, 14, 17, 15, 16, 1);
        require(!gate.apply_verified(wrong, ProxyReceiptGateV1.Event.ADMITTED));
        wrong = new ProxyReceiptGateV1.Identity(1, 11, 99, 13, 14, 17, 15, 16, 1);
        require(!gate.apply_verified(wrong, ProxyReceiptGateV1.Event.ADMITTED));
        wrong = new ProxyReceiptGateV1.Identity(1, 11, 12, 13, 99, 17, 15, 16, 1);
        require(!gate.apply_verified(wrong, ProxyReceiptGateV1.Event.ADMITTED));
        wrong = new ProxyReceiptGateV1.Identity(1, 11, 12, 13, 14, 99, 15, 16, 1);
        require(!gate.apply_verified(wrong, ProxyReceiptGateV1.Event.ADMITTED));
        wrong = new ProxyReceiptGateV1.Identity(1, 11, 12, 13, 14, 17, 99, 16, 1);
        require(!gate.apply_verified(wrong, ProxyReceiptGateV1.Event.ADMITTED));
        wrong = new ProxyReceiptGateV1.Identity(1, 11, 12, 13, 14, 17, 15, 16, 99);
        require(!gate.apply_verified(wrong, ProxyReceiptGateV1.Event.ADMITTED));
        wrong = new ProxyReceiptGateV1.Identity(2, 11, 12, 13, 14, 17, 15, 16, 1);
        require(!gate.apply_verified(wrong, ProxyReceiptGateV1.Event.ADMITTED));
        require(gate.apply_verified(i, ProxyReceiptGateV1.Event.ADMITTED));
        require(gate.destination_admitted());
        require(!gate.apply_verified(i, ProxyReceiptGateV1.Event.DURABLE_RESULT));
        require(gate.apply_verified(i, ProxyReceiptGateV1.Event.DURABLE_INBOX));
        require(gate.durable_inbox() && gate.stage() == ProxyReceiptGateV1.Stage.ADMITTED);
        require(gate.apply_verified(i, ProxyReceiptGateV1.Event.STARTED));
        require(gate.apply_verified(i, ProxyReceiptGateV1.Event.ADMITTED));
        require(gate.stage() == ProxyReceiptGateV1.Stage.STARTED);
        require(gate.apply_verified(i, ProxyReceiptGateV1.Event.PROCESSED));
        require(gate.apply_verified(i, ProxyReceiptGateV1.Event.DURABLE_RESULT));
        require(gate.durable_result());
        require(!gate.apply_verified(i, ProxyReceiptGateV1.Event.FAILED));
        var lost = new ProxyReceiptGateV1(i);
        require(lost.apply_verified(i, ProxyReceiptGateV1.Event.INDETERMINATE));
        require(lost.outcome() == ProxyReceiptGateV1.Outcome.INDETERMINATE);
        require(!lost.apply_verified(i, ProxyReceiptGateV1.Event.ADMITTED));
        var reject = new ProxyReceiptGateV1(i);
        require(reject.apply_verified(i, ProxyReceiptGateV1.Event.REJECTED));
        require(!reject.apply_verified(i, ProxyReceiptGateV1.Event.ADMITTED));
        var failed = new ProxyReceiptGateV1(i);
        require(failed.apply_verified(i, ProxyReceiptGateV1.Event.ADMITTED));
        require(failed.apply_verified(i, ProxyReceiptGateV1.Event.FAILED));
        require(!failed.apply_verified(i, ProxyReceiptGateV1.Event.PROCESSED));
        var invalid = new ProxyReceiptGateV1(new ProxyReceiptGateV1.Identity(1, 0, 12, 13, 14, 17, 15, 16, 1));
        require(!invalid.apply_verified(new ProxyReceiptGateV1.Identity(1, 0, 12, 13, 14, 17, 15, 16, 1),
                ProxyReceiptGateV1.Event.ADMITTED));
        System.out.println("HOST_MODEL_ONLY: proxy ACK stage and replay tests passed");
    }
}
