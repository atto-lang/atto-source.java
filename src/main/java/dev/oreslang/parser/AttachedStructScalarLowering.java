package dev.oreslang.parser;

import dev.oreslang.compiler.OresCompiler;
import java.util.*;
import static dev.oreslang.parser.Token.Type.*;

/** Opt-in bounded nominal source lowering into the ordinary checked scalar
 * guest compiler. No guest evaluation, class allocation, or physical ABI. */
public final class AttachedStructScalarLowering {
    private AttachedStructScalarLowering() {}
    public static String lowerChecked(String source) {
        String lowered = new Reader(source).lower();
        OresCompiler.parseAndTypeCheckNumericProfile(lowered,true);
        return lowered;
    }
    private record Field(String name,String type) {}
    private record Leaf(String path,String type) {}
    private record Init(String field,String parameter) {}
    private record Ctor(List<Field> parameters,List<Init> assignments) {}
    private record Method(String internal,String type) {}
    private record Write(String path,String helper) {}
    private record Mutation(List<Field> parameters,List<Write> writes) {}
    private record Expr(String code,String type) {}
    private record Value(Owner owner,Map<String,Expr> slots,boolean mutable) {}
    private record Arg(String type,Map<String,Expr> slots) {}
    private static final class Owner {
        final String name;
        final List<Field> fields=new ArrayList<>();
        final List<Leaf> leaves=new ArrayList<>();
        final List<Ctor> ctors=new ArrayList<>();
        final Map<String,Method> methods=new LinkedHashMap<>();
        final Map<String,Mutation> mutations=new LinkedHashMap<>();
        Owner(String name){this.name=name;}
    }
    private static final class Reader {
        private final List<Token> tokens;
        private int at,depth,next,methods;
        private final Map<String,Owner> owners=new LinkedHashMap<>();
        private final Map<String,Value> values=new LinkedHashMap<>();
        private final StringBuilder helpers=new StringBuilder(),main=new StringBuilder();
        private static final Set<String> RESERVED=Set.of("int","bool","void","constructor","mtd","with","copy","move","take","borrow","share","main","suspends");
        Reader(String source){
            if(source==null||source.length()>65536)throw new IllegalArgumentException("E_SOURCE_LIMIT: attached scalar source");
            tokens=new Lexer(source).scan();
            if(tokens.size()>16384)throw new IllegalArgumentException("E_SOURCE_LIMIT: attached scalar tokens");
            // Lexer decodes quoted lexemes. Reject their token kinds before
            // interpreting a lexeme as grammar, not just before emission.
            for(Token t:tokens)if(Set.of(STRING,TEMPLATE_LITERAL,SYMBOL_LITERAL,FLOAT,IMAG).contains(t.type()))
                throw new IllegalArgumentException("E_STRUCT_SYNTAX: unsupported literal");
        }
        private Token tok(){return tokens.get(at);}
        private String word(){return tok().lexeme();}
        private boolean eat(String s){if(!word().equals(s))return false;if(tok().type()!=EOF)at++;return true;}
        private void expect(String s){require(eat(s),"E_STRUCT_SYNTAX","expected "+s);}
        private void require(boolean yes,String code,String msg){
            if(!yes)throw new IllegalArgumentException(code+": "+msg+" at "+tok().line()+":"+tok().column());
        }
        private String name(){
            String n=word();require(tok().type()==IDENT&&!RESERVED.contains(n)&&n.matches("[A-Za-z_][A-Za-z_0-9]{0,127}"),"E_STRUCT_SYNTAX","identifier required");
            at++;return n;
        }
        private boolean scalar(String t){return t.equals("int")||t.equals("bool");}
        private String type(){
            if(eat("int"))return "int";if(eat("bool"))return "bool";
            String n=name();require(owners.containsKey(n),"E_TYPE","unknown, forward or cyclic nominal type");return n;
        }
        private List<Leaf> leaves(String t){return scalar(t)?List.of(new Leaf("",t)):owners.get(t).leaves;}
        private String path(String a,String b){return b.isEmpty()?a:a+"."+b;}
        private String slot(){require(next<4096,"E_SOURCE_LIMIT","expanded slot budget");return "atto_struct_slot_"+next++;}
        private Expr snapshot(Expr e){
            String s=slot();main.append("val ").append(e.type).append(' ').append(s).append(" = ").append(e.code).append(";\n");
            require(main.length()<262144,"E_SOURCE_LIMIT","scalar expansion");return new Expr(s,e.type);
        }
        private List<Field> params(){
            List<Field> p=new ArrayList<>();Set<String> seen=new HashSet<>();
            expect("(");if(!eat(")")){
                do{String t=type(),n=name();require(seen.add(n)&&!owners.containsKey(n),"E_DUPLICATE","parameter name");p.add(new Field(n,t));
                    require(p.stream().mapToInt(f->leaves(f.type).size()).sum()<=16,"E_SOURCE_LIMIT","parameter slots");
                }while(eat(","));expect(")");
            }return p;
        }
        private boolean adjacent(String a,String b){
            if(!word().equals(a)||at+1>=tokens.size())return false;
            Token x=tok(),y=tokens.get(at+1);
            return y.lexeme().equals(b)&&x.line()==y.line()&&x.column()+a.length()==y.column();
        }
        private void member(Owner o){
            if(eat("mut")){mutableMember(o);return;}
            if(eat("constructor")){
                List<Field> p=params();List<String> types=p.stream().map(Field::type).toList();
                require(o.ctors.stream().noneMatch(c->c.parameters.stream().map(Field::type).toList().equals(types)),"E_DUPLICATE","constructor signature");
                List<Init> init=new ArrayList<>();Set<String> seen=new HashSet<>();expect("{");
                while(!eat("}")){
                    expect("self");expect(".");String f=name();expect("=");String n=name();expect(";");
                    Field field=o.fields.stream().filter(x->x.name.equals(f)).findFirst().orElse(null);
                    Field parameter=p.stream().filter(x->x.name.equals(n)).findFirst().orElse(null);
                    require(field!=null&&parameter!=null,"E_STRUCT_INIT","whole-field parameter initialization required");
                    require(field.type.equals(parameter.type),"E_TYPE","constructor nominal identity mismatch");
                    require(seen.add(f),"E_STRUCT_INIT","duplicate field initialization");init.add(new Init(f,n));
                }
                require(seen.size()==o.fields.size(),"E_STRUCT_INIT","missing field initialization");o.ctors.add(new Ctor(p,init));
            }else{
                expect("mtd");String n,t;
                if(scalar(word())){
                    t=type();if(!eat("•")){require(adjacent("<","-"),"E_STRUCT_SYNTAX","prefix return separator");at+=2;}
                    n=name();expect("(");expect(")");
                }else{n=name();expect("(");expect(")");require(eat("->")||eat(":"),"E_STRUCT_SYNTAX","postfix return separator");t=type();}
                require(scalar(t),"E_TYPE","scalar result required");
                require(!o.methods.containsKey(n)&&!o.mutations.containsKey(n),"E_DUPLICATE","method signature");
                require(methods<64,"E_SOURCE_LIMIT","total method budget");
                String generated="atto_struct_method_"+methods++;Map<String,Expr> self=new LinkedHashMap<>();List<String> ps=new ArrayList<>();
                for(Leaf l:o.leaves){String s=slot();ps.add(l.type+" "+s);self.put(l.path,new Expr(s,l.type));}
                expect("{");expect("return");Expr result=expr(self,1);expect(";");expect("}");
                require(result.type.equals(t),"E_TYPE","method result mismatch");
                helpers.append("fnc ").append(generated).append('(').append(String.join(",",ps)).append("): ").append(t).append(" { return ").append(result.code).append("; }\n");
                require(helpers.length()<262144,"E_SOURCE_LIMIT","helper expansion");o.methods.put(n,new Method(generated,t));
            }
            require(o.ctors.size()+o.methods.size()+o.mutations.size()<=64,"E_SOURCE_LIMIT","member budget");
        }
        // Mutations create new scalar slot versions, never writable aliases.
        // Each right-hand side executes once and sees previous leaf updates.
        private void mutableMember(Owner o){
            expect("mtd");String n=name();List<Field> formals=params();
            require(formals.stream().allMatch(f->scalar(f.type)),"E_TYPE","scalar mutation parameters");
            require(o.leaves.size()+formals.size()<=16,"E_SOURCE_LIMIT","mutation parameter slots");
            require(!o.methods.containsKey(n)&&!o.mutations.containsKey(n),"E_DUPLICATE","method signature");
            require(eat(":")||eat("->"),"E_STRUCT_SYNTAX","mutable method result separator");
            expect("void");expect("{");List<Write> writes=new ArrayList<>();
            while(!eat("}")){
                if(eat("return")){expect(";");expect("}");break;}
                expect("self");expect(".");String field=name();while(eat("."))field=path(field,name());
                final String target=field;
                Leaf leaf=o.leaves.stream().filter(l->l.path.equals(target)).findFirst().orElse(null);
                require(leaf!=null,"E_TYPE","unknown mutable leaf");expect("=");
                Map<String,Expr> env=new LinkedHashMap<>();List<String> ps=new ArrayList<>();
                for(Leaf l:o.leaves){String slot=slot();ps.add(l.type+" "+slot);env.put(l.path,new Expr(slot,l.type));}
                for(Field f:formals){String slot=slot();ps.add(f.type+" "+slot);env.put("$arg."+f.name,new Expr(slot,f.type));}
                Expr result=expr(env,1);expect(";");
                require(result.type.equals(leaf.type),"E_TYPE","mutable leaf type mismatch");
                require(methods<64,"E_SOURCE_LIMIT","mutation helper budget");
                String generated="atto_struct_method_"+methods++;
                helpers.append("fnc ").append(generated).append('(').append(String.join(",",ps)).append("): ").append(result.type).append(" { return ").append(result.code).append("; }\n");
                require(helpers.length()<262144,"E_SOURCE_LIMIT","helper expansion");
                writes.add(new Write(field,generated));
            }
            require(!writes.isEmpty(),"E_STRUCT_SYNTAX","empty mutation outside profile");
            require(o.ctors.size()+o.methods.size()+o.mutations.size()<64,"E_SOURCE_LIMIT","member budget");
            o.mutations.put(n,new Mutation(formals,writes));
        }
        private void invokeMutation(){
            String name=name();expect(".");String member=name();Value v=values.get(name);
            require(v!=null,"E_TYPE","unknown receiver");
            Mutation mutation=v.owner.mutations.get(member);require(mutation!=null,"E_TYPE","unknown mutable method");
            require(v.mutable,"E_BORROW_MUT_REQUIRED","immutable receiver");
            List<Expr> args=new ArrayList<>();expect("(");
            if(!eat(")")){
                do{
                    require(args.size()<mutation.parameters.size(),"E_TYPE","mutation arity");
                    Expr e=expr(null,1);require(e.type.equals(mutation.parameters.get(args.size()).type),"E_TYPE","mutation argument type");
                    args.add(snapshot(e));
                }while(eat(","));expect(")");
            }
            require(args.size()==mutation.parameters.size(),"E_TYPE","mutation arity");expect(";");
            for(Write write:mutation.writes){
                List<String> operands=new ArrayList<>();
                for(Leaf l:v.owner.leaves)operands.add(v.slots.get(l.path).code);
                for(Expr e:args)operands.add(e.code);
                String type=v.slots.get(write.path).type;
                v.slots.put(write.path,snapshot(new Expr(write.helper+"("+String.join(",",operands)+")",type)));
            }
        }
        private void declaration(){
            String n;if(eat("define")){expect("struct");n=name();}else{expect("type");n=name();expect("=");expect("struct");}
            require(!owners.containsKey(n)&&owners.size()<16,"E_DUPLICATE","duplicate or excessive owner");
            Owner o=new Owner(n);expect("{");
            while(!eat("}")){
                String t=type(),f=name();expect(";");
                require(o.fields.stream().noneMatch(x->x.name.equals(f)),"E_DUPLICATE","field");o.fields.add(new Field(f,t));
                for(Leaf l:leaves(t))o.leaves.add(new Leaf(path(f,l.path),l.type));
                require(o.leaves.size()<=16,"E_SOURCE_LIMIT","flattened field slots");
            }
            require(!o.fields.isEmpty(),"E_TYPE","empty owner");
            do{expect("with");if(eat("{")){require(!word().equals("}"),"E_STRUCT_SYNTAX","empty group");while(!eat("}"))member(o);}else member(o);}while(word().equals("with"));
            require(!o.ctors.isEmpty(),"E_STRUCT_INIT","constructor required");owners.put(n,o);
        }
        private String op(){
            if(adjacent("&","&"))return "&&";
            if(adjacent("|","|"))return "||";
            return word();
        }
        private int precedence(String op){return switch(op){
            case "||"->1;case "&&"->2;case "==","!=","!eq"->3;case "<",">","<=",">="->4;case "+","-"->5;case "*"->6;default->0;};}
        private Expr expr(Map<String,Expr> self,int min){
            require(++depth<=64,"E_SOURCE_LIMIT","expression depth");
            try{
                Expr a=atom(self);
                int chain=0;
                while(precedence(op())>=min){
                    require(++chain<=32,"E_SOURCE_LIMIT","scalar expression chain");
                    String op=op();int prec=precedence(op);at+=op.equals("&&")||op.equals("||")?2:1;
                    Expr b=expr(self,prec+1);require(a.type.equals(b.type),"E_TYPE","mixed scalar operands");
                    boolean arith=Set.of("+","-","*").contains(op),logic=Set.of("&&","||").contains(op),cmp=Set.of("<",">","<=",">=").contains(op);
                    require((!arith&&!cmp||a.type.equals("int"))&&(!logic||a.type.equals("bool")),"E_TYPE","invalid scalar operands");
                    a=new Expr("("+a.code+" "+op+" "+b.code+")",arith?"int":"bool");
                    require(a.code.length()<65536,"E_SOURCE_LIMIT","expression expansion");
                }return a;
            }finally{depth--;}
        }
        private Expr atom(Map<String,Expr> self){
            if(eat("(")){Expr a=expr(self,1);expect(")");return a;}
            if(eat("-")){Expr a=expr(self,7);require(a.type.equals("int"),"E_TYPE","integer negation");return new Expr("(-"+a.code+")","int");}
            if(eat("true"))return new Expr("true","bool");if(eat("false"))return new Expr("false","bool");
            if(tok().type()==INT){
                String raw=word();require(raw.matches("[0-9]{1,19}"),"E_STRUCT_SYNTAX","decimal i64 literal required");
                long n;try{n=Long.parseLong(raw);}catch(NumberFormatException ex){throw new IllegalArgumentException("E_TYPE: i64 literal overflow");}
                at++;return new Expr(Long.toString(n),"int");
            }
            String root=eat("self")?"self":name();
            if(self!=null&&!root.equals("self")&&self.containsKey("$arg."+root))return self.get("$arg."+root);
            expect(".");String f=name();
            Value v=values.get(root);
            if(self==null&&v!=null&&eat("(")){
                expect(")");Method m=v.owner.methods.get(f);require(m!=null,"E_TYPE","unknown nominal method");
                return new Expr(m.internal+"("+String.join(",",v.owner.leaves.stream().map(l->v.slots.get(l.path).code).toList())+")",m.type);
            }
            while(eat("."))f=path(f,name());
            Expr result=self!=null&&root.equals("self")?self.get(f):self==null&&v!=null?v.slots.get(f):null;
            require(result!=null,"E_TYPE","unknown scalar projection");return result;
        }
        private void binding(){
            expect("let");boolean mutable=eat("mut");String first=name(),n=first;Owner annotation=owners.get(first);if(annotation!=null)n=name();
            require(!owners.containsKey(n)&&!values.containsKey(n),"E_DUPLICATE","local shadow");
            expect("=");boolean copy=adjacent("copy","!");if(copy)at+=2;
            Owner o;Map<String,Expr> slots=new LinkedHashMap<>();Value old=values.get(word());
            if(old!=null){
                at++;o=old.owner;for(Leaf l:o.leaves)slots.put(l.path,snapshot(old.slots.get(l.path)));
            }else{
                require(!copy,"E_TYPE","copy requires nominal local");
                o=owners.get(name());require(o!=null,"E_TYPE","unknown constructor owner");
                List<Arg> args=new ArrayList<>();expect("(");
                if(!eat(")")){
                    do{
                        Value v=at+1<tokens.size()&&tokens.get(at+1).lexeme().equals(".")?null:values.get(word());
                        if(v!=null){at++;Map<String,Expr> ss=new LinkedHashMap<>();for(Leaf l:v.owner.leaves)ss.put(l.path,snapshot(v.slots.get(l.path)));args.add(new Arg(v.owner.name,ss));}
                        else{Expr e=snapshot(expr(null,1));args.add(new Arg(e.type,Map.of("",e)));}
                        require(args.stream().mapToInt(a->a.slots.size()).sum()<=16,"E_SOURCE_LIMIT","argument slots");
                    }while(eat(","));expect(")");
                }
                List<String> types=args.stream().map(Arg::type).toList();
                List<Ctor> ctors=o.ctors.stream().filter(c->c.parameters.stream().map(Field::type).toList().equals(types)).toList();
                require(ctors.size()==1,"E_TYPE","no exact nominal constructor");Ctor c=ctors.getFirst();
                for(Init i:c.assignments){
                    int index=0;while(!c.parameters.get(index).name.equals(i.parameter))index++;
                    for(var leaf:args.get(index).slots.entrySet())slots.put(path(i.field,leaf.getKey()),snapshot(leaf.getValue()));
                }
            }
            require(annotation==null||annotation==o,"E_TYPE","nominal binding mismatch");expect(";");values.put(n,new Value(o,slots,mutable));
        }
        String lower(){
            while(word().equals("define")||word().equals("type"))declaration();
            require(!owners.isEmpty(),"E_STRUCT_SYNTAX","attached struct required");
            eat("pub");expect("fnc");expect("main");expect("(");expect(")");
            require(eat(":")||eat("->"),"E_STRUCT_SYNTAX","main return separator");expect("int");expect("{");
            while(!word().equals("return")){if(word().equals("let"))binding();else invokeMutation();}
            expect("return");Expr result=expr(null,1);require(result.type.equals("int"),"E_TYPE","main result");expect(";");expect("}");require(tok().type()==EOF,"E_STRUCT_SYNTAX","trailing input");
            return helpers+"pub fnc main(): int {\n"+main+"return "+result.code+";\n}\n";
        }
    }
}
