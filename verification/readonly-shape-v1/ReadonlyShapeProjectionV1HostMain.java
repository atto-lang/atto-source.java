package dev.oreslang;

import java.util.List;
import dev.oreslang.types.ReadonlyShapeProjectionV1;
import dev.oreslang.types.ReadonlyShapeProjectionV1.*;

/** Exact-source host-only test runner; no JUnit, no Graal guest execution. */
public final class ReadonlyShapeProjectionV1HostMain {
    private static Field f(String n, Scalar ty, int slot) {
        return new Field(n,ty,slot,true,true,true,false);
    }
    private static Source s(Origin kind, Field... fields) {
        return new Source(kind,7,1,9,false,List.of(fields));
    }
    private static Plan p(Source source) {
        return ReadonlyShapeProjectionV1.prove(source,List.of(new RequiredField("x",Scalar.I64)));
    }
    private static void check(boolean ok,String id) {
        if (!ok) throw new AssertionError(id);
    }
    private static void reject(Runnable code,String id) {
        try { code.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("expected rejection: "+id);
    }
    public static void main(String[] args) {
        var a=p(s(Origin.CLASS,f("x",Scalar.I64,0)));
        check(a.field("x").sourceSlot()==0,"SP001");
        var b=p(s(Origin.CLASS,f("padding",Scalar.I64,0),f("x",Scalar.I64,1)));
        check(b.field("x").sourceSlot()==1,"SP002");
        check(List.of(42L).get(a.field("x").sourceSlot())
            +List.of(10L,42L).get(b.field("x").sourceSlot())==84L,"host84");
        check(b.sourceNominalId()==7&&b.loanId()==9,"source identity");
        check(p(s(Origin.RECORD,f("pad",Scalar.I64,0),f("x",Scalar.I64,1)))
            .field("x").sourceSlot()==1,"SP003 record");
        check(p(s(Origin.STRUCT,f("pad",Scalar.I64,0),f("x",Scalar.I64,1)))
            .field("x").sourceSlot()==1,"SP003 struct");
        reject(()->p(s(Origin.CLASS,new Field("x",Scalar.I64,0,false,true,true,false))),"SP004");
        reject(()->p(s(Origin.CLASS,f("y",Scalar.I64,0))),"SP005");
        reject(()->p(s(Origin.CLASS,f("x",Scalar.BOOL,0))),"SP006");
        reject(()->p(new Source(Origin.CLASS,7,1,9,true,
            List.of(f("x",Scalar.I64,0)))),"SP007");
        reject(()->p(s(Origin.CLASS,new Field("x",Scalar.I64,0,true,true,true,true))),"SP007 field");
        reject(()->p(s(Origin.MAP,f("x",Scalar.I64,0))),"SP008");
        reject(()->p(s(Origin.ACTOR,f("x",Scalar.I64,0))),"SP008 actor");
        reject(()->p(s(Origin.CLASS,new Field("x",Scalar.I64,0,true,false,true,false))),"SP009");
        reject(()->p(new Source(Origin.CLASS,7,0,9,false,
            List.of(f("x",Scalar.I64,0)))),"SP010");
        reject(()->p(new Source(Origin.CLASS,7,1,0,false,
            List.of(f("x",Scalar.I64,0)))),"SP011");
        reject(()->p(s(Origin.CLASS,f("x",Scalar.I64,0),f("x",Scalar.I64,1))),"SP012");
        reject(()->p(s(Origin.CLASS,f("x",Scalar.I64,0),f("y",Scalar.I64,0))),"SP013");
        reject(()->ReadonlyShapeProjectionV1.prove(s(Origin.CLASS,f("x",Scalar.I64,0)),List.of()),"SP014");
        reject(()->p(new Source(Origin.CLASS,0,1,9,false,
            List.of(f("x",Scalar.I64,0)))),"SP015");
        reject(()->ReadonlyShapeProjectionV1.prove(s(Origin.CLASS,f("x",Scalar.I64,0)),
            List.of(new RequiredField("x",Scalar.I64),new RequiredField("x",Scalar.I64))),"SP016");
        System.out.println("JAVA HOST_WITNESS 16/16 PASS; offset oracle 84; Truffle guest NOT_EXECUTED");
    }
}
