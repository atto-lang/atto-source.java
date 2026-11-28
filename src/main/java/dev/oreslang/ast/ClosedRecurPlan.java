package dev.oreslang.ast;

import java.util.ArrayList;
import java.util.List;

/** Closed scalar loop plan: construction is the only way to mint executable
 * operands. No dynamic names, calls, ownership transfers or external captures. */
public final class ClosedRecurPlan {
    private enum Code { CONST, SLOT, NEG, ADD, SUB, MUL, EQ, NE, LT, LE, GT, GE }
    private record Op(Code code, long value, Op left, Op right, boolean predicate) {
        long run(long[] state) {
            return switch (code) {
                case CONST -> value;
                case SLOT -> state[(int)value];
                case NEG -> Math.negateExact(left.run(state));
                case ADD -> Math.addExact(left.run(state), right.run(state));
                case SUB -> Math.subtractExact(left.run(state), right.run(state));
                case MUL -> Math.multiplyExact(left.run(state), right.run(state));
                case EQ -> left.run(state) == right.run(state) ? 1 : 0;
                case NE -> left.run(state) != right.run(state) ? 1 : 0;
                case LT -> left.run(state) < right.run(state) ? 1 : 0;
                case LE -> left.run(state) <= right.run(state) ? 1 : 0;
                case GT -> left.run(state) > right.run(state) ? 1 : 0;
                case GE -> left.run(state) >= right.run(state) ? 1 : 0;
            };
        }
    }
    private final Op[] initial, guards, results, updates;
    private ClosedRecurPlan(Op[] initial, Op[] guards, Op[] results, Op[] updates) {
        this.initial = initial; this.guards = guards; this.results = results; this.updates = updates;
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("E_VALUE_LOOP_PROFILE: invalid closed integer recur operand or shape");
    }
    private static Op operand(Ast.Expr e, List<String> names, int depth) {
        if (depth > 32) throw invalid();
        if (e instanceof Ast.LiteralExpr literal) {
            if (literal.value() instanceof Long n) return new Op(Code.CONST,n,null,null,false);
            if (literal.value() instanceof Boolean b) return new Op(Code.CONST,b?1:0,null,null,true);
        }
        if (e instanceof Ast.NameExpr name) {
            int slot=names.indexOf(name.name());
            if (slot>=0) return new Op(Code.SLOT,slot,null,null,false);
        }
        if (e instanceof Ast.UnaryExpr unary && unary.operator().equals("-")) {
            Op x=operand(unary.operand(),names,depth+1);
            if (x.predicate) throw invalid();
            return new Op(Code.NEG,0,x,null,false);
        }
        if (e instanceof Ast.BinaryExpr binary) {
            Op x=operand(binary.left(),names,depth+1), y=operand(binary.right(),names,depth+1);
            if (x.predicate || y.predicate) throw invalid();
            Code c=switch(binary.operator()) {
                case "+" -> Code.ADD; case "-" -> Code.SUB; case "*" -> Code.MUL;
                case "eq" -> Code.EQ; case "neq" -> Code.NE;
                case "<" -> Code.LT; case "<=" -> Code.LE; case ">" -> Code.GT; case ">=" -> Code.GE;
                default -> throw invalid();
            };
            return new Op(c,0,x,y,c.ordinal()>=Code.EQ.ordinal());
        }
        throw invalid();
    }
    private static Op typed(Ast.Expr e,List<String> names,boolean predicate) {
        Op op=operand(e,names,0);
        if (op.predicate!=predicate) throw invalid();
        return op;
    }
    public static ClosedRecurPlan create(List<String> names,List<Ast.Expr> initial,
            List<Ast.Expr> guards,List<Ast.Expr> results,List<Ast.Expr> updates) {
        int n=names.size(), m=guards.size();
        if(n<1||n>8||initial.size()!=n||updates.size()!=n||m<1||m>32||results.size()!=m) throw invalid();
        List<String> scope=new ArrayList<>();
        Op[] init=new Op[n], test=new Op[m], out=new Op[m], next=new Op[n];
        for(int i=0;i<n;i++) {
            String name=names.get(i);
            if(name==null||name.isEmpty()||scope.contains(name)) throw invalid();
            init[i]=typed(initial.get(i),scope,false);
            scope.add(name);
        }
        for(int i=0;i<m;i++) {
            test[i]=typed(guards.get(i),scope,true);
            out[i]=typed(results.get(i),scope,false);
        }
        for(int i=0;i<n;i++) next[i]=typed(updates.get(i),scope,false);
        return new ClosedRecurPlan(init,test,out,next);
    }
    /** Two primitive arrays per invocation; no per-iteration boxing or copying
     * of the old state. Every next-state operand sees the same old array. */
    public long execute() {
        long[] state=new long[initial.length], next=new long[initial.length];
        for(int i=0;i<initial.length;i++) state[i]=initial[i].run(state);
        while(true) {
            for(int i=0;i<guards.length;i++)
                if(guards[i].run(state)!=0) return results[i].run(state);
            for(int i=0;i<updates.length;i++) next[i]=updates[i].run(state);
            long[] old=state; state=next; next=old;
        }
    }
}
