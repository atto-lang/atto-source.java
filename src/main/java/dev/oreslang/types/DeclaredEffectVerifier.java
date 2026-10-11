package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.lang.reflect.RecordComponent;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Conservative first-step effect admission for explicit `using` contracts.
 * It verifies syntactically proven operations only. Unknown calls fail closed
 * rather than manufacturing a safe effect summary. Eventually this will be
 * replaced by interprocedural typed effect inference in the shared IR.
 */
public final class DeclaredEffectVerifier {
    private DeclaredEffectVerifier() { }

    public static void verify(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl function) {
                    check(function.name(), function.annotations(), function.body());
                } else if (declaration instanceof Ast.ClassDecl klass) {
                    for (Ast.MethodDecl method : klass.methods()) {
                        check(klass.name() + "." + method.name(), method.annotations(), method.body());
                    }
                }
            }
        }
    }

    private static void check(String name, List<Ast.Annotation> annotations, Object body) {
        Ast.Annotation using = null;
        for (Ast.Annotation annotation : annotations) {
            if (annotation.name().equals("$requires$")) {
                for (Ast.TypeRef requirement : annotation.arguments()) {
                    if (requirement.name().equals("bounded")) {
                        throw new IllegalArgumentException(
                                "requires bounded needs verified target-specific WCET for " + name);
                    }
                }
            }
            if (annotation.name().equals("$using$")) {
                if (using != null) throw new IllegalArgumentException("duplicate using effect declaration");
                using = annotation;
            }
        }
        if (using == null) return;

        FunctionEffects allowed = FunctionEffects.ofNames(using.arguments().stream()
                .map(Ast.TypeRef::name).toList());
        EnumSet<FunctionEffects.Effect> inferred =
                EnumSet.noneOf(FunctionEffects.Effect.class);
        collect(body, inferred, null, name);

        // A no_alloc/no_block guarantee requires absence of those operations
        // in the *transitive* effect graph. This conservative first pass rejects
        // unknown calls, avoiding accidental admission.
        for (Ast.Annotation annotation : annotations) {
            if (!annotation.name().equals("$requires$")) continue;
            for (Ast.TypeRef requirement : annotation.arguments()) {
                if (requirement.name().equals("no_alloc")
                        && inferred.contains(FunctionEffects.Effect.ALLOC)) {
                    throw new IllegalArgumentException(name + " violates requires no_alloc");
                }
                if (requirement.name().equals("no_block")
                        && inferred.contains(FunctionEffects.Effect.BLOCK)) {
                    throw new IllegalArgumentException(name + " violates requires no_block");
                }
            }
        }
        for (FunctionEffects.Effect effect : inferred) {
            if (!allowed.contains(effect)) {
                throw new IllegalArgumentException(name + " uses effect " + effect.sourceName()
                        + " not permitted by its using signature");
            }
        }
    }

    private static void collect(Object node, Set<FunctionEffects.Effect> effects,
                                String stateAccess, String callableName) {
        if (node == null) return;
        if (node instanceof Ast.CallExpr call) {
            if (call.callee() instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr self
                    && self.name().equals("self")
                    && member.member().equals("handle_state")) {
                if (call.arguments().size() != 1
                        || !(call.arguments().getFirst() instanceof Ast.LambdaExpr callback)
                        || callback.parameters().size() != 1) {
                    throw new IllegalArgumentException("invalid handle_state effect callback");
                }
                if (!callback.critical() || callback.nonLexical()) {
                    throw new IllegalArgumentException(
                            "intrinsic critical handle_state requires a critical lexical callback");
                }
                String name = callback.parameters().getFirst().name();
                collect(callback.expressionBody(), effects, name, callableName);
                collect(callback.blockBody(), effects, name, callableName);
                return;
            }
            if (call.callee() instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr namespace
                    && namespace.name().equals("stdio")
                    && (member.member().equals("println") || member.member().equals("print"))) {
                effects.add(FunctionEffects.Effect.IO);
                collect(call.arguments(), effects, stateAccess, callableName);
                return;
            }
            // An unknown callee might suspend, allocate, perform I/O, or
            // mutate externally reachable memory. Require a trusted summary.
            throw new IllegalArgumentException(
                    "cannot verify using effects of call in " + callableName
                    + " without a trusted callee summary");
        }
        if (node instanceof Ast.AssignExpr assign) {
            if (assign.target() instanceof Ast.MemberExpr member && stateAccess != null
                    && member.receiver() instanceof Ast.NameExpr name
                    && name.name().equals(stateAccess)) {
                // Assignment to a state field is a WRITE, not an implicit
                // READ. A read-modify-write operation contributes STATE_READ
                // separately when its right-hand side observes the field.
                effects.add(FunctionEffects.Effect.STATE_WRITE);
                collect(assign.value(), effects, stateAccess, callableName);
                return;
            }
            collect(assign.target(), effects, stateAccess, callableName);
            collect(assign.value(), effects, stateAccess, callableName);
            return;
        }
        if (node instanceof Ast.MemberExpr member && stateAccess != null
                && member.receiver() instanceof Ast.NameExpr name
                && name.name().equals(stateAccess)) {
            effects.add(FunctionEffects.Effect.STATE_READ);
            return;
        }
        if (node instanceof Ast.AwaitExpr await) {
            effects.add(FunctionEffects.Effect.SUSPEND);
            collect(await.expression(), effects, stateAccess, callableName);
            return;
        }
        if (node instanceof Ast.NewExpr) {
            effects.add(FunctionEffects.Effect.ALLOC);
        }
        if (node instanceof Ast.LambdaExpr) {
            // Merely creating a callable does not invoke its body. The
            // handle_state intrinsic above handles synchronous invocation.
            return;
        }
        if (node instanceof Ast.RuntimeCallExpr operation) {
            if (operation.operation().equals("cooperate")) effects.add(FunctionEffects.Effect.SUSPEND);
            else throw new IllegalArgumentException(
                    "runtime intrinsic in " + callableName + " lacks a trusted effect summary");
        }
        if (node instanceof Ast.SelectStmt || node instanceof Ast.DynamicSelectExpr
                || node instanceof Ast.ChannelOpExpr) effects.add(FunctionEffects.Effect.SUSPEND);
        if (node instanceof Iterable<?> children) {
            for (Object child : children) collect(child, effects, stateAccess, callableName);
            return;
        }
        if (node instanceof java.lang.Record record
                && node.getClass().getName().startsWith("dev.oreslang.ast.")) {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                try {
                    collect(component.getAccessor().invoke(record),
                            effects, stateAccess, callableName);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("effect analyzer could not inspect AST", failure);
                }
            }
        }
    }
}
