package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A narrow, independently typed, NON-EXECUTABLE staged-call proof for source
 * arguments that are immutable primitive scalar literals.
 *
 * <p>Unlike a caller-provided List<TypeRef>, literal types come directly from
 * the parsed AST. A literal value cannot be retagged to manufacture a
 * WRAP_SOME or PASS_REQUIRED action. The existing binder proves source/formal
 * correspondence; the evaluation schedule proves source-order temporaries.
 *
 * <p>Important: this proves ONLY scalar argument shapes and local adaptation.
 * It does NOT prove the callee's lexical declaration, overload, visibility,
 * effects, ownership, captures or return type. It cannot authorize a runtime
 * call; TypeChecker, OwnershipChecker and interpreter staged-call admission
 * continue failing closed until complete semantic lowering is implemented.
 */
public final class OptionalCallLiteralStage {
    private OptionalCallLiteralStage() { }

    /** An opaque, factory-owned literal-only argument plan, not a runtime token. */
    public static final class LiteralPlan {
        private final OptionalCallAbiPreview.InvocationPreview invocation;
        private final OptionalCallEvaluationSchedule.Schedule evaluation;
        private final List<Ast.TypeRef> independentlyDerivedTypes;

        private LiteralPlan(OptionalCallAbiPreview.InvocationPreview invocation,
                            OptionalCallEvaluationSchedule.Schedule evaluation,
                            List<Ast.TypeRef> independentlyDerivedTypes) {
            this.invocation = Objects.requireNonNull(invocation, "invocation");
            this.evaluation = Objects.requireNonNull(evaluation, "evaluation");
            this.independentlyDerivedTypes = List.copyOf(independentlyDerivedTypes);
        }

        public OptionalCallAbiPreview.InvocationPreview invocation() { return invocation; }
        public OptionalCallEvaluationSchedule.Schedule evaluation() { return evaluation; }
        public List<Ast.TypeRef> independentlyDerivedTypes() { return independentlyDerivedTypes; }
    }

    /**
     * Non-executable candidate from a parser-owned, self-contained module.
     * A function name alone is NOT a lexical-resolution, owner, visibility,
     * effect or ownership proof. In particular, a local variable at the call
     * site might shadow this module declaration. No backend may invoke this.
     */
    public static final class LocalCandidate {
        private final Ast.FunctionDecl declaration;
        private final LiteralPlan preview;

        private LocalCandidate(Ast.FunctionDecl declaration, LiteralPlan preview) {
            this.declaration = Objects.requireNonNull(declaration, "declaration");
            this.preview = Objects.requireNonNull(preview, "preview");
        }

        public Ast.FunctionDecl declaration() { return declaration; }
        public LiteralPlan preview() { return preview; }
    }

    /**
     * Conservatively bind the signature from actual parsed source, not a
     * caller-supplied list. Import resolution and all runtime admission remain
     * blocked. More than one matching declaration is ambiguous even when
     * supplied counts or labels differ: do not guess at overload selection.
     */
    public static LocalCandidate prepareSingleModuleLiteralCandidate(
            Ast.Program program, Ast.StagedCallExpr call) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(call, "staged call");
        if (!program.imports().isEmpty() || program.modules().size() != 1) {
            throw new IllegalArgumentException(
                    "single-module candidate requires exactly one unimported module");
        }
        if (!(call.callee() instanceof Ast.NameExpr target)
                || call.typeArgumentsPresent()) {
            throw new IllegalArgumentException(
                    "candidate requires a direct name and no generic call application");
        }
        Ast.ModuleDecl module = program.modules().getFirst();
        if (!module.annotations().isEmpty() || !module.contracts().isEmpty()) {
            throw new IllegalArgumentException(
                    "module contracts and annotations need semantic resolution");
        }
        Ast.FunctionDecl candidate = null;
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn && target.name().equals(fn.name())) {
                if (candidate != null) {
                    throw new IllegalArgumentException(
                            "overloaded candidates require independent declaration selection");
                }
                if (fn.kind() != Ast.CallableKind.FNC || fn.async() || fn.suspends()
                        || fn.generator() || fn.structural() || fn.nonLexical()
                        || fn.trapped() || fn.actorKind() != Ast.ActorKind.NONE
                        || !fn.genericParameters().isEmpty() || !fn.annotations().isEmpty()) {
                    throw new IllegalArgumentException(
                            "candidate callable needs checked effects, generics or capabilities");
                }
                candidate = fn;
            } else if ((decl instanceof Ast.FieldDecl field
                        && target.name().equals(field.name()))
                    || (decl instanceof Ast.ClassDecl klass
                        && target.name().equals(klass.name()))
                    || (decl instanceof Ast.InterfaceDecl iface
                        && target.name().equals(iface.name()))
                    || (decl instanceof Ast.TypeAliasDecl alias
                        && target.name().equals(alias.name()))) {
                // Even declarations that are type-only in one target may
                // participate in named import, constructor or companion
                // resolution elsewhere. A candidate preview cannot decide
                // whether these names share a runtime namespace.
                throw new IllegalArgumentException(
                        "same-name module declaration needs real lexical resolution");
            }
        }
        if (candidate == null) {
            throw new IllegalArgumentException("unknown local function candidate");
        }
        // A literal expression proves one scalar type, not an arbitrary
        // nominal declaration, generic placeholder, borrowed view or
        // mutable parameter loan. Restrict this candidate stage to the exact
        // scalar value types that the AST literal classifier can establish.
        for (Ast.Param formal : candidate.parameters()) {
            Ast.TypeRef valueType = formal.valueType();
            if (formal.structural() || formal.mutable()
                    || valueType.inferArguments() || !valueType.arguments().isEmpty()
                    || !List.of("int", "float", "bool", "string")
                            .contains(valueType.name())) {
                throw new IllegalArgumentException(
                        "local literal candidate lacks proven scalar value/ownership type");
            }
        }
        LiteralPlan preview = prepareDirectLiteralCall(call, candidate.parameters());
        return new LocalCandidate(candidate, preview);
    }

    /**
     * Only direct-name calls with literal argument expressions qualify.
     * The direct name is still UNRESOLVED: this proof must never select a
     * declaration based on name spelling, labels or guessed parameter types.
     */
    public static LiteralPlan prepareDirectLiteralCall(
            Ast.StagedCallExpr call, List<Ast.Param> candidateFormals) {
        Objects.requireNonNull(call, "staged call");
        Objects.requireNonNull(candidateFormals, "candidate formals");
        if (!(call.callee() instanceof Ast.NameExpr)) {
            throw new IllegalArgumentException(
                    "method, member, dynamic or effectful callee needs resolved target proof");
        }
        if (call.typeArgumentsPresent()) {
            throw new IllegalArgumentException(
                    "generic call needs independent instantiation proof");
        }

        List<Ast.TypeRef> checked = new ArrayList<>();
        for (Ast.CallArgument item : call.arguments()) {
            Objects.requireNonNull(item, "call item");
            if (item.spread()) {
                throw new IllegalArgumentException("spread requires checked pack ownership");
            }
            if (item.omitted()) continue;
            if (!(item.value() instanceof Ast.LiteralExpr literal)) {
                throw new IllegalArgumentException(
                        "nonliteral argument needs independent type/effect/ownership proof");
            }
            checked.add(exactScalarLiteralType(literal));
        }
        var invocation = OptionalCallAbiPreview.prepareStagedCallExact(
                call, candidateFormals, checked);
        var evaluation = OptionalCallEvaluationSchedule.from(invocation);
        return new LiteralPlan(invocation, evaluation, checked);
    }

    /**
     * Ensure a supplied exact type is compatible with any independently
     * inspectable literal AST. Nonliterals are deliberately unproved here.
     */
    static void requireMatchingLiteralType(Ast.Expr source, Ast.TypeRef claimed) {
        Objects.requireNonNull(source, "source expression");
        Objects.requireNonNull(claimed, "claimed type");
        if (source instanceof Ast.LiteralExpr literal
                && !exactScalarLiteralType(literal).equals(claimed)) {
            throw new IllegalArgumentException(
                    "checked type assertion contradicts scalar literal AST");
        }
    }

    static Ast.TypeRef exactScalarLiteralType(Ast.LiteralExpr literal) {
        Object value = literal.value();
        if (value instanceof Long) return Ast.TypeRef.simple("int");
        if (value instanceof Double floating) {
            // LiteralExpr(Object) is publicly constructible. NaN and infinities
            // can be injected directly as Java values without a corresponding
            // verified Oreslang scalar source literal. Leave special IEEE-754
            // values to the real checker/runtime, not this narrow AST oracle.
            if (!Double.isFinite(floating)) {
                throw new IllegalArgumentException(
                        "non-finite float requires checked source literal semantics");
            }
            return Ast.TypeRef.simple("float");
        }
        if (value instanceof Boolean) return Ast.TypeRef.simple("bool");
        if (value instanceof String) return Ast.TypeRef.simple("string");
        // In particular, null is never an Option<T>.None value.
        // Other value literal classes require a dedicated canonical type
        // proof (Symbol, complex, etc.) and are not enabled here.
        throw new IllegalArgumentException(
                "literal has no independently proved scalar ABI type");
    }
}
