package dev.oreslang.parser;

import java.util.*;
import static dev.oreslang.parser.Token.Type.*;

/** Closed integer contextual source lowering. Existing parser/type checker and
 * guest compiler own executable semantics; no resource capture or reflection. */
final class ContextualIntLowering {
    private static final String PREFIX = "atto_context_v1_";
    private static final Set<Token.Type> ADMITTED = Set.of(DEFINE,CLASS,AS,PUB,STATIC,FNC,END,
        CONST,RETURN,IDENT,INT,LPAREN,RPAREN,LBRACE,RBRACE,COMMA,DOT,SEMICOLON,
        DOUBLE_COLON,COLON,ARROW,EQUAL,PLUS,MINUS,STAR,SLASH,EOF);
    private static final Set<String> RESERVED = Set.of("main","int","bool","void","new","make",
        "self","constructor","mtd","with","set","copy","move","take","borrow","share","rt",
        "stdio","process","Pointer","Result","Option","Integer","Symbol");
    static List<Token> lower(List<Token> tokens) {
        boolean present=false;
        for(int i=0;i+3<tokens.size();i++)
            if(tokens.get(i).type()==FNC && tokens.get(i+1).type()==IDENT &&
                tokens.get(i+2).type()==DOUBLE_COLON && tokens.get(i+3).type()==LPAREN) {present=true;break;}
        if(!present)return tokens;
        return new Reader(tokens).lower();
    }
    private static final class Reader {
        final List<Token> input;
        final StringBuilder out=new StringBuilder();
        final Map<String,List<String>> aliases=new LinkedHashMap<>();
        final Set<String> locals=new HashSet<>();
        int at,slots,depth;
        String owner,member;
        int contextCount,ordinaryCount;
        Reader(List<Token> input) {
            this.input=input;
            if(input.size()>8192)throw deny("token budget");
            for(Token t:input) {
                if(!ADMITTED.contains(t.type())||t.lexeme().startsWith(PREFIX))
                    throw deny("unsupported or reserved contextual token");
            }
        }
        Token token(){return input.get(Math.min(at,input.size()-1));}
        IllegalArgumentException deny(String msg){return new IllegalArgumentException("E_CONTEXT_PROFILE: "+msg+" at "+token().line()+":"+token().column());}
        boolean eat(Token.Type t){if(token().type()!=t)return false;if(t!=EOF)at++;return true;}
        void need(Token.Type t){if(!eat(t))throw deny("expected "+t);}
        void word(String s){if(token().type()!=IDENT||!token().lexeme().equals(s))throw deny("expected "+s);at++;}
        String name(){
            String s=token().lexeme();
            if(token().type()!=IDENT||!s.matches("[A-Za-z_][A-Za-z_0-9]{0,127}")||RESERVED.contains(s))
                throw deny("invalid contextual name");
            at++;return s;
        }
        List<String> params(){
            need(LPAREN);List<String> result=new ArrayList<>();
            if(!eat(RPAREN)){
                do{word("int");result.add(name());if(result.size()>8)throw deny("parameter limit");}while(eat(COMMA));
                need(RPAREN);
            }
            return result;
        }
        String plain(boolean calls) {
            if(++depth>64)throw deny("expression depth");
            try{
                String expr=term(calls);
                while(token().type()==PLUS||token().type()==MINUS){
                    String op=token().lexeme();at++;expr+=" "+op+" "+term(calls);
                }
                if(expr.length()>32768)throw deny("expression budget");
                return expr;
            }finally{depth--;}
        }
        String term(boolean calls){
            String s=atom(calls);
            while(token().type()==STAR||token().type()==SLASH){
                String op=token().lexeme();at++;s+=" "+op+" "+atom(calls);
                if(s.length()>32768)throw deny("expression budget");
            }
            return s;
        }
        String atom(boolean calls){
            if(++depth>64)throw deny("expression depth");
            try{
                if(eat(MINUS))return "- "+atom(calls);
                if(eat(LPAREN)){String s=plain(calls);need(RPAREN);return "( "+s+" )";}
                if(token().type()==INT){String s=token().lexeme();if(!s.matches("[0-9]{1,64}"))throw deny("integer literal");at++;return s;}
                if(token().type()!=IDENT)throw deny("integer operand required");
                String n=token().lexeme();at++;
                if(calls&&(n.equals(owner)||aliases.containsKey(n))){
                    List<String> args=new ArrayList<>();
                    if(n.equals(owner)){need(DOUBLE_COLON);args.addAll(args(contextCount));}
                    else args.addAll(aliases.get(n));
                    need(DOT);if(!name().equals(member))throw deny("unknown contextual member");
                    args.addAll(args(ordinaryCount));
                    return PREFIX+"member("+String.join(", ",args)+")";
                }
                if(!locals.contains(n))throw deny("unresolved scalar operand");
                return n;
            }finally{depth--;}
        }
        List<String> args(int count){
            need(LPAREN);List<String> result=new ArrayList<>();
            if(!eat(RPAREN)){
                do{result.add(plain(false));if(result.size()>count)throw deny("argument arity");}while(eat(COMMA));
                need(RPAREN);
            }
            if(result.size()!=count)throw deny("argument arity");
            return result;
        }
        List<Token> lower(){
            need(DEFINE);need(CLASS);owner=name();need(AS);need(PUB);need(STATIC);need(FNC);
            member=name();need(DOUBLE_COLON);
            List<String> captures=params(),ordinary=params();
            contextCount=captures.size();ordinaryCount=ordinary.size();
            List<String> all=new ArrayList<>(captures);all.addAll(ordinary);
            if(contextCount<1||contextCount>8||all.size()>16||new HashSet<>(all).size()!=all.size()||all.contains(owner))
                throw deny("invalid context schema");
            locals.addAll(all);
            if(!eat(ARROW))need(COLON);word("int");need(LBRACE);need(RETURN);
            String body=plain(false);need(SEMICOLON);need(RBRACE);need(END);
            out.append("fnc ").append(PREFIX).append("member(");
            for(int i=0;i<all.size();i++){if(i>0)out.append(", ");out.append("int ").append(all.get(i));}
            out.append("):int { return ").append(body).append("; }\n");
            locals.clear();
            need(FNC);word("main");need(LPAREN);need(RPAREN);need(ARROW);word("int");need(LBRACE);
            out.append("pub fnc main():int {\n");
            while(eat(CONST)){
                String n=name();
                if(n.equals(owner)||aliases.containsKey(n)||aliases.size()>=64)throw deny("duplicate or excessive alias");
                need(EQUAL);
                if(token().type()==IDENT&&aliases.containsKey(token().lexeme())){
                    aliases.put(n,aliases.get(token().lexeme()));at++;need(SEMICOLON);continue;
                }
                word(owner);need(DOUBLE_COLON);List<String> values=args(contextCount);need(SEMICOLON);
                List<String> captured=new ArrayList<>();
                for(String v:values){
                    String slot=PREFIX+"slot_"+slots++;
                    out.append("val int ").append(slot).append(" = ").append(v).append(";\n");
                    captured.add(slot);
                }
                aliases.put(n,List.copyOf(captured));
                if(out.length()>65536)throw deny("lowering budget");
            }
            need(RETURN);out.append("return ").append(plain(true)).append("; }");need(SEMICOLON);need(RBRACE);need(EOF);
            if(at!=input.size()-1||out.length()>65536)throw deny("trailing input or output budget");
            return new Lexer(out.toString()).scan();
        }
    }
}
