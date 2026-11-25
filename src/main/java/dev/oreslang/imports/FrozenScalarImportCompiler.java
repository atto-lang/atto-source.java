package dev.oreslang.imports;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.OresLanguage;
import dev.oreslang.nodes.OresEvalRootNode;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import java.util.*;

/**
 * Frozen, in-memory compiler entry point for one deliberately bounded guest
 * shape: imports, namespace aliases, and a public scalar main returning a
 * no-argument imported function call. Every provider byte was verified before
 * lowering. The consumer is parsed by an explicit closed token grammar, not
 * rewritten by textual substitution. Other shapes must fail closed.
 */
public final class FrozenScalarImportCompiler {
    private static final String ENTRY = "entry.atto";
    private static final String PROVIDER = "pinned/provider.atto";
    private static final String IMPORT = "./pinned/provider.atto";
    private FrozenScalarImportCompiler() {}

    private static IllegalArgumentException fail(String code,String detail) {
        return new IllegalArgumentException(code+": "+detail);
    }

    public static final class CheckedGuest {
        private final IncrementalCompiler.BuildResult build;
        private CheckedGuest(IncrementalCompiler.BuildResult build) { this.build=build; }

        public int run(ExecutionProfile profile) {
            // Use the existing restricted runtime, prechecked AST and static
            // linked-unit protocol. No runtime alias values or host interop.
            try(Context ctx=IsolatePolicy.strictFaas().restrictedContextBuilder(profile).build()) {
                var units=new TreeMap<String,Value>();
                for(var unit:build.units().values()) {
                    Source source=Source.newBuilder(OresLanguage.ID,unit.sourceText(),unit.unitId())
                        .mimeType(OresLanguage.MIME_TYPE).buildLiteral();
                    try(var ignored=OresLanguage.openPrecheckedProgram(unit.unitId(),unit.sourceText(),unit.program())) {
                        units.put(unit.unitId(),ctx.parse(source));
                    }
                }
                units.get(ENTRY).execute(OresEvalRootNode.REGISTER_IMPORT_COMMAND,IMPORT,PROVIDER);
                for(var unit:units.values()) unit.execute(OresEvalRootNode.LINK_ONLY_COMMAND);
                for(var group:build.initializationGroups())
                    for(String id:group) units.get(id).execute(OresEvalRootNode.INIT_ONLY_COMMAND);
                Value value=units.get(ENTRY).execute(OresEvalRootNode.MAIN_ONLY_COMMAND);
                if(!value.fitsInInt()) throw fail("E_IMPORT_KIND","guest result is not an i32 exit status");
                return value.asInt();
            }
        }
    }

    public static CheckedGuest compile(String consumer,List<FrozenImportSnapshot> packages) {
        if(consumer==null||consumer.length()>65536||packages==null||packages.isEmpty()||packages.size()>16)
            throw fail("E_SOURCE_LIMIT","invalid frozen scalar compilation budget");
        var available=new TreeMap<String,FrozenImportSnapshot>();
        for(var pkg:packages)
            if(available.putIfAbsent(pkg.name(),pkg)!=null)
                throw fail("E_AMBIGUOUS","multiple snapshots for package name");
        var reader=new Reader(new Lexer(consumer).scan());
        var graph=new StaticImportGraph();
        var aliases=new ArrayList<StaticImportGraph.Alias>();
        record Import(String source,String selector,String binding,boolean renamed) {}
        var imports=new ArrayList<Import>();
        while(reader.take("import")) {
            if(imports.size()>=64) throw fail("E_SOURCE_LIMIT","import budget exceeded");
            if(reader.take("pkg")) {
                String source=reader.string();
                String alias=reader.take("as")?reader.path():null;
                reader.expect(";");
                imports.add(new Import(source,null,alias,alias!=null));
                continue;
            }
            reader.expect("namespace");
            String selector=reader.path(), binding=selector;
            boolean renamed=reader.take("as");
            if(renamed) binding=reader.path();
            reader.expect("from"); reader.expect("pkg");
            String source=reader.string();
            reader.expect(";");
            imports.add(new Import(source,selector,binding,renamed));
        }
        while(reader.take("define")) {
            reader.expect("alias");
            if(aliases.size()>=256) throw fail("E_SOURCE_LIMIT","alias budget exceeded");
            String left=reader.path(), name, reference;
            if(reader.take("=")) {name=left; reference=reader.path();}
            else {reader.expect("as"); reference=left; name=reader.path();}
            reader.expect(";");
            aliases.add(new StaticImportGraph.Alias(name,reference));
        }
        StaticImportGraph.precheckAliases(aliases);
        reader.expect("pub"); reader.expect("fnc"); reader.expect("main");
        reader.expect("("); reader.expect(")");
        if(!reader.take("->")) reader.expect(":");
        reader.expect("int"); reader.expect("{"); reader.expect("return");
        String reference=reader.path();
        reader.expect("("); reader.expect(")"); reader.expect(";"); reader.expect("}");
        reader.expectEnd();
        if(reference.indexOf('.')<0) throw fail("E_UNSUPPORTED","expected a qualified static call");
        for(var imported:imports) {
            if(imported.selector()==null) {
                var pkg=available.get(imported.source());
                if(pkg==null) throw fail("E_UNBOUND","package root not in authenticated snapshot set");
                graph.bindPackage(pkg.functionView(),imported.binding());
                continue;
            }
            String[] parts=imported.source().split("/",-1);
            if(parts.length<3) throw fail("E_IMPORT_PATH","invalid package selector");
            var pkg=available.get(String.join("/",Arrays.copyOf(parts,3)));
            if(pkg==null) throw fail("E_UNBOUND","package not in authenticated snapshot set");
            pkg.bindNamespace(graph,imported.source(),imported.selector(),imported.binding(),imported.renamed());
        }
        graph.bindAliases(aliases);
        StaticImportGraph.Target target=graph.resolve(reference);
        if(target.kind()!=StaticImportGraph.Kind.FNC) throw fail("E_IMPORT_KIND","expected a function");
        FrozenImportSnapshot pkg=available.get(target.view().origin().identity());
        if(pkg==null||pkg.functionView()!=target.view())
            throw fail("E_IMPORT_PATH","selected view not owned by authenticated snapshot");
        int dot=target.path().lastIndexOf('.');
        if(dot<=0) throw fail("E_IMPORT_PATH","missing provider namespace");
        String member=target.path().substring(dot+1);
        String provider=pkg.providerSource(target.path().substring(0,dot));
        // Only validated identifiers enter the generated static import. The
        // provider is the unchanged original verified source, not a transformed
        // copy. The normal compiler rechecks member signatures and call types.
        String lowered="import fnc "+member+" as selected_call from \""+IMPORT+"\";\n"
            +"pub fnc main(): int { return selected_call(); }";
        var resolutions=Map.of(ENTRY,Map.of(IMPORT,PROVIDER));
        var build=new IncrementalCompiler().compileStrictAtto(Map.of(ENTRY,lowered,PROVIDER,provider),resolutions);
        return new CheckedGuest(build);
    }

    private static final class Reader {
        private final List<Token> tokens;
        private int i;
        Reader(List<Token> tokens) {this.tokens=tokens;}
        Token peek(){return tokens.get(i);}
        boolean take(String value) {
            Token.Type expected=switch(value) {
                case "import" -> Token.Type.IMPORT;
                case "namespace" -> Token.Type.NAMESPACE;
                case "as" -> Token.Type.AS;
                case "from" -> Token.Type.FROM;
                case "define" -> Token.Type.DEFINE;
                case "pub" -> Token.Type.PUB;
                case "fnc" -> Token.Type.FNC;
                case "return" -> Token.Type.RETURN;
                case "(" -> Token.Type.LPAREN;
                case ")" -> Token.Type.RPAREN;
                case "{" -> Token.Type.LBRACE;
                case "}" -> Token.Type.RBRACE;
                case "." -> Token.Type.DOT;
                case ";" -> Token.Type.SEMICOLON;
                case ":" -> Token.Type.COLON;
                case "->" -> Token.Type.ARROW;
                case "=" -> Token.Type.EQUAL;
                default -> Token.Type.IDENT;
            };
            if(peek().type()!=expected||!peek().lexeme().equals(value)) return false;
            i++; return true;
        }
        void expect(String value) {if(!take(value)) throw fail("E_UNSUPPORTED","expected "+value);}
        void expectEnd(){if(peek().type()!=Token.Type.EOF) throw fail("E_UNSUPPORTED","unexpected trailing guest source");}
        String component(){
            Token t=peek();
            if(t.type()!=Token.Type.IDENT||!ContextualImportPath.valid(t.lexeme()))
                throw fail("E_IMPORT_PATH","invalid qualified component");
            i++; return t.lexeme();
        }
        String path(){
            StringBuilder s=new StringBuilder(component());
            int count=1;
            while(take(".")) {
                if(++count>8) throw fail("E_IMPORT_PATH","qualified path too deep");
                s.append('.').append(component());
            }
            return s.toString();
        }
        String string(){
            if(peek().type()!=Token.Type.STRING) throw fail("E_IMPORT_PATH","expected static package string");
            return tokens.get(i++).lexeme();
        }
    }
}
