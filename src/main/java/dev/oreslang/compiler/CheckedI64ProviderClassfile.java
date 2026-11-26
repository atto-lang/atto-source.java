package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.io.*;
import java.util.*;

/** Closed checked signed-64 provider ABI. Separate from realtime i32 audit:
 * overflow throws ArithmeticException, not an RT/WCET qualification.
 * Bytecode is constructed only from compiler-checked, closed AST nodes.
 * No source text interpolated into bytecode or Java source. */
final class CheckedI64ProviderClassfile {
    static final String CLASS_NAME="AttoCheckedI64Provider";
    private static IllegalArgumentException deny(String detail){
        return new IllegalArgumentException("E_NAMESPACE_I64: "+detail);
    }
    private final Ast.Expr expression;
    private final Map<String,Integer> params;
    private final int arity;
    private final List<Long> constants=new ArrayList<>();
    private CheckedI64ProviderClassfile(Ast.Expr expression,Map<String,Integer> params,int arity){
        this.expression=expression;this.params=Map.copyOf(params);this.arity=arity;
    }
    static CheckedI64ProviderClassfile admit(Ast.Program program,String symbol,int arity,RealtimePrimitiveCompiler.Budget budget){
        if(arity<0||arity>4||!program.imports().isEmpty()||!program.exports().isEmpty()||program.modules().size()!=1)
            throw deny("closed module");
        var module=program.modules().getFirst();
        if(!module.annotations().isEmpty()||!module.contracts().isEmpty()||module.declarations().size()!=1||
           !(module.declarations().getFirst() instanceof Ast.FunctionDecl f)||!symbol.equals(f.name())||
           f.visibility()!=Ast.Visibility.PUBLIC||f.kind()!=Ast.CallableKind.FNC||f.actorKind()!=Ast.ActorKind.NONE||
           f.async()||f.suspends()||f.generator()||f.structural()||f.nonLexical()||f.trapped()||
           !f.annotations().isEmpty()||!f.effects().isEmpty()||!f.genericParameters().isEmpty()||
           !Ast.TypeRef.simple("int").equals(f.returnType())||f.parameters().size()!=arity||
           f.body().size()!=1||!(f.body().getFirst() instanceof Ast.ReturnStmt ret))
            throw deny("closed public int function");
        var params=new LinkedHashMap<String,Integer>();
        for(var p:f.parameters()){
            if(p.mutable()||p.structural()||!Ast.TypeRef.simple("int").equals(p.type())||
               params.putIfAbsent(p.name(),params.size())!=null)throw deny("int parameter");
        }
        var out=new CheckedI64ProviderClassfile(ret.value(),params,arity);
        int[] count={0};
        out.validate(ret.value(),0,count);
        if(budget==null||count[0]>budget.maxSourceSteps()||budget.maxCallDepth()<1)throw deny("budget");
        return out;
    }
    private void validate(Ast.Expr e,int depth,int[] count){
        if(depth>32||++count[0]>256)throw deny("expression budget");
        if(e instanceof Ast.LiteralExpr l && (l.value() instanceof Long||l.value() instanceof Integer||
                l.value() instanceof Short||l.value() instanceof Byte))return;
        if(e instanceof Ast.NameExpr n&&params.containsKey(n.name()))return;
        if(e instanceof Ast.UnaryExpr u&&u.operator().equals("-")){validate(u.operand(),depth+1,count);return;}
        if(e instanceof Ast.BinaryExpr b&&Set.of("+","-","*").contains(b.operator())){
            validate(b.left(),depth+1,count);validate(b.right(),depth+1,count);return;
        }
        throw deny("closed signed integer expression");
    }
    private void emit(Ast.Expr e,DataOutputStream out)throws IOException{
        if(e instanceof Ast.LiteralExpr l){
            long value=((Number)l.value()).longValue();
            int idx=constants.indexOf(value);
            if(idx<0){idx=constants.size();constants.add(value);}
            out.writeByte(0x14);out.writeShort(24+2*idx);return; // ldc2_w
        }
        if(e instanceof Ast.NameExpr n){out.writeByte(0x16);out.writeByte(2*params.get(n.name()));return;} // lload
        if(e instanceof Ast.UnaryExpr u){emit(u.operand(),out);out.writeByte(0xb8);out.writeShort(23);return;}
        if(e instanceof Ast.BinaryExpr b){
            emit(b.left(),out);emit(b.right(),out);out.writeByte(0xb8);
            out.writeShort(switch(b.operator()){case "+"->20;case "-"->21;case "*"->22;default->throw deny("operator");});return;
        }
        throw deny("unadmitted expression");
    }
    private static void utf(DataOutputStream d,String s)throws IOException{d.writeByte(1);d.writeUTF(s);}
    private static void one(DataOutputStream d,int tag,int a)throws IOException{d.writeByte(tag);d.writeShort(a);}
    private static void two(DataOutputStream d,int tag,int a,int b)throws IOException{d.writeByte(tag);d.writeShort(a);d.writeShort(b);}
    byte[] emitClassfile()throws IOException{
        constants.clear();
        var code=new ByteArrayOutputStream();
        try(var d=new DataOutputStream(code)){emit(expression,d);d.writeByte(0xad);} // lreturn
        var file=new ByteArrayOutputStream();
        try(var d=new DataOutputStream(file)){
            d.writeInt(0xcafebabe);d.writeShort(0);d.writeShort(52); // Java 8
            d.writeShort(24+2*constants.size());
            utf(d,CLASS_NAME);one(d,7,1);utf(d,"java/lang/Object");one(d,7,3);
            utf(d,"java/lang/Math");one(d,7,5);utf(d,"Code");utf(d,"invoke");
            utf(d,"("+"J".repeat(arity)+")J");
            utf(d,"addExact");utf(d,"subtractExact");utf(d,"multiplyExact");utf(d,"negateExact");
            utf(d,"(JJ)J");utf(d,"(J)J");
            for(int i=0;i<4;i++)two(d,12,10+i,i==3?15:14);
            for(int i=0;i<4;i++)two(d,10,6,16+i);
            for(long n:constants){d.writeByte(5);d.writeLong(n);}
            d.writeShort(0x0031);d.writeShort(2);d.writeShort(4); // public final super
            d.writeShort(0);d.writeShort(0);d.writeShort(1); // interfaces, fields, methods
            d.writeShort(0x0009);d.writeShort(8);d.writeShort(9);d.writeShort(1); // public static invoke
            d.writeShort(7);d.writeInt(12+code.size());
            d.writeShort(66);d.writeShort(2*arity);d.writeInt(code.size());d.write(code.toByteArray());
            d.writeShort(0);d.writeShort(0);d.writeShort(0); // exceptions, nested attrs, class attrs
        }
        return file.toByteArray();
    }
}
