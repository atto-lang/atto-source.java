package dev.oreslang.imports;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import java.util.*;

/** All input bytes were authenticated before this linker runs. Only the
 * leading typed import declarations are lowered; provider bodies remain
 * verbatim and the ordinary strict compiler checks the complete graph. */
final class FrozenProviderLinker {
    record Key(String owner,String namespace) {}
    record Linked(Map<Key,String> units,Map<String,String> sources,
                  Map<String,Map<String,String>> resolutions,
                  Map<String,StaticImportGraph.View> views) {}
    private static IllegalArgumentException fail(String code,String message) {
        return new IllegalArgumentException(code+": "+message);
    }
    static Linked link(FrozenDependencyGraph closure,Map<String,FrozenImportSnapshot> available) {
        var units=new LinkedHashMap<Key,String>();
        var sources=new LinkedHashMap<String,String>();
        var resolutions=new LinkedHashMap<String,Map<String,String>>();
        var views=new LinkedHashMap<String,StaticImportGraph.View>();
        int methods=0;
        for(String identity:closure.dependencyOrder()) {
            var owner=available.get(identity);
            var view=owner.linkedFunctionView();
            for(String ns:owner.namespaceNames()) {
                if(units.size()>=16) throw fail("E_SOURCE_LIMIT","provider namespace budget exceeded");
                String unit="pinned/provider_"+units.size()+".atto";
                String original=owner.providerSource(ns);
                var parsed=Parser.parseStrictAtto(original);
                for(var module:parsed.modules()) {
                    for(var decl:module.declarations()) {
                        if(++methods>64) throw fail("E_SOURCE_LIMIT","linked method budget exceeded");
                        if(decl instanceof Ast.FunctionDecl f && f.name().equals("main"))
                            throw fail("E_UNSUPPORTED","provider main remains outside bounded profile");
                    }
                }
                var edges=new LinkedHashMap<String,String>();
                var bindings=new HashSet<String>();
                var lowered=new ArrayList<String>();
                if(parsed.imports().size()>32) throw fail("E_SOURCE_LIMIT","provider import budget exceeded");
                for(var imported:parsed.imports()) {
                    if(imported.kind()!=Ast.ImportKind.FUNCTION||imported.wildcard()||
                        imported.names().size()!=1||!imported.path().startsWith(ImportRules.PACKAGE_PREFIX))
                        throw fail("E_UNSUPPORTED","provider requires explicit typed function import from package subpath");
                    String member=imported.names().getFirst();
                    String binding=ImportRules.localName(imported,member);
                    if(!ContextualImportPath.valid(member)||!ContextualImportPath.valid(binding))
                        throw fail("E_IMPORT_PATH","invalid provider callable name");
                    if(!bindings.add(binding)) throw fail("E_DUPLICATE","provider import binding");
                    String[] parts=imported.path().substring(ImportRules.PACKAGE_PREFIX.length()).split("/",-1);
                    if(parts.length<4) throw fail("E_IMPORT_PATH","provider function import requires exported subpath");
                    String dependency=String.join("/",Arrays.copyOf(parts,3));
                    var target=closure.requireDependency(identity,dependency);
                    String selected=target.namespaceForSubpath(String.join("/",Arrays.copyOfRange(parts,3,parts.length)));
                    var targetView=views.get(target.name());
                    if(targetView==null||targetView.lookup(selected+"."+member).kind()!=StaticImportGraph.Kind.FNC)
                        throw fail("E_IMPORT_KIND","expected public dependency callable");
                    String targetUnit=units.get(new Key(target.name(),selected));
                    if(targetUnit==null) throw fail("E_IMPORT_PATH","dependency provider not linked");
                    String path="./"+targetUnit.substring("pinned/".length());
                    edges.put(path,targetUnit);
                    lowered.add("import fnc "+member+(binding.equals(member)?"":" as "+binding)+" from \""+path+"\";");
                }
                for(var module:parsed.modules())
                    for(var decl:module.declarations())
                        if(decl instanceof Ast.FunctionDecl f && bindings.contains(f.name()))
                            throw fail("E_DUPLICATE","provider callable shadows imported binding");
                String source=replaceImports(original,parsed.imports(),lowered);
                var checked=Parser.parseStrictAtto(source);
                if(!Objects.equals(parsed.namespace(),checked.namespace())||
                   !parsed.modules().equals(checked.modules())||!parsed.exports().equals(checked.exports())||
                   checked.imports().size()!=lowered.size())
                    throw fail("E_IMPORT_PATH","provider lowering changed original declarations");
                units.put(new Key(identity,ns),unit);
                sources.put(unit,source);
                resolutions.put(unit,Map.copyOf(edges));
            }
            views.put(identity,view);
        }
        return new Linked(Map.copyOf(units),Collections.unmodifiableMap(sources),
            Collections.unmodifiableMap(resolutions),Map.copyOf(views));
    }

    private static String replaceImports(String original,List<Ast.ImportDecl> imports,List<String> lowered) {
        if(imports.isEmpty()) return original;
        var tokens=new Lexer(original).scan();
        int i=0;
        if(tokens.get(i).type()==Token.Type.IDENT&&tokens.get(i).lexeme().equals("use")) i++;
        if(tokens.get(i++).type()!=Token.Type.NAMESPACE) throw fail("E_IMPORT_PATH","expected namespace header");
        while(tokens.get(i).type()!=Token.Type.SEMICOLON) {
            if(tokens.get(i++).type()==Token.Type.EOF) throw fail("E_IMPORT_PATH","unterminated namespace header");
        }
        i++;
        int first=offset(original,tokens.get(i)),last=first;
        for(var imported:imports) {
            if(tokens.get(i++).type()!=Token.Type.IMPORT||tokens.get(i++).type()!=Token.Type.FNC)
                throw fail("E_UNSUPPORTED","expected single typed function import");
            var member=tokens.get(i++);
            if(member.type()!=Token.Type.IDENT||!member.lexeme().equals(imported.names().getFirst()))
                throw fail("E_UNSUPPORTED","grouped provider import unsupported");
            if(tokens.get(i).type()==Token.Type.AS) {
                i++;
                var binding=tokens.get(i++);
                if(binding.type()!=Token.Type.IDENT||!binding.lexeme().equals(imported.namespace()))
                    throw fail("E_IMPORT_PATH","invalid provider alias");
            }
            if(tokens.get(i++).type()!=Token.Type.FROM||!tokens.get(i).lexeme().equals("pkg"))
                throw fail("E_UNSUPPORTED","expected pinned package import");
            i++;
            var target=tokens.get(i++);
            if(target.type()!=Token.Type.STRING||!imported.path().equals(ImportRules.PACKAGE_PREFIX+target.lexeme()))
                throw fail("E_IMPORT_PATH","provider import source mismatch");
            var end=tokens.get(i++);
            if(end.type()!=Token.Type.SEMICOLON) throw fail("E_IMPORT_PATH","expected import terminator");
            last=offset(original,end)+1;
        }
        // Preserve line count of the declaration region for body diagnostics.
        String replacement=String.join(" ",lowered);
        long lines=original.substring(first,last).chars().filter(c->c=='\n').count();
        return original.substring(0,first)+replacement+"\n".repeat((int)lines)+original.substring(last);
    }
    private static int offset(String source,Token token) {
        int pos=0;
        for(int line=1;line<token.line();line++) {
            pos=source.indexOf('\n',pos)+1;
            if(pos==0) throw fail("E_IMPORT_PATH","invalid token position");
        }
        pos+=token.column()-1;
        if(pos<0||pos>=source.length()||!source.startsWith(token.lexeme(),pos))
            throw fail("E_IMPORT_PATH","invalid token offset");
        return pos;
    }
}
