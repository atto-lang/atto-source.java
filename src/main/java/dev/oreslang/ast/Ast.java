package dev.oreslang.ast;

import java.util.List;

public final class Ast {
    private Ast() { }

    public record Program(List<ModuleDecl> modules) {
        public Program {
            modules = List.copyOf(modules);
        }
    }

    public record ModuleDecl(String name, List<Decl> declarations) {
        public ModuleDecl {
            declarations = List.copyOf(declarations);
        }
    }

    public sealed interface Decl permits FunctionDecl, ClassDecl, FieldDecl, TypeAliasDecl { }

    public enum Visibility { PRIVATE, PUBLIC }

    public record Annotation(String name, List<TypeRef> arguments) {
        public Annotation {
            arguments = List.copyOf(arguments);
        }
    }

    public record TypeRef(String name, List<TypeRef> arguments, boolean inferArguments) {
        public TypeRef {
            arguments = List.copyOf(arguments);
        }

        public static TypeRef simple(String name) {
            return new TypeRef(name, List.of(), false);
        }
    }

    public record Param(TypeRef type, String name) { }

    public record FunctionDecl(
            String name,
            Visibility visibility,
            boolean async,
            List<String> genericParameters,
            List<Param> parameters,
            TypeRef returnType,
            List<Annotation> annotations,
            List<Stmt> body) implements Decl {
        public FunctionDecl {
            genericParameters = List.copyOf(genericParameters);
            parameters = List.copyOf(parameters);
            annotations = List.copyOf(annotations);
            body = List.copyOf(body);
        }
    }

    public record ClassDecl(
            String name,
            boolean isAbstract,
            List<String> genericParameters,
            List<FieldDecl> fields,
            List<MethodDecl> methods) implements Decl {
        public ClassDecl {
            genericParameters = List.copyOf(genericParameters);
            fields = List.copyOf(fields);
            methods = List.copyOf(methods);
        }
    }

    public record FieldDecl(
            String name,
            Visibility visibility,
            BindingKind bindingKind,
            TypeRef type,
            Expr initializer) implements Decl { }

    public record MethodDecl(
            String name,
            Visibility visibility,
            boolean isStatic,
            boolean isAbstract,
            boolean async,
            TypeRef explicitReceiverType,
            List<String> genericParameters,
            List<Param> parameters,
            TypeRef returnType,
            List<Annotation> annotations,
            List<Stmt> body) {
        public MethodDecl {
            genericParameters = List.copyOf(genericParameters);
            parameters = List.copyOf(parameters);
            annotations = List.copyOf(annotations);
            body = List.copyOf(body);
        }
    }

    public record TypeAliasDecl(String name, List<String> genericParameters, TypeRef target) implements Decl {
        public TypeAliasDecl {
            genericParameters = List.copyOf(genericParameters);
        }
    }

    public enum BindingKind { CONST, VAL, LET }

    public sealed interface Stmt permits BindingStmt, DestructureStmt, ReturnStmt, ExprStmt, DeferStmt, IfStmt, TryStmt { }

    public record BindingStmt(BindingKind kind, TypeRef declaredType, String name, Expr initializer) implements Stmt { }

    public record DestructureBinding(BindingKind kind, String name) { }

    public record DestructureStmt(List<DestructureBinding> bindings, Expr initializer) implements Stmt {
        public DestructureStmt {
            bindings = List.copyOf(bindings);
        }
    }

    public record ReturnStmt(Expr value) implements Stmt { }

    public record ExprStmt(Expr expression) implements Stmt { }

    public record DeferStmt(Expr expression) implements Stmt { }

    public record IfBranch(Expr condition, List<Stmt> body) {
        public IfBranch {
            body = List.copyOf(body);
        }
    }

    public record IfStmt(List<IfBranch> branches, List<Stmt> elseBody) implements Stmt {
        public IfStmt {
            branches = List.copyOf(branches);
            elseBody = List.copyOf(elseBody);
        }
    }

    public record TryStmt(List<Stmt> body, String errorName, List<Stmt> catchBody, List<Stmt> finallyBody) implements Stmt {
        public TryStmt {
            body = List.copyOf(body);
            catchBody = List.copyOf(catchBody);
            finallyBody = List.copyOf(finallyBody);
        }
    }

    public sealed interface Expr permits LiteralExpr, NameExpr, BinaryExpr, UnaryExpr, CallExpr, MemberExpr, NewExpr, AwaitExpr, ListExpr, LambdaExpr { }

    public record LiteralExpr(Object value) implements Expr { }

    public record Imaginary(double coefficient) { }

    public record NameExpr(String name) implements Expr { }

    public record BinaryExpr(String operator, Expr left, Expr right) implements Expr { }

    public record UnaryExpr(String operator, Expr operand) implements Expr { }

    public record CallExpr(Expr callee, List<Expr> arguments) implements Expr {
        public CallExpr {
            arguments = List.copyOf(arguments);
        }
    }

    public record MemberExpr(Expr receiver, String member) implements Expr { }

    public record NewExpr(TypeRef type, List<Expr> arguments) implements Expr {
        public NewExpr {
            arguments = List.copyOf(arguments);
        }
    }

    public record AwaitExpr(Expr expression) implements Expr { }

    public record ListExpr(List<Expr> elements) implements Expr {
        public ListExpr {
            elements = List.copyOf(elements);
        }
    }

    public record LambdaExpr(List<Param> parameters, Expr expressionBody, List<Stmt> blockBody) implements Expr {
        public LambdaExpr {
            parameters = List.copyOf(parameters);
            blockBody = blockBody == null ? null : List.copyOf(blockBody);
        }
    }
}
