package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Trusted build-time bridge for a deliberately closed scalar-record subset.
 * No dynamic guest class construction or reflection. */
final class IpcCompilerCborPlanV1 {
    private static final Map<String,IpcCborDataPlanV1.Kind> TYPES=Map.of(
            "bool",IpcCborDataPlanV1.Kind.BOOL,"Bool",IpcCborDataPlanV1.Kind.BOOL,
            "i64",IpcCborDataPlanV1.Kind.I64,"f64",IpcCborDataPlanV1.Kind.F64,
            "String",IpcCborDataPlanV1.Kind.TEXT,"string",IpcCborDataPlanV1.Kind.TEXT);
    private static final Map<IpcCborDataPlanV1.Kind,String> IDS=Map.of(
            IpcCborDataPlanV1.Kind.BOOL,"Bool",IpcCborDataPlanV1.Kind.I64,"I64",
            IpcCborDataPlanV1.Kind.F64,"F64",IpcCborDataPlanV1.Kind.TEXT,"Text");
    private IpcCompilerCborPlanV1(){}
    static IpcCborDataPlanV1 compile(String source,String root,String schema,long version,
            IpcCborDataPlanV1.Limits limits){
        RuntimeBlockingGuard.requireBlockingHostThread("derive compiler CBOR plan");
        if(source==null||source.length()>65536||root==null||IDS.containsValue(root)||TYPES.containsKey(root))
            throw new IllegalArgumentException("unsupported compiler descriptor source/root");
        Ast.Program checked=OresCompiler.parseAndTypeCheck(source);
        if(!checked.imports().isEmpty()||checked.namespace()!=null||checked.modules().size()!=1)
            throw new IllegalArgumentException("closed single-module source required");
        Ast.ClassDecl record=null;
        for(Ast.Decl declaration:checked.modules().getFirst().declarations()){
            if(declaration instanceof Ast.ClassDecl c){
                if(record!=null||!c.name().equals(root))
                    throw new IllegalArgumentException("one explicit root record required");
                record=c;
            }else if(!(declaration instanceof Ast.FunctionDecl))
                throw new IllegalArgumentException("unsupported declaration in scalar profile");
        }
        if(record==null||record.actorKind()!=Ast.ActorKind.NONE||record.isAbstract()
                ||!record.genericParameters().isEmpty()||!record.parents().isEmpty()
                ||!record.interfaces().isEmpty()||record.fields().isEmpty()||record.fields().size()>64)
            throw new IllegalArgumentException("unsupported scalar record");
        var fields=new ArrayList<IpcCborDataPlanV1.Field>();
        var nodes=new TreeMap<String,IpcCborDataPlanV1.Node>();
        for(Ast.FieldDecl field:record.fields()){
            Ast.TypeRef type=field.type();
            if(type==null||type.inferArguments()||!type.arguments().isEmpty()
                    ||!field.annotations().isEmpty()||field.initializer()!=null)
                throw new IllegalArgumentException("unsupported scalar field metadata");
            var kind=TYPES.get(type.name());
            if(kind==null)throw new IllegalArgumentException("unsupported exact scalar type: "+type.name());
            String id=IDS.get(kind);
            nodes.putIfAbsent(id,new IpcCborDataPlanV1.Node(id,kind,List.of()));
            fields.add(new IpcCborDataPlanV1.Field(fields.size(),field.name(),id));
        }
        nodes.put(root,new IpcCborDataPlanV1.Node(root,IpcCborDataPlanV1.Kind.RECORD,fields));
        return new IpcCborDataPlanV1(schema,version,root,List.copyOf(nodes.values()),limits);
    }
}
