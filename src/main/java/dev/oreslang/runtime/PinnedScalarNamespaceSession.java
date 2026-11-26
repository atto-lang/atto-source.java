package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.PinnedNamespaceInventory;
import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Opt-in trusted-host namespace session. Only closed, public scalar provider
 * functions returning an integer literal and a consumer consisting of
 * stdout calls to zero-argument selected functions are admitted. No arbitrary
 * source rewriting, file resolution, host interop, or implicit namespace.
 * The actual compiler checks independent source units and the normal Truffle
 * linker executes them. The consumer is lowered to explicit function imports
 * from validated providers. Fresh runtime context each call; typed-unit reuse
 * is not machine-code or runtime-init reuse. Not the default launcher.
 */
public final class PinnedScalarNamespaceSession {
    private final IncrementalCompiler compiler=new IncrementalCompiler();
    private static final String ENTRY="consumer/main.ores";
    public record Result(IncrementalCompiler.BuildResult build,int exitStatus){}
    private static IllegalArgumentException deny(String detail){return new IllegalArgumentException("E_NAMESPACE_PROFILE: "+detail);}
    private static boolean identifier(String name){return name!=null&&name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")&&!Set.of("main","stdio","init",Parser.ROOT_MODULE).contains(name);}
    private static Ast.ModuleDecl root(Ast.Program p){
        if(!p.exports().isEmpty()||p.modules().size()!=1)throw deny("single root module");
        var m=p.modules().getFirst();if(!Parser.ROOT_MODULE.equals(m.name())||!m.annotations().isEmpty()||!m.contracts().isEmpty())throw deny("root metadata");return m;
    }
    private static Ast.FunctionDecl ordinary(Ast.Decl d,String type){
        if(!(d instanceof Ast.FunctionDecl f)||f.visibility()!=Ast.Visibility.PUBLIC||f.kind()!=Ast.CallableKind.FNC||
            f.actorKind()!=Ast.ActorKind.NONE||f.async()||f.suspends()||f.generator()||f.structural()||f.nonLexical()||f.trapped()||
            !f.genericParameters().isEmpty()||!f.annotations().isEmpty()||!f.effects().isEmpty()||!Ast.TypeRef.simple(type).equals(f.returnType()))
            throw deny("ordinary public "+type+" function required");
        return f;
    }
    private static Ast.CallExpr direct(Ast.Expr e){
        if(!(e instanceof Ast.CallExpr c)||c.origin()!=Ast.CallOrigin.DIRECT||c.typeArgumentsPresent()||!c.typeArguments().isEmpty())throw deny("direct call required");return c;
    }
    private static boolean member(Ast.Expr e,String receiver,String method){
        return e instanceof Ast.MemberExpr m&&m.receiver() instanceof Ast.NameExpr n&&n.name().equals(receiver)&&m.member().equals(method);
    }
    public synchronized Result run(String consumer,String consumerManifest,String providerManifest,Map<String,String> snapshot,OutputStream out,OutputStream err){
        Objects.requireNonNull(out);Objects.requireNonNull(err);
        if(consumer==null||consumer.length()>65536||!StandardCharsets.UTF_8.newEncoder().canEncode(consumer)||consumer.getBytes(StandardCharsets.UTF_8).length>65536)throw deny("consumer budget");
        var program=Parser.parse(consumer);
        if(program.imports().size()!=1)throw deny("one namespace import required");
        var selected=PinnedNamespaceInventory.admit(program.imports().getFirst(),consumerManifest,providerManifest,snapshot);
        if(!identifier(selected.localAlias()))throw deny("reserved alias");
        var m=root(program);
        if(m.declarations().size()!=1)throw deny("single entry");
        var main=ordinary(m.declarations().getFirst(),"void");
        if(!main.name().equals("main")||!main.parameters().isEmpty()||main.body().isEmpty()||main.body().size()>129)throw deny("entry signature");
        var sources=new TreeMap<String,String>();var exports=new TreeMap<String,String>();
        for(var provider:selected.providers()){
            var p=provider.program();if(!p.imports().isEmpty())throw deny("provider imports not supported");
            String id="provider/"+provider.path();
            for(var d:root(p).declarations()){
                var f=ordinary(d,"int");
                if(!identifier(f.name()))throw deny("reserved provider symbol");
                if(!f.parameters().isEmpty())throw new IllegalArgumentException("E_NAMESPACE_ARITY: zero-argument provider required: "+f.name());
                if(f.body().size()!=1||!(f.body().getFirst() instanceof Ast.ReturnStmt ret)||!(ret.value() instanceof Ast.LiteralExpr))throw deny("closed scalar literal provider required");
                if(exports.putIfAbsent(f.name(),id)!=null)throw deny("ambiguous provider");
            }
            sources.put(id,provider.source());
        }
        var used=new TreeMap<String,String>();var body=new StringBuilder();
        for(int i=0;i<main.body().size();i++){
            var stmt=main.body().get(i);
            if(i==main.body().size()-1){
                if(!(stmt instanceof Ast.ReturnStmt ret)||ret.value()!=null)throw deny("void return required");
                body.append("return;\n");continue;
            }
            if(!(stmt instanceof Ast.ExprStmt expr))throw deny("only stdout call statements");
            var print=direct(expr.expression());
            if(!member(print.callee(),"stdio","println")||print.arguments().size()!=1)throw deny("only stdio.println");
            var call=direct(print.arguments().getFirst());
            if(!(call.callee() instanceof Ast.MemberExpr access)||!member(call.callee(),selected.localAlias(),access.member())||!call.arguments().isEmpty())throw deny("selected zero-argument call required");
            String name=access.member(),target=exports.get(name);
            if(target==null)throw new IllegalArgumentException("E_NAMESPACE_SYMBOL: not a public selected provider: "+name);
            used.put(name,target);body.append("stdio.println(").append(name).append("());\n");
        }
        if(used.isEmpty())throw deny("no selected calls");
        var lowered=new StringBuilder();
        // Consumer namespace is irrelevant to this narrowly closed main.
        // The original consumer is parsed/validated above, never evaluated as
        // arbitrary source; only AST-validated identifier names are emitted.
        var resolutions=new TreeMap<String,String>();
        used.forEach((name,id)->{
            String path="../"+id;lowered.append("import fnc ").append(name).append(" from \"").append(path).append("\";\n");
            resolutions.put(path,id);
        });
        lowered.append("pub fnc main(): void {\n").append(body).append("}\n");
        sources.put(ENTRY,lowered.toString());
        var build=compiler.compile(sources,Map.of(ENTRY,resolutions));
        var policy=new IsolatePolicy(Set.of(IsolatePolicy.Capability.STDOUT),64L*1024*1024,64,Duration.ofSeconds(10));
        try(Context context=policy.restrictedContextBuilder(ExecutionProfile.serverJit()).out(out).err(err).build()){
            var units=new TreeMap<String,Value>();
            for(var id:new TreeSet<>(build.units().keySet())){
                var unit=build.units().get(id);
                var source=Source.newBuilder(OresLanguage.ID,unit.sourceText(),id).mimeType(OresLanguage.MIME_TYPE).cached(false).buildLiteral();
                try(var scope=OresLanguage.openPrecheckedProgram(id,unit.sourceText(),unit.program())){
                    units.put(id,context.parse(source));
                }
            }
            for(var resolution:resolutions.entrySet())units.get(ENTRY).execute(OresEvalRootNode.REGISTER_IMPORT_COMMAND,resolution.getKey(),resolution.getValue());
            for(var unit:units.values())unit.execute(OresEvalRootNode.LINK_ONLY_COMMAND);
            for(var group:build.initializationGroups())for(String id:group)units.get(id).execute(OresEvalRootNode.INIT_ONLY_COMMAND);
            var result=units.get(ENTRY).execute(OresEvalRootNode.MAIN_ONLY_COMMAND);
            if(!result.isNull()&&(!result.fitsInInt()||result.asInt()!=0))throw deny("nonzero entry result");
            return new Result(build,0);
        }
    }
}
