import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Explicit closed unsigned scalar source-to-Java candidate; no signed fallback. */
public final class CheckedU64 {
    private static final Pattern TOKEN = Pattern.compile("[ \\t\\r\\n]+|[A-Za-z_][A-Za-z0-9_]*|[0-9]+|[(){}:,;+*\\-]");
    private static final Set<String> RESERVED = Set.of("pub", "fnc", "return", "uint", "u64");
    private record Expr(String kind, String value, List<Expr> args) {}
    private record Fn(String name, boolean pub, List<String> params, Expr body, int index) {}
    private final List<String> tokens = new ArrayList<>();
    private final Map<String,Fn> funcs = new LinkedHashMap<>();
    private final Map<String,Integer> costs = new HashMap<>();
    private final Set<String> active = new HashSet<>();
    private int pos, nodes;
    private static IllegalArgumentException bad(String why) { return new IllegalArgumentException(why); }
    private String peek() { return pos < tokens.size() ? tokens.get(pos) : ""; }
    private boolean eat(String s) { if (!peek().equals(s)) return false; pos++; return true; }
    private void need(String s) { if (!eat(s)) throw bad("expected " + s); }
    private String name() {
        String n = peek();
        if (n.length() > 64 || !n.matches("[A-Za-z_][A-Za-z0-9_]*") || RESERVED.contains(n)) throw bad("identifier");
        pos++; return n;
    }
    private void type() { if (!eat("u64") && !eat("uint")) throw bad("unsigned type required"); }
    private Expr node(String kind, String value, List<Expr> args) {
        if (++nodes > 1024) throw bad("node budget");
        return new Expr(kind,value,List.copyOf(args));
    }
    private Expr expr(int depth, int min) {
        if (depth > 32) throw bad("expression depth");
        Expr lhs;
        if (eat("(")) { lhs = expr(depth+1,1); need(")"); }
        else if (peek().matches("0|[1-9][0-9]*")) {
            String s = tokens.get(pos++);
            if (s.length() > 20) throw bad("literal range");
            long bits = Long.parseUnsignedLong(s);
            lhs = node("literal", "0x"+Long.toHexString(bits)+"L", List.of());
        } else {
            String id = name();
            if (eat("(")) {
                var args = new ArrayList<Expr>();
                if (!eat(")")) {
                    do { if (args.size()==16) throw bad("arity budget"); args.add(expr(depth+1,1)); } while (eat(","));
                    need(")");
                }
                lhs = node("call",id,args);
            } else lhs = node("param",id,List.of());
        }
        while (true) {
            String op = peek(); int prec = op.equals("*") ? 2 : op.equals("+") || op.equals("-") ? 1 : 0;
            if (prec < min) return lhs;
            pos++; lhs = node("binary",op,List.of(lhs,expr(depth+1,prec+1)));
        }
    }
    private CheckedU64(String source) {
        var m = TOKEN.matcher(source);
        for (int i=0; i<source.length();) {
            m.region(i,source.length());
            if (!m.lookingAt()) throw bad("unsupported source byte");
            String s = m.group(); i=m.end();
            if (s.charAt(0)==' ' || s.charAt(0)=='\t' || s.charAt(0)=='\r' || s.charAt(0)=='\n') continue;
            tokens.add(s); if (tokens.size()>2048) throw bad("token budget");
        }
        while (pos<tokens.size()) {
            if (funcs.size()==32) throw bad("function budget");
            boolean pub=eat("pub"); need("fnc"); String id=name();
            if (funcs.containsKey(id)) throw bad("duplicate function");
            need("("); var params=new ArrayList<String>();
            if (!eat(")")) {
                do { if(params.size()==16) throw bad("arity budget"); type(); String p=name(); if(params.contains(p)) throw bad("duplicate parameter"); params.add(p); } while(eat(","));
                need(")");
            }
            need(":"); type(); need("{"); need("return"); Expr body=expr(0,1); need(";"); need("}");
            funcs.put(id,new Fn(id,pub,List.copyOf(params),body,funcs.size()));
        }
        Fn main=funcs.get("main");
        if(main==null || !main.pub() || !main.params().isEmpty()) throw bad("public zero-arity main required");
        for(Fn f:funcs.values()) cost(f);
    }
    private int cost(Fn f) {
        if(costs.containsKey(f.name())) return costs.get(f.name());
        if(!active.add(f.name())) throw bad("recursive call graph");
        int c=visit(f.body(),f,0); active.remove(f.name()); costs.put(f.name(),c); return c;
    }
    private int visit(Expr n,Fn f,int depth) {
        if(depth>64) throw bad("tree depth");
        int c=1;
        if(n.kind().equals("param") && !f.params().contains(n.value())) throw bad("unknown parameter");
        for(Expr x:n.args()) { c+=visit(x,f,depth+1); if(c>10000) throw bad("work budget"); }
        if(n.kind().equals("call")) {
            Fn target=funcs.get(n.value());
            if(target==null || target.params().size()!=n.args().size()) throw bad("unknown call or arity");
            c+=cost(target);
        }
        if(c>10000) throw bad("work budget"); return c;
    }
    private String emit(Expr n,Fn f) {
        if(n.kind().equals("literal")) return n.value();
        if(n.kind().equals("param")) return "p"+f.params().indexOf(n.value());
        String target=n.kind().equals("call")?"f"+funcs.get(n.value()).index():switch(n.value()) {case "+" -> "add";case "-" -> "sub";default -> "mul";};
        var args=new StringJoiner(","); for(Expr x:n.args()) args.add(emit(x,f));
        return target+"("+args+")";
    }
    private String output() {
        var out=new StringBuilder("""
            public final class AttoGuest {
                private static long add(long a,long b) { if(Long.compareUnsigned(b,~a)>0) throw new ArithmeticException("E_U64_OVERFLOW"); return a+b; }
                private static long sub(long a,long b) { if(Long.compareUnsigned(b,a)>0) throw new ArithmeticException("E_U64_OVERFLOW"); return a-b; }
                private static long mul(long a,long b) { if(b!=0 && Long.compareUnsigned(a,Long.divideUnsigned(-1L,b))>0) throw new ArithmeticException("E_U64_OVERFLOW"); return a*b; }
            """);
        for(Fn f:funcs.values()) {
            var params=new StringJoiner(","); for(int i=0;i<f.params().size();i++) params.add("long p"+i);
            out.append("private static long f").append(f.index()).append('(').append(params).append("){return ").append(emit(f.body(),f)).append(";}\n");
        }
        out.append("public static long guest(){return f").append(funcs.get("main").index()).append("();}\n");
        out.append("public static void main(String[] args){try{System.out.println(Long.toUnsignedString(guest()));}catch(ArithmeticException e){System.err.println(e.getMessage());System.exit(70);}}\n}\n");
        if(out.length()>131072) throw bad("output budget"); return out.toString();
    }
    public static void main(String[] args) throws IOException {
        try {
            byte[] bytes=System.in.readNBytes(16385);
            if(bytes.length>16384) throw bad("source budget");
            for(byte b:bytes) if(b<0) throw bad("non-ASCII source");
            String generated=new CheckedU64(new String(bytes,StandardCharsets.US_ASCII)).output();
            System.out.print(generated);
            if(System.out.checkError()) throw new IOException("output write");
        } catch(IllegalArgumentException e) { System.err.println("E_U64_PROFILE: "+e.getMessage()); System.exit(2); }
    }
}
