package dev.oreslang.imports;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
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
 * bounded signed-int arithmetic call with a scalar local prelude. Every provider byte was verified before
 * lowering. The consumer is parsed by an explicit closed token grammar, not
 * rewritten by textual substitution. Other shapes must fail closed.
 */
public final class FrozenScalarImportCompiler {
    private static final String ENTRY = "entry.atto";
    private FrozenScalarImportCompiler() {}

    private static IllegalArgumentException fail(String code,String detail) {
        return new IllegalArgumentException(code+": "+detail);
    }

    public static final class CheckedGuest {
        private final IncrementalCompiler.BuildResult build;
        private final Map<String,Map<String,String>> resolutions;
        private CheckedGuest(IncrementalCompiler.BuildResult build,Map<String,Map<String,String>> resolutions) {
            this.build=build; this.resolutions=Map.copyOf(resolutions);
        }

        public int run(ExecutionProfile profile) {
            return execute(IsolatePolicy.strictFaas().restrictedContextBuilder(profile));
        }

        // Package-private test seam, never selected by the production CLI.
        // Only trusted, bounded fixtures may use the in-process interpreter.
        int runTrustedFixture(ExecutionProfile profile) {
            var policy=new IsolatePolicy(Set.of(),128L*1024*1024,1024,
                java.time.Duration.ofSeconds(30),false);
            return execute(policy.restrictedContextBuilder(profile));
        }

        private int execute(Context.Builder builder) {
            // Prechecked AST and static linked-unit protocol. No runtime
            // alias values or host interop.
            try(Context ctx=builder.build()) {
                var units=new TreeMap<String,Value>();
                for(var unit:build.units().values()) {
                    Source source=Source.newBuilder(OresLanguage.ID,unit.sourceText(),unit.unitId())
                        .mimeType(OresLanguage.MIME_TYPE).buildLiteral();
                    try(var ignored=OresLanguage.openPrecheckedProgram(unit.unitId(),unit.sourceText(),unit.program())) {
                        units.put(unit.unitId(),ctx.parse(source));
                    }
                }
                for(var importer:new TreeMap<>(resolutions).entrySet())
                    for(var edge:new TreeMap<>(importer.getValue()).entrySet())
                        units.get(importer.getKey()).execute(OresEvalRootNode.REGISTER_IMPORT_COMMAND,edge.getKey(),edge.getValue());
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
        var closure=FrozenDependencyGraph.verify(packages);
        var linked=FrozenProviderLinker.link(closure,available);
        for(var imported:imports) {
            if(imported.selector()==null) {
                var pkg=available.get(imported.source());
                if(pkg==null) throw fail("E_UNBOUND","package root not in authenticated snapshot set");
                graph.bindPackage(linked.views().get(pkg.name()),imported.binding());
                continue;
            }
            String[] parts=imported.source().split("/",-1);
            if(parts.length<3) throw fail("E_IMPORT_PATH","invalid package selector");
            var pkg=available.get(String.join("/",Arrays.copyOf(parts,3)));
            if(pkg==null) throw fail("E_UNBOUND","package not in authenticated snapshot set");
            if(!imported.renamed()&&!imported.selector().equals(imported.binding()))
                throw fail("E_IMPORT_PATH","rename requires explicit alias");
            String selected=pkg.selectNamespace(imported.source(),imported.selector());
            var view=linked.views().get(pkg.name());
            graph.bindGroup(view.origin(),view,List.of(new StaticImportGraph.Selection(selected,
                imported.binding(),StaticImportGraph.Kind.NAMESPACE,imported.renamed())));
        }
        graph.bindAliases(aliases);

        record CallKey(String provider,String member) {}
        var sources=new LinkedHashMap<String,String>(linked.sources());
        var resolutions=new LinkedHashMap<String,String>();
        var callNames=new LinkedHashMap<CallKey,String>();
        var declarations=new StringBuilder();
        int[] calls={0};
        reader.resolveCall=(reference,arguments)->{
            if(++calls[0]>32) throw fail("E_SOURCE_LIMIT","imported call budget exceeded");
            StaticImportGraph.Target target=graph.resolve(reference);
            if(target.kind()!=StaticImportGraph.Kind.FNC) throw fail("E_IMPORT_KIND","expected a function");
            FrozenImportSnapshot pkg=available.get(target.view().origin().identity());
            if(pkg==null||linked.views().get(pkg.name())!=target.view())
                throw fail("E_IMPORT_PATH","selected view not owned by authenticated snapshot");
            int dot=target.path().lastIndexOf('.');
            if(dot<=0) throw fail("E_IMPORT_PATH","missing provider namespace");
            String member=target.path().substring(dot+1), ns=target.path().substring(0,dot);
            String provider=pkg.providerSource(ns);
            // Match the closed signed-int ABI before lowering. Do not use
            // implicit coercions or argument types to choose a package.
            var functions=Parser.parseStrictAtto(provider).modules().stream()
                .filter(m->Parser.ROOT_MODULE.equals(m.name()))
                .flatMap(m->m.declarations().stream())
                .filter(d->d instanceof Ast.FunctionDecl)
                .map(d->(Ast.FunctionDecl)d)
                .filter(f->f.name().equals(member))
                .toList();
            if(functions.size()!=1) throw fail("E_IMPORT_KIND","selected function lacks unique declaration");
            var function=functions.getFirst();
            if(function.visibility()!=Ast.Visibility.PUBLIC||function.kind()!=Ast.CallableKind.FNC||
                function.actorKind()!=Ast.ActorKind.NONE||function.async()||function.suspends()||
                function.generator()||function.structural()||function.nonLexical()||function.trapped()||
                !function.genericParameters().isEmpty()||!function.annotations().isEmpty()||
                function.parameters().size()!=arguments.size()||
                function.parameters().stream().anyMatch(p->!p.type().equals(Ast.TypeRef.simple("int"))||p.structural()||p.mutable())||
                !Ast.TypeRef.simple("int").equals(function.returnType()))
                throw fail("E_IMPORT_KIND","selected function must accept exact signed-int arguments and return int");

            String unit=linked.units().get(new FrozenProviderLinker.Key(pkg.name(),ns));
            if(unit==null) throw fail("E_IMPORT_PATH","provider not linked");
            resolutions.put("./"+unit,unit);
            CallKey callKey=new CallKey(unit,member);
            String selected=callNames.get(callKey);
            if(selected==null) {
                selected="frozen_call_"+callNames.size();
                callNames.put(callKey,selected);
                declarations.append("import fnc ").append(member).append(" as ").append(selected)
                    .append(" from \"./").append(unit).append("\";\n");
            }
            return selected+"("+String.join(", ",arguments)+")";
        };
        reader.expect("pub"); reader.expect("fnc"); reader.expect("main");
        reader.expect("("); reader.expect(")");
        if(!reader.take("->")) reader.expect(":");
        reader.expect("int"); reader.expect("{");
        var prelude=new StringBuilder();
        while(reader.take("val")) {
            if(reader.locals.size()>=16) throw fail("E_SOURCE_LIMIT","local binding budget exceeded");
            reader.expect("int");
            String local=reader.component();
            if(reader.locals.containsKey(local)) throw fail("E_DUPLICATE","duplicate local binding");
            reader.expect("=");
            String initial=reader.expression(0);
            reader.expect(";");
            String generated="frozen_local_"+reader.locals.size();
            reader.locals.put(local,generated);
            prelude.append("val int ").append(generated).append(" = ").append(initial).append("; ");
        }
        reader.expect("return");
        String result=reader.expression(0);
        reader.expect(";"); reader.expect("}");
        reader.expectEnd();
        if(calls[0]==0) throw fail("E_UNSUPPORTED","frozen guest requires imported call");
        sources.put(ENTRY,declarations+"pub fnc main(): int { "+prelude+"return "+result+"; }");
        var allResolutions=new LinkedHashMap<String,Map<String,String>>(linked.resolutions());
        allResolutions.put(ENTRY,Map.copyOf(resolutions));
        var build=new IncrementalCompiler().compileStrictAtto(sources,allResolutions);
        return new CheckedGuest(build,allResolutions);
    }

    private static final class Reader {
        private final List<Token> tokens;
        private int i, expressionNodes;
        private java.util.function.BiFunction<String,List<String>,String> resolveCall;
        private final Map<String,String> locals=new LinkedHashMap<>();
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
                case "," -> Token.Type.COMMA;
                case "-" -> Token.Type.MINUS;
                case "+" -> Token.Type.PLUS;
                case "*" -> Token.Type.STAR;
                case "val" -> Token.Type.VAL;
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
        private void chargeExpression(int depth){
            if(depth>16||++expressionNodes>128)
                throw fail("E_SOURCE_LIMIT","scalar expression budget exceeded");
        }
        String expression(int depth){
            String value=product(depth);
            while(peek().type()==Token.Type.PLUS||peek().type()==Token.Type.MINUS) {
                String op=tokens.get(i++).lexeme();
                chargeExpression(depth);
                value="("+value+" "+op+" "+product(depth)+")";
            }
            return value;
        }
        String product(int depth){
            String value=atom(depth);
            while(take("*")) {
                chargeExpression(depth);
                value="("+value+" * "+atom(depth)+")";
            }
            return value;
        }
        String atom(int depth){
            chargeExpression(depth);
            if(take("(")) {
                String value=expression(depth+1);
                expect(")");
                return "("+value+")";
            }
            if(peek().type()==Token.Type.IDENT) {
                String name=component();
                if(peek().type()==Token.Type.DOT) {
                    if(locals.containsKey(name)) throw fail("E_IMPORT_KIND","scalar local shadows qualified call root");
                    StringBuilder reference=new StringBuilder(name);
                    int count=1;
                    while(take(".")) {
                        if(++count>8) throw fail("E_IMPORT_PATH","path too deep");
                        reference.append('.').append(component());
                    }
                    expect("(");
                    var args=new ArrayList<String>();
                    if(!take(")")) {
                        do {
                            if(args.size()>=16) throw fail("E_SOURCE_LIMIT","call argument budget exceeded");
                            args.add(expression(depth+1));
                        } while(take(","));
                        expect(")");
                    }
                    if(resolveCall==null) throw fail("E_UNSUPPORTED","import call unavailable");
                    return resolveCall.apply(reference.toString(),List.copyOf(args));
                }
                String generated=locals.get(name);
                if(generated==null) throw fail("E_UNBOUND","unbound scalar local");
                return generated;
            }
            // Preserve negative-literal bounds including Long.MIN_VALUE.
            // General unary operators and function-valued arguments remain denied.
            return Long.toString(integer());
        }
        long integer(){
            boolean negative=take("-");
            Token t=peek();
            if(t.type()!=Token.Type.INT||!t.lexeme().matches("[0-9](?:_?[0-9]){0,18}"))
                throw fail("E_IMPORT_KIND","expected bounded decimal int argument");
            i++;
            try {return Long.parseLong((negative?"-":"")+t.lexeme().replace("_",""));}
            catch(NumberFormatException e){throw fail("E_IMPORT_KIND","int argument outside signed i64 range");}
        }
        String string(){
            if(peek().type()!=Token.Type.STRING) throw fail("E_IMPORT_PATH","expected static package string");
            return tokens.get(i++).lexeme();
        }
    }
}
