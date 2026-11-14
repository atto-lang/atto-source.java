package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.List;

/** Fail closed on parsed parameter omission until full-arity ABI lowering exists. */
public final class UnloweredParameterPresenceGuard {
    private UnloweredParameterPresenceGuard() {}

    public static void checkParameters(List<Ast.Param> parameters, String owner) {
        for (Ast.Param p : parameters) {
            if (p.omittable() || p.defaultValue() != null) {
                throw new IllegalArgumentException(
                        "OPTIONAL_FORMAL_ABI_UNSUPPORTED: " + owner + "." + p.name()
                        + " requires checked omission/default and declaration-arity lowering");
            }
        }
    }

    public static void checkClass(Ast.ClassDecl klass) {
        if (klass.constructor() != null)
            checkParameters(klass.constructor().parameters(), "constructor " + klass.name());
        for (Ast.MethodDecl method : klass.methods())
            checkParameters(method.parameters(), "method " + klass.name() + "." + method.name());
    }

    public static void checkInterface(Ast.InterfaceDecl iface) {
        for (Ast.InterfaceMember member : iface.members())
            if (member instanceof Ast.InterfaceFunctionDecl fn)
                checkParameters(fn.parameters(), "interface " + iface.name() + "." + fn.name());
    }

    public static void check(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules())
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    // Only this rigorously bounded scalar/Option<int> subset
                    // has explicit checked type/ownership/runtime materialization.
                    if (!CheckedScalarFormalPolicy.admitted(fn)) {
                        checkParameters(fn.parameters(), "function " + fn.name());
                    }
                }
                else if (decl instanceof Ast.ClassDecl klass) checkClass(klass);
                else if (decl instanceof Ast.InterfaceDecl iface) checkInterface(iface);
            }
    }
}
