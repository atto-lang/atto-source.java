package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shape-only resolved invocation IR. This is not an executable capability:
 * semantic types, ownership, effects, imports and runtime ABI are unproved.
 */
public final class BoundInvocation {
    public enum Readiness { SHAPE_ONLY }
    private final Ast.StagedCallExpr source;
    private final Ast.ModuleDecl owner;
    private final Ast.FunctionDecl selected;
    private final int declarationOrdinal;
    private final OptionalCallAbiPreview.InvocationPreview preview;
    private final List<Ast.TypeRef> assertedSourceTypes;

    private BoundInvocation(Ast.StagedCallExpr source, Ast.ModuleDecl owner,
                            Ast.FunctionDecl selected, int ordinal,
                            OptionalCallAbiPreview.InvocationPreview preview,
                            List<Ast.TypeRef> assertedSourceTypes) {
        this.source = Objects.requireNonNull(source);
        this.owner = Objects.requireNonNull(owner);
        this.selected = Objects.requireNonNull(selected);
        this.declarationOrdinal = ordinal;
        this.preview = Objects.requireNonNull(preview);
        this.assertedSourceTypes = List.copyOf(assertedSourceTypes);
        if (ordinal < 0 || owner.declarations().get(ordinal) != selected) {
            throw new IllegalArgumentException("selected declaration not owned by module");
        }
    }

    public Ast.StagedCallExpr source() { return source; }
    public Ast.ModuleDecl owner() { return owner; }
    public Ast.FunctionDecl selectedDeclaration() { return selected; }
    public int declarationOrdinal() { return declarationOrdinal; }
    public OptionalCallAbiPreview.InvocationPreview preview() { return preview; }
    public List<Ast.TypeRef> assertedSourceTypes() { return assertedSourceTypes; }
    public Readiness readiness() { return Readiness.SHAPE_ONLY; }

    public void requireExecutable() {
        throw new IllegalStateException(
                "trusted type/ownership/effect/Option ABI lowering is not implemented");
    }

    /**
     * Resolve only a direct or explicitly module-qualified function in the
     * real parsed owner. This does NOT prove the caller can access the module.
     */
    public static BoundInvocation bindModuleFunction(
            Ast.ModuleDecl module, Ast.StagedCallExpr call,
            List<Ast.TypeRef> assertedSourceTypes) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(call, "call");
        String name;
        if (call.callee() instanceof Ast.NameExpr direct) {
            name = direct.name();
        } else if (call.callee() instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr qualifier
                && qualifier.name().equals(module.name())) {
            name = member.member();
        } else {
            throw new IllegalArgumentException("unproved indirect or foreign callee");
        }
        List<Ast.FunctionDecl> candidates = new ArrayList<>();
        List<Integer> ordinals = new ArrayList<>();
        List<List<Ast.Param>> signatures = new ArrayList<>();
        for (int i = 0; i < module.declarations().size(); i++) {
            if (module.declarations().get(i) instanceof Ast.FunctionDecl f
                    && f.name().equals(name)) {
                if (!f.genericParameters().isEmpty() || !f.genericSpecs().isEmpty()
                        || f.actorKind() != Ast.ActorKind.NONE) {
                    throw new IllegalArgumentException("generic or actor target not proved");
                }
                candidates.add(f);
                ordinals.add(i);
                signatures.add(f.parameters());
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("no declaration in selected module");
        }
        var selection = OptionalCallShapeBinder.selectParsed(signatures, call.arguments());
        Ast.FunctionDecl declaration = candidates.get(selection.signatureIndex());
        OptionalCallShapeBinder.verifyPlanAgainstSource(
                OptionalCallShapeBinder.declaredParameterShapes(declaration.parameters()),
                call.arguments(), selection.plan());
        var preview = OptionalCallAbiPreview.prepareStagedCallExact(
                call, declaration.parameters(), assertedSourceTypes);
        return new BoundInvocation(call, module, declaration,
                ordinals.get(selection.signatureIndex()), preview, assertedSourceTypes);
    }
}
