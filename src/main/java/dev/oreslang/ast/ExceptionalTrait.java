package dev.oreslang.ast;

import java.util.List;

/** Compiler-owned nominal markers shared by type checking and guest admission. */
public enum ExceptionalTrait {
    THROWABLE("Throwable"),
    RAISABLE("Raisable"),
    PANICABLE("Panicable");

    private final Ast.InterfaceDecl declaration;

    ExceptionalTrait(String name) {
        declaration = new Ast.InterfaceDecl(name, Ast.Visibility.PUBLIC,
                List.of(), List.of(), List.of());
    }

    public Ast.InterfaceDecl declaration() {
        return declaration;
    }

    public static Ast.InterfaceDecl find(String name) {
        return switch (name) {
            case "Throwable" -> THROWABLE.declaration;
            case "Raisable" -> RAISABLE.declaration;
            case "Panicable" -> PANICABLE.declaration;
            default -> null;
        };
    }
}
