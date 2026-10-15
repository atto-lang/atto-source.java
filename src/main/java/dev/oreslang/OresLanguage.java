package dev.oreslang;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.nodes.OresInteropRootNode;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.AsyncRuntime;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.OresScheduler;
import org.graalvm.polyglot.SandboxPolicy;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

@TruffleLanguage.Registration(
        id = OresLanguage.ID,
        name = "Oreslang",
        version = "0.1.0",
        defaultMimeType = OresLanguage.MIME_TYPE,
        characterMimeTypes = OresLanguage.MIME_TYPE,
        contextPolicy = TruffleLanguage.ContextPolicy.EXCLUSIVE,
        sandbox = SandboxPolicy.UNTRUSTED,
        website = "https://github.com/ores-truffle-oreslang/oreslang-source.java")
public final class OresLanguage extends TruffleLanguage<OresContext> {
    public static final String ID = "ores";
    public static final String MIME_TYPE = "application/x-oreslang";

    @Override
    protected OresContext createContext(Env env) {
        return new OresContext(this, env);
    }

    /**
     * Host-owned actor dispatcher and async workers may enter the context.
     * Guest source still has no raw thread-creation authority; that remains controlled by
     * IsolatePolicy and the Polyglot Context builder.
     *
     * Strict/adversarial contexts serialize actor guest turns in OresContext.
     * Non-adversarial contexts may execute independent actor turns concurrently.
     */
    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return singleThreaded
                || ActorRuntime.isActorCarrierThread()
                || AsyncRuntime.isAsyncCarrierThread()
                || OresScheduler.isSchedulerCarrierThread();
    }

    @Override
    protected void initializeMultiThreading(OresContext context) {
        // All mutable language state used by actor turns is context-owned,
        // actor-owned, immutable, or explicitly synchronized.
    }

    @Override
    protected void disposeContext(OresContext context) {
        context.close();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        var source = request.getSource();
        String text = source.getCharacters().toString();
        // Exact opt-in per source unit. Default arithmetic retains legacy semantics.
        boolean experimentalCheckedI64 = text.startsWith("// oreslang:checked-i64-v1\n");
        Ast.Program program = OresCompiler.parseAndTypeCheck(text);
        if (experimentalCheckedI64) verifyExperimentalCheckedI64Subset(program);
        String codeUnitId = source.getPath();
        if (codeUnitId == null || codeUnitId.isBlank()) {
            codeUnitId = source.getName();
        } else {
            try {
                codeUnitId = Path.of(codeUnitId).toAbsolutePath().normalize().toString().replace('\\', '/');
            } catch (InvalidPathException invalidPath) {
                throw new IllegalArgumentException("invalid Oreslang source path identity", invalidPath);
            }
        }
        if (codeUnitId == null || codeUnitId.isBlank()) codeUnitId = "<anonymous>";
        RootCallTarget evaluator = new OresEvalRootNode(this, program, codeUnitId, experimentalCheckedI64).getCallTarget();
        return new OresInteropRootNode(this, evaluator).getCallTarget();
    }

    /**
     * The checked arithmetic experiment has no certified actor, async,
     * superclass, import or source-level exceptional effect ABI yet.
     * Reject those source units before creating any executable guest root.
     * Default/unmarked source has no new admission restrictions.
     */
    private static void verifyExperimentalCheckedI64Subset(Ast.Program program) {
        if (program.namespace() != null || !program.imports().isEmpty()) {
            throw new IllegalArgumentException("checked-i64-v1 rejects namespace/import boundaries");
        }
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.FunctionDecl fn)
                        || fn.kind() != Ast.CallableKind.FNC
                        || fn.actorKind() != Ast.ActorKind.NONE
                        || fn.async() || fn.suspends() || fn.generator()
                        || fn.trapped() || fn.structural() || fn.nonLexical()
                        || !fn.genericParameters().isEmpty()
                        || !fn.annotations().isEmpty()
                        || !isCheckedI64Scalar(fn.returnType())
                        || fn.parameters().stream().anyMatch(p ->
                                p.mutable() || p.structural() || !isCheckedI64Scalar(p.type()))
                        || fn.body().size() != 1
                        || !(fn.body().getFirst() instanceof Ast.ReturnStmt ret)
                        || ret.value() == null) {
                    throw new IllegalArgumentException(
                            "checked-i64-v1 requires synchronous scalar functions; "
                            + "actor, class, import and effect lowering are unsupported");
                }
            }
        }
    }

    private static boolean isCheckedI64Scalar(Ast.TypeRef type) {
        return type != null && !type.inferArguments() && type.arguments().isEmpty()
                && (type.name().equals("int") || type.name().equals("bool"));
    }

}
