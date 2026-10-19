package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.util.Objects;

/** Compiler-checked identity of a single ordinary positional module call.
 * This is NOT authority to execute a named/optional staged invocation.
 */
public final class VerifiedDirectModuleCall {
    private final Ast.Program checked;
    private final Ast.ModuleDecl module;
    private final Ast.FunctionDecl caller;
    private final Ast.FunctionDecl selected;
    private final Ast.CallExpr call;

    private VerifiedDirectModuleCall(Ast.Program checked, Ast.ModuleDecl module,
            Ast.FunctionDecl caller, Ast.FunctionDecl selected, Ast.CallExpr call) {
        this.checked = checked;
        this.module = module;
        this.caller = caller;
        this.selected = selected;
        this.call = call;
    }
    public Ast.Program checkedProgram() { return checked; }
    public Ast.ModuleDecl module() { return module; }
    public Ast.FunctionDecl caller() { return caller; }
    public Ast.FunctionDecl selectedDeclaration() { return selected; }
    public Ast.CallExpr sourceCall() { return call; }

    /** Reparse and run BOTH real front-end checkers; do not trust caller ASTs. */
    public static VerifiedDirectModuleCall analyzeAndIssue(String source,
            String moduleName, String callerName) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(moduleName);
        Objects.requireNonNull(callerName);
        Ast.Program checked = OresCompiler.analyze(Parser.parse(source));
        if (!checked.imports().isEmpty()) {
            throw new IllegalArgumentException("imported call needs linked scope proof");
        }
        Ast.ModuleDecl owner = null;
        for (Ast.ModuleDecl m : checked.modules()) {
            if (!m.name().equals(moduleName)) continue;
            if (owner != null) throw new IllegalArgumentException("duplicate module");
            owner = m;
        }
        if (owner == null || !owner.annotations().isEmpty()
                || !owner.contracts().isEmpty()) {
            throw new IllegalArgumentException("module needs unexpanded lexical origin");
        }
        Ast.FunctionDecl caller = null;
        for (Ast.Decl decl : owner.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn && fn.name().equals(callerName)) {
                if (caller != null) throw new IllegalArgumentException("overloaded caller");
                caller = fn;
            }
        }
        if (caller == null || !ordinary(caller) || caller.body().size() != 1) {
            throw new IllegalArgumentException("only one ordinary direct caller statement");
        }
        Ast.Stmt stmt = caller.body().getFirst();
        Ast.CallExpr call;
        if (stmt instanceof Ast.ReturnStmt r && r.value() instanceof Ast.CallExpr c) {
            call = c;
        } else if (stmt instanceof Ast.ExprStmt e && e.expression() instanceof Ast.CallExpr c) {
            call = c;
        } else {
            throw new IllegalArgumentException("call requires original CallExpr site");
        }
        // This restricted proof denotes one positional source expression per
        // formal. Explicit generic instantiation and pack/spread expansion
        // need different type-substitution and source-to-slot witnesses.
        requirePlainPositionalCallShape(call);
        if (!(call.callee() instanceof Ast.NameExpr name)) {
            throw new IllegalArgumentException("direct module function only");
        }
        if (name.name().equals(callerName)) {
            throw new IllegalArgumentException("recursive direct calls need verified recursion context");
        }
        // TypeChecker validates the call, but this first issuer has no
        // retained declaration-resolution map. Never assert ownership of
        // an unqualified target when another source module defines it.
        for (Ast.ModuleDecl other : checked.modules()) {
            if (other == owner) continue;
            // The restricted proof has no linker namespace map. A same-name
            // field/type in another module can also change unqualified
            // callable resolution. Do not check only foreign functions.
            for (Ast.Decl declaration : other.declarations()) {
                String exportedName = null;
                if (declaration instanceof Ast.FunctionDecl fn) {
                    exportedName = fn.name();
                } else if (declaration instanceof Ast.FieldDecl field) {
                    exportedName = field.name();
                } else if (declaration instanceof Ast.ClassDecl klass) {
                    exportedName = klass.name();
                } else if (declaration instanceof Ast.InterfaceDecl iface) {
                    exportedName = iface.name();
                } else if (declaration instanceof Ast.TypeAliasDecl alias) {
                    exportedName = alias.name();
                }
                if (name.name().equals(exportedName)) {
                    throw new IllegalArgumentException(
                            "cross-module target name requires resolved ownership proof");
                }
            }
        }
        for (Ast.Param param : caller.parameters()) {
            if (param.optional() || param.name().equals(name.name())) {
                throw new IllegalArgumentException("optional or shadowing caller formal");
            }
        }
        Ast.FunctionDecl selected = null;
        for (Ast.Decl d : owner.declarations()) {
            if (d instanceof Ast.FieldDecl f && f.name().equals(name.name())) {
                throw new IllegalArgumentException("shadowed callee");
            }
            if (d instanceof Ast.ClassDecl k && k.name().equals(name.name())
                    || d instanceof Ast.InterfaceDecl it && it.name().equals(name.name())
                    || d instanceof Ast.TypeAliasDecl a && a.name().equals(name.name())) {
                throw new IllegalArgumentException("competing type namespace");
            }
            if (d instanceof Ast.FunctionDecl f && f.name().equals(name.name())
                    && f.parameters().size() == call.arguments().size()) {
                if (selected != null) throw new IllegalArgumentException("ambiguous callee");
                selected = f;
            }
        }
        if (selected == null || !ordinary(selected)) {
            throw new IllegalArgumentException("unverified direct function target");
        }
        return new VerifiedDirectModuleCall(checked, owner, caller, selected, call);
    }

    /**
     * Source-shape guard independently testable with forged public AST nodes.
     * Full TypeChecker approval alone does not authorize this narrower
     * fixed-slot, non-generic positional-origin witness.
     */
    static void requirePlainPositionalCallShape(Ast.CallExpr call) {
        Objects.requireNonNull(call, "original ordinary call");
        if (call.typeArgumentsPresent() || !call.typeArguments().isEmpty()) {
            throw new IllegalArgumentException(
                    "explicit type application needs checked substitution proof");
        }
        for (Ast.Expr argument : call.arguments()) {
            if (argument instanceof Ast.SpreadExpr) {
                throw new IllegalArgumentException(
                        "spread arguments need checked expansion and ownership proof");
            }
        }
    }

    private static boolean ordinary(Ast.FunctionDecl f) {
        if (f.actorKind() != Ast.ActorKind.NONE || f.kind() != Ast.CallableKind.FNC
                || f.async() || f.suspends() || f.generator() || f.trapped()
                || f.structural() || f.nonLexical()
                || !f.genericParameters().isEmpty() || !f.genericSpecs().isEmpty()
                || !f.annotations().isEmpty()) return false;
        for (Ast.Param p : f.parameters()) {
            if (p.optional() || p.structural() || p.mutable()) return false;
        }
        return true;
    }
}
