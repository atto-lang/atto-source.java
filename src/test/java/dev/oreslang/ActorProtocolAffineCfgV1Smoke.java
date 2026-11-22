package dev.oreslang;

import dev.oreslang.parser.ActorProtocolAffineCfgV1;
import dev.oreslang.parser.ActorProtocolTypestateV1;
import java.util.List;
import static dev.oreslang.parser.ActorProtocolAffineCfgV1.*;
import static dev.oreslang.parser.ActorProtocolTypestateV1.Mode.*;

/** Standalone source-only smoke; not runnable guest actor tests. */
public final class ActorProtocolAffineCfgV1Smoke {
    private ActorProtocolAffineCfgV1Smoke() { }
    private static int passed;
    private static ActorProtocolTypestateV1.Catalog catalog() {
        return new ActorProtocolTypestateV1.Catalog(List.of(
                new ActorProtocolTypestateV1.Protocol("Connection", "Disconnected",
                        List.of("Disconnected", "Connected", "Closed"), List.of("Closed"),
                        List.of(
                            new ActorProtocolTypestateV1.Transition("Disconnected", "Connect", "Config", "Connected", UNARY, "Ack"),
                            new ActorProtocolTypestateV1.Transition("Disconnected", "Close", null, "Closed", UNARY, "ClosedAck"),
                            new ActorProtocolTypestateV1.Transition("Connected", "Close", null, "Closed", UNARY, "ClosedAck"),
                            new ActorProtocolTypestateV1.Transition("Connected", "Subscribe", "Topic", "Connected", STREAM, "Event")
                        ))) , List.of());
    }
    private static Block b(int id, List<Op> ops, int... targets) {
        return new Block(id, ops, java.util.Arrays.stream(targets).boxed().toList());
    }
    private static Program p(boolean suspending, Block... blocks) {
        return new Program(0, suspending, List.of(blocks));
    }
    private static Init init() { return new Init(1, "Connection"); }
    private static Begin connect(int from, int lease) { return new Begin(from, lease, "Connect", "Config", UNARY, "Ack"); }
    private static Begin stream(int from, int lease) { return new Begin(from, lease, "Subscribe", "Topic", STREAM, "Event"); }
    private static Begin close(int from, int lease) { return new Begin(from, lease, "Close", null, UNARY, "ClosedAck"); }
    private static void passes(String name, Program p) {
        try {
            Proof proof = ActorProtocolAffineCfgV1.verify(catalog(), p);
            if (proof.verifiedBlocks() != p.blocks().size()) throw new AssertionError("incomplete proof: " + name);
            passed++;
        } catch (RuntimeException ex) { throw new AssertionError(name + " should pass", ex); }
    }
    private static void fails(String name, String expected, Program p) {
        try { ActorProtocolAffineCfgV1.verify(catalog(), p); }
        catch (IllegalArgumentException error) {
            if (error.getMessage().contains(expected)) { passed++; return; }
            throw new AssertionError(name + ": expected " + expected + " but got " + error, error);
        }
        throw new AssertionError(name + ": expected " + expected);
    }
    private static Block ret(int id) { return b(id, List.of(new Return())); }

    public static void main(String[] args) {
        passes("unary four explicit outcomes", p(true,
                b(0, List.of(init(), connect(1, 10), new AwaitUnary(10, 2)), 1,2,3,4),
                b(1, List.of(new ReturnHandle(2), new Return())), ret(2),ret(3),ret(4)));
        passes("stream item then eof and independent terminal failures", p(true,
                b(0, List.of(init(), connect(1, 10), new AwaitUnary(10, 2)), 1,2,3,4),
                b(1, List.of(stream(2, 11), new AwaitStreamStep(11, 3)), 5,6,7,8,9),
                ret(2),ret(3),ret(4),
                b(5, List.of(new AwaitStreamStep(11, 3)), 10,11,12,13,14),
                b(6, List.of(new ReturnHandle(3), new Return())), ret(7),ret(8),ret(9),
                b(10, List.of(new CancelLease(11),new Return())),
                b(11, List.of(new ReturnHandle(3), new Return())),
                ret(12),ret(13),ret(14)));
        passes("take then request", p(true,
                b(0,List.of(init(),new Take(1,2),connect(2,10),new AwaitUnary(10,3)),1,2,3,4),
                b(1,List.of(new ReturnHandle(3),new Return())),ret(2),ret(3),ret(4)));
        passes("lease transfer into task frame", p(true,
                b(0,List.of(init(),connect(1,10),new MoveLease(10,11),new AwaitUnary(11,2)),1,2,3,4),
                b(1,List.of(new ReturnHandle(2),new Return())),ret(2),ret(3),ret(4)));
        passes("cancel never creates next handle", p(false,b(0,List.of(init(),connect(1,10),new CancelLease(10),new Return()))));
        passes("terminal disposal only after verified close", p(true,
                b(0,List.of(init(),close(1,10),new AwaitUnary(10,2)),1,2,3,4),
                b(1,List.of(new DisposeTerminal(2),new Return())),ret(2),ret(3),ret(4)));
        passes("completed mutable loan before send", p(true,
                b(0,List.of(init(),new Borrow(1,1,LoanKind.EXCLUSIVE),new EndBorrow(1),connect(1,10),new AwaitUnary(10,2)),1,2,3,4),
                b(1,List.of(new ReturnHandle(2),new Return())),ret(2),ret(3),ret(4)));
        fails("affine authority cannot be copied","E_PROTOCOL_AFFINE_COPY",
                p(false,b(0,List.of(init(),new Copy(1,2),new Return()))));
        fails("begin consumes old owner","E_PROTOCOL_HANDLE_NOT_LIVE",
                p(false,b(0,List.of(init(),connect(1,10),new ReturnHandle(1),new Return()))));
        fails("take invalidates old owner","E_PROTOCOL_HANDLE_NOT_LIVE",
                p(false,b(0,List.of(init(),new Take(1,2),new ReturnHandle(1),new Return()))));
        fails("take may not clone into existing live target","E_PROTOCOL_TARGET_LIVE",
                p(false,b(0,List.of(init(),new Init(2,"Connection"),new Take(1,2),new Return()))));
        fails("double cancel denied","E_PROTOCOL_LEASE_NOT_LIVE",
                p(false,b(0,List.of(init(),connect(1,10),new CancelLease(10),new CancelLease(10),new Return()))));
        fails("old lease unavailable after move","E_PROTOCOL_LEASE_NOT_LIVE",
                p(false,b(0,List.of(init(),connect(1,10),new MoveLease(10,11),new CancelLease(10),new Return()))));
        fails("lease cannot escape a return","E_PROTOCOL_LEASE_ESCAPE",
                p(false,b(0,List.of(init(),connect(1,10),new Return()))));
        fails("unreturned handle cannot escape","E_PROTOCOL_HANDLE_ESCAPE",
                p(false,b(0,List.of(init(),new Return()))));
        fails("no forged handle on unary error edge","E_PROTOCOL_HANDLE_NOT_LIVE", p(true,
                b(0,List.of(init(),connect(1,10),new AwaitUnary(10,2)),1,2,3,4),
                b(1,List.of(new ReturnHandle(2),new Return())),
                b(2,List.of(new ReturnHandle(2),new Return())),ret(3),ret(4)));
        fails("stream item is not terminal EOF","E_PROTOCOL_HANDLE_NOT_LIVE", p(true,
                b(0,List.of(init(),connect(1,10),new AwaitUnary(10,2)),1,2,3,4),
                b(1,List.of(stream(2,11),new AwaitStreamStep(11,3)),5,6,7,8,9),
                ret(2),ret(3),ret(4),
                b(5,List.of(new ReturnHandle(3),new Return())),
                b(6,List.of(new ReturnHandle(3),new Return())),ret(7),ret(8),ret(9)));
        fails("loan blocks consume","E_PROTOCOL_LOAN_CONFLICT",
                p(false,b(0,List.of(init(),new Borrow(1,9,LoanKind.READ),connect(1,10),new Return()))));
        fails("suspend cannot carry critical-state loan","E_PROTOCOL_LOAN_ACROSS_AWAIT",
                p(true,b(0,List.of(init(),new Borrow(1,9,LoanKind.EXCLUSIVE),new Suspend(),new Return()))));
        fails("await cannot carry unrelated live loan","E_PROTOCOL_LOAN_ACROSS_AWAIT",p(true,
                b(0,List.of(init(),new Init(5,"Connection"),new Borrow(5,9,LoanKind.READ),connect(1,10),new AwaitUnary(10,2)),1,2,3,4),
                b(1,List.of(new ReturnHandle(2),new Return())),ret(2),ret(3),ret(4)));
        fails("borrowed handle may not be returned","E_PROTOCOL_LOAN_CONFLICT",
                p(false,b(0,List.of(init(),new Borrow(1,9,LoanKind.READ),new ReturnHandle(1),new Return()))));
        fails("unended borrow cannot escape","E_PROTOCOL_LOAN_ESCAPE",
                p(false,b(0,List.of(init(),new Borrow(1,9,LoanKind.READ),new Return()))));
        fails("nonterminal drop denied","E_PROTOCOL_NONTERMINAL_DISPOSE",
                p(false,b(0,List.of(init(),new DisposeTerminal(1),new Return()))));
        fails("illegal stream before connected","E_PROTOCOL_ILLEGAL_MESSAGE_STATE",
                p(false,b(0,List.of(init(),stream(1,10),new Return()))));
        fails("nominal cardinality mismatch","E_PROTOCOL_RESPONSE_MODE",
                p(false,b(0,List.of(init(),new Begin(1,10,"Connect","Config",STREAM,"Ack"),new Return()))));
        fails("nominal response mismatch","E_PROTOCOL_RESPONSE_TYPE",
                p(false,b(0,List.of(init(),new Begin(1,10,"Connect","Config",UNARY,"Wrong"),new Return()))));
        fails("nominal payload mismatch","E_PROTOCOL_MESSAGE_PAYLOAD",
                p(false,b(0,List.of(init(),new Begin(1,10,"Connect","Wrong",UNARY,"Ack"),new Return()))));
        fails("wrong stream await for unary","E_PROTOCOL_RESPONSE_MODE",p(true,
                b(0,List.of(init(),connect(1,10),new AwaitStreamStep(10,2)),1,2,3,4,5),
                ret(1),ret(2),ret(3),ret(4),ret(5)));
        fails("wrong unary await for stream","E_PROTOCOL_RESPONSE_MODE",p(true,
                b(0,List.of(init(),connect(1,10),new AwaitUnary(10,2)),1,2,3,4),
                b(1,List.of(stream(2,11),new AwaitUnary(11,3)),5,6,7,8),ret(2),ret(3),ret(4),ret(5),ret(6),ret(7),ret(8)));
        fails("await must be suspending","E_PROTOCOL_AWAIT_CONTEXT",p(false,
                b(0,List.of(init(),connect(1,10),new AwaitUnary(10,2)),1,2,3,4),
                ret(1),ret(2),ret(3),ret(4)));
        fails("join cannot silently abandon lease","E_PROTOCOL_LEASE_JOIN",p(false,
                b(0,List.of(init(),connect(1,10)),1,2),
                b(1,List.of(new CancelLease(10)),3),b(2,List.of(),3),ret(3)));
        fails("optional handle at branch join cannot send","E_PROTOCOL_HANDLE_NOT_LIVE",p(false,
                b(0,List.of(init()),1,2),
                b(1,List.of(new Take(1,2),new ReturnHandle(2)),3),
                b(2,List.of(),3),
                b(3,List.of(connect(1,10),new CancelLease(10),new Return()))));
        fails("maybe-moved handle still live on alternate branch cannot be abandoned",
                "E_PROTOCOL_HANDLE_ESCAPE",p(false,
                b(0,List.of(init()),1,2),
                b(1,List.of(new Take(1,2),new ReturnHandle(2)),3),
                b(2,List.of(),3),ret(3)));
        fails("branch-unbalanced loan","E_PROTOCOL_LOAN_JOIN",p(false,
                b(0,List.of(init()),1,2),
                b(1,List.of(new Borrow(1,9,LoanKind.READ)),3),
                b(2,List.of(),3),ret(3)));
        fails("cycles denied","E_PROTOCOL_CFG_CYCLE",p(false,
                b(0,List.of(init()),1),b(1,List.of(),0)));
        fails("implicit exit denied","E_PROTOCOL_IMPLICIT_EXIT",
                p(false,b(0,List.of(init()))));
        fails("missing explicit five-way stream outcomes","E_PROTOCOL_OUTCOME_SHAPE",p(true,
                b(0,List.of(init(),close(1,10),new AwaitUnary(10,2)),1,2,3,4),
                b(1,List.of(stream(2,11),new AwaitStreamStep(11,3)),5,6,7,8),
                ret(2),ret(3),ret(4),ret(5),ret(6),ret(7),ret(8)));
        var manyPlaces = new java.util.ArrayList<Op>();
        manyPlaces.add(init());
        for (int place = 1; place <= MAX_HANDLES; place++)
            manyPlaces.add(new Take(place, place + 1));
        manyPlaces.add(new Return());
        fails("moved handles still count against bounded proof budget", "E_PROTOCOL_HANDLE_BUDGET",
                p(false,b(0,manyPlaces)));
        fails("two returned owners are not one function result",
                "E_PROTOCOL_MULTIPLE_RETURN_HANDLES", p(false,
                b(0, List.of(init(), new Init(2,"Connection"),
                        new ReturnHandle(1), new ReturnHandle(2), new Return()))));
        fails("returned authority must agree at join",
                "E_PROTOCOL_RETURN_JOIN", p(false,
                b(0,List.of(init()),1,2),
                b(1,List.of(new ReturnHandle(1)),3),
                b(2,List.of(),3),
                ret(3)));
        System.out.println("ATTO_PROTOCOL_AFFINE_CFG_SOURCE_ONLY=PASS|cases="+passed);
        System.out.println("ATTO_PROTOCOL_GUEST_EXECUTION=NOT_EXECUTED");
    }
}
