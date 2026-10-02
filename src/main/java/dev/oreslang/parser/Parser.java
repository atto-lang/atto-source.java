package dev.oreslang.parser;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.List;

import static dev.oreslang.parser.Token.Type.*;

public final class Parser {
    public static final String ROOT_MODULE = "__root__";

    private final List<Token> tokens;
    private int current;

    public Parser(List<Token> tokens) {
        this.tokens = List.copyOf(tokens);
    }

    public static Ast.Program parse(String source) {
        return new Parser(new Lexer(source).scan()).parseProgram();
    }

    public Ast.Program parseProgram() {
        List<Ast.ImportDecl> imports = new ArrayList<>();
        while (match(IMPORT)) imports.add(parseImport());

        List<Ast.ModuleDecl> modules = new ArrayList<>();
        List<Ast.Decl> rootDeclarations = new ArrayList<>();

        while (!check(EOF)) {
            List<Ast.Annotation> annotations = parseAnnotations();
            Modifiers modifiers = parseModifiers();

            if (match(DEFINE)) {
                boolean afterDefineAbstract = match(ABSTRACT);
                if (match(MODULE)) {
                    if (modifiers.visibility != Ast.Visibility.PRIVATE || modifiers.async || modifiers.isStatic || modifiers.isAbstract) {
                        throw error(previous(), "modules do not accept function/class modifiers");
                    }
                    modules.add(parseModule(annotations));
                    continue;
                }
                if (match(CLASS)) {
                    rootDeclarations.add(parseClass(modifiers.isAbstract || afterDefineAbstract));
                    continue;
                }
                if (match(INTERFACE)) {
                    rootDeclarations.add(parseInterface(modifiers.visibility));
                    continue;
                }
                throw error(previous(), "expected module, class, or interface after 'define'");
            }

            Ast.Decl declaration = parseDeclarationAfterModifiers(annotations, modifiers);
            if (declaration == null) throw error(peek(), "expected module or top-level declaration");
            rootDeclarations.add(declaration);
        }

        if (!rootDeclarations.isEmpty()) {
            modules.add(new Ast.ModuleDecl(ROOT_MODULE, List.of(), rootDeclarations));
        }
        if (modules.isEmpty()) throw error(peek(), "a source file must define at least one module or top-level declaration");
        return new Ast.Program(imports, modules);
    }

    private Ast.ImportDecl parseImport() {
        Ast.ImportKind kind;
        List<String> names = new ArrayList<>();
        boolean wildcard = false;
        String namespace = null;

        if (match(STAR)) {
            kind = Ast.ImportKind.ALL;
            wildcard = true;
            consume(AS, "'import *' requires 'as <namespace>'");
            namespace = consume(IDENT, "expected import namespace").lexeme();
        } else {
            if (match(MODULE)) kind = Ast.ImportKind.MODULE;
            else if (match(CLASS)) kind = Ast.ImportKind.CLASS;
            else if (match(FNC)) kind = Ast.ImportKind.FUNCTION;
            else throw error(peek(), "expected module, class, fnc, or * after import");

            if (match(STAR)) {
                wildcard = true;
                consume(AS, "wildcard import requires 'as <namespace>'");
                namespace = consume(IDENT, "expected import namespace").lexeme();
            } else if (match(LBRACE)) {
                if (check(RBRACE)) throw error(peek(), "import selection cannot be empty");
                do names.add(consume(IDENT, "expected imported name").lexeme()); while (match(COMMA));
                consume(RBRACE, "expected '}' after imported names");
            } else {
                names.add(consume(IDENT, "expected imported name").lexeme());
            }
        }

        consume(FROM, "expected 'from' in import");
        String path = consume(STRING, "expected quoted import path").lexeme();
        if (path.isBlank()) throw error(previous(), "import path cannot be empty");
        consume(SEMICOLON, "expected ';' after import");
        return new Ast.ImportDecl(kind, names, wildcard, namespace, path);
    }

    private Ast.ModuleDecl parseModule(List<Ast.Annotation> annotations) {
        String name = parseQualifiedName();
        List<Ast.Decl> declarations = new ArrayList<>();
        while (!check(END) && !check(EOF)) declarations.add(parseModuleMember());
        consume(END, "expected 'end' to close module " + name);
        return new Ast.ModuleDecl(name, annotations, declarations);
    }

    private Ast.Decl parseModuleMember() {
        List<Ast.Annotation> annotations = parseAnnotations();
        Modifiers modifiers = parseModifiers();

        if (match(DEFINE)) {
            boolean afterDefineAbstract = match(ABSTRACT);
            if (match(CLASS)) return parseClass(modifiers.isAbstract || afterDefineAbstract);
            if (match(INTERFACE)) return parseInterface(modifiers.visibility);
            throw error(previous(), "expected class or interface after 'define'");
        }

        Ast.Decl declaration = parseDeclarationAfterModifiers(annotations, modifiers);
        if (declaration != null) return declaration;
        throw error(peek(), "expected function, routine, class, interface, type, or binding declaration");
    }

    private Ast.Decl parseDeclarationAfterModifiers(List<Ast.Annotation> annotations, Modifiers modifiers) {
        if (match(FNC)) return parseFunction(annotations, modifiers, Ast.CallableKind.FNC);
        if (match(ROUTINE)) return parseFunction(annotations, modifiers, Ast.CallableKind.ROUTINE);
        if (match(INTERFACE)) return parseInterface(modifiers.visibility);
        if (match(TYPE)) return parseTypeAlias();
        if (isBindingKind(peek().type())) return parseModuleBinding(modifiers.visibility);
        return null;
    }

    private Ast.FunctionDecl parseFunction(List<Ast.Annotation> annotations, Modifiers modifiers, Ast.CallableKind kind) {
        String name = consume(IDENT, "expected callable name").lexeme();
        List<String> generics = parseGenericParameters();
        consume(LPAREN, "expected '('");
        List<Ast.Param> params = parseParametersUntil(RPAREN);
        consume(RPAREN, "expected ')' after parameters");
        Ast.TypeRef returnType = parseReturnType(annotations);
        List<Ast.Stmt> body = parseBlock();
        return new Ast.FunctionDecl(name, kind, modifiers.visibility, modifiers.async, generics, params,
                returnType, annotations, body);
    }

    private Ast.ClassDecl parseClass(boolean isAbstract) {
        String name = consume(IDENT, "expected class name").lexeme();
        List<String> generics = parseGenericParameters();
        List<Ast.TypeRef> parents = match(EXTENDS) ? parseTypeRefList() : List.of();
        List<Ast.TypeRef> interfaces = match(IMPLEMENTS, IMPL) ? parseTypeRefList() : List.of();
        List<Ast.FieldDecl> fields = new ArrayList<>();
        List<Ast.MethodDecl> methods = new ArrayList<>();

        while (!check(END) && !check(EOF)) {
            List<Ast.Annotation> annotations = parseAnnotations();
            Modifiers mods = parseModifiers();
            if (isBindingKind(peek().type())) {
                fields.add(parseField(mods.visibility));
                continue;
            }
            methods.add(parseMethod(annotations, mods));
        }
        consume(END, "expected 'end' to close class " + name);
        return new Ast.ClassDecl(name, isAbstract, generics, parents, interfaces, fields, methods);
    }

    private Ast.InterfaceDecl parseInterface(Ast.Visibility visibility) {
        String name = consume(IDENT, "expected interface name").lexeme();
        List<String> generics = parseGenericParameters();
        List<Ast.TypeRef> parents = match(EXTENDS) ? parseTypeRefList() : List.of();
        boolean braceStyle = match(LBRACE);
        Token.Type terminator = braceStyle ? RBRACE : END;

        List<Ast.InterfaceMember> members = new ArrayList<>();
        while (!check(terminator) && !check(EOF)) {
            parseAnnotations();
            parseModifiers();

            if (match(FNC)) {
                String memberName = consume(IDENT, "expected interface function name").lexeme();
                List<String> memberGenerics = parseGenericParameters();
                consume(LPAREN, "expected '(' after interface function name");
                List<Ast.Param> params = parseParametersUntil(RPAREN);
                consume(RPAREN, "expected ')' after interface parameters");
                Ast.TypeRef returns = match(FAT_ARROW) ? parseTypeRef() : Ast.TypeRef.simple("void");
                consumeMemberTerminator(terminator, "interface function signature should end with ';'");
                members.add(new Ast.InterfaceFunctionDecl(memberName, memberGenerics, params, returns));
                continue;
            }

            if (check(IDENT) && checkNext(COLON)) {
                String fieldName = advance().lexeme();
                consume(COLON, "expected ':' after interface field name");
                Ast.TypeRef type = parseTypeRef();
                consumeMemberTerminator(terminator, "interface field signature should end with ';'");
                members.add(new Ast.InterfaceFieldDecl(fieldName, type));
                continue;
            }

            if (isBindingKind(peek().type())) advance();
            Ast.TypeRef type = parseTypeRef();
            String fieldName = consume(IDENT, "expected interface field name").lexeme();
            consumeMemberTerminator(terminator, "interface field signature should end with ';'");
            members.add(new Ast.InterfaceFieldDecl(fieldName, type));
        }

        consume(terminator, braceStyle ? "expected '}' to close interface " + name : "expected 'end' to close interface " + name);
        return new Ast.InterfaceDecl(name, visibility, generics, parents, members);
    }

    private List<Ast.TypeRef> parseTypeRefList() {
        List<Ast.TypeRef> refs = new ArrayList<>();
        do refs.add(parseTypeRef()); while (match(COMMA));
        return refs;
    }

    private Ast.FieldDecl parseField(Ast.Visibility visibility) {
        Ast.BindingKind kind = parseBindingKind();
        Ast.TypeRef type = parseTypeRef();
        String name = consume(IDENT, "expected field name").lexeme();
        Ast.Expr initializer = match(EQUAL) ? parseExpression() : null;
        consumeStatementTerminator("field declaration should end with ';'");
        return new Ast.FieldDecl(name, visibility, kind, type, initializer);
    }

    private Ast.FieldDecl parseModuleBinding(Ast.Visibility visibility) {
        Ast.BindingKind kind = parseBindingKind();
        Ast.TypeRef type = null;
        String name;
        if (check(IDENT) && checkNext(EQUAL)) name = advance().lexeme();
        else {
            type = parseTypeRef();
            name = consume(IDENT, "expected binding name").lexeme();
        }
        consume(EQUAL, "module bindings require an initializer");
        Ast.Expr initializer = parseExpression();
        consumeStatementTerminator("module binding should end with ';'");
        return new Ast.FieldDecl(name, visibility, kind, type, initializer);
    }

    private Ast.MethodDecl parseMethod(List<Ast.Annotation> annotations, Modifiers mods) {
        String name = parseMethodName();
        List<String> generics = parseGenericParameters();
        consume(LPAREN, "expected '(' after method name");

        Ast.TypeRef receiverType = null;
        List<Ast.Param> params;
        if (match(SELF)) {
            receiverType = parseTypeRef();
            consume(RPAREN, "expected ')' after explicit self receiver");
            consume(LPAREN, "explicit receiver form is method(self Type)(params)");
            params = parseParametersUntil(RPAREN);
            consume(RPAREN, "expected ')' after method parameters");
        } else {
            params = parseParametersUntil(RPAREN);
            consume(RPAREN, "expected ')' after method parameters");
        }

        Ast.TypeRef returnType = parseReturnType(annotations);
        List<Ast.Stmt> body;
        if (mods.isAbstract) {
            consumeStatementTerminator("abstract method should end with ';'");
            body = List.of();
        } else body = parseBlock();
        return new Ast.MethodDecl(name, mods.visibility, mods.isStatic, mods.isAbstract, mods.async,
                receiverType, generics, params, returnType, annotations, body);
    }

    private String parseMethodName() {
        if (match(LBRACKET)) {
            String namespace = consume(IDENT, "expected symbol namespace").lexeme();
            consume(DOT, "expected '.' in symbol method");
            String symbol = consume(IDENT, "expected symbol name").lexeme();
            consume(RBRACKET, "expected ']' after symbol method");
            if (!namespace.equals("Symbol")) throw error(previous(), "symbol methods must use Symbol.<name>");
            return namespace + "." + symbol;
        }
        return consume(IDENT, "expected method name (methods omit 'fnc')").lexeme();
    }

    private Ast.TypeAliasDecl parseTypeAlias() {
        String name = consume(IDENT, "expected type alias name").lexeme();
        List<String> generics = parseGenericParameters();
        consume(EQUAL, "expected '=' in type alias");
        Ast.TypeRef target = parseTypeRef();
        consumeStatementTerminator("type alias should end with ';'");
        return new Ast.TypeAliasDecl(name, generics, target);
    }

    private List<Ast.Annotation> parseAnnotations() {
        List<Ast.Annotation> result = new ArrayList<>();
        while (match(AT)) {
            String name = consume(IDENT, "expected annotation name").lexeme();
            List<Ast.TypeRef> args = new ArrayList<>();
            Token.Type close = null;
            if (match(LT)) close = GT;
            else if (match(LPAREN)) close = RPAREN;
            if (close != null) {
                if (!check(close)) do args.add(parseTypeRef()); while (match(COMMA));
                consume(close, "expected annotation terminator");
            }
            result.add(new Ast.Annotation(name, args));
        }
        return result;
    }

    private Modifiers parseModifiers() {
        Ast.Visibility visibility = Ast.Visibility.PRIVATE;
        boolean async = false;
        boolean isStatic = false;
        boolean isAbstract = false;
        boolean progress;
        do {
            progress = true;
            if (match(PUB)) visibility = Ast.Visibility.PUBLIC;
            else if (match(PRIVATE)) visibility = Ast.Visibility.PRIVATE;
            else if (match(ASYNC)) async = true;
            else if (match(STATIC)) isStatic = true;
            else if (match(ABSTRACT)) isAbstract = true;
            else progress = false;
        } while (progress);
        return new Modifiers(visibility, async, isStatic, isAbstract);
    }

    private Ast.TypeRef parseReturnType(List<Ast.Annotation> annotations) {
        Ast.TypeRef annotated = null;
        for (Ast.Annotation annotation : annotations) {
            if (annotation.name().equals("Ret")) {
                if (annotation.arguments().size() != 1) throw error(previous(), "@Ret requires exactly one type");
                annotated = annotation.arguments().getFirst();
            }
        }
        Ast.TypeRef arrow = match(FAT_ARROW) ? parseTypeRef() : null;
        if (annotated != null && arrow != null && !sameType(annotated, arrow)) {
            throw error(previous(), "@Ret type and => return type disagree");
        }
        return arrow != null ? arrow : annotated != null ? annotated : Ast.TypeRef.simple("void");
    }

    private boolean sameType(Ast.TypeRef a, Ast.TypeRef b) {
        return a.name().equals(b.name()) && a.arguments().equals(b.arguments()) && a.inferArguments() == b.inferArguments();
    }

    private List<String> parseGenericParameters() {
        if (!match(LT)) return List.of();
        List<String> names = new ArrayList<>();
        do names.add(consume(IDENT, "expected generic parameter name").lexeme()); while (match(COMMA));
        consume(GT, "expected '>' after generic parameters");
        return names;
    }

    private List<Ast.Param> parseParametersUntil(Token.Type terminator) {
        if (check(terminator)) return List.of();
        List<Ast.Param> params = new ArrayList<>();
        do {
            boolean structural = false;
            if (match(AT)) {
                String annotation = consume(IDENT, "expected parameter annotation").lexeme();
                if (!annotation.equals("Structural")) throw error(previous(), "only @Structural is currently supported on parameters");
                structural = true;
            }
            Ast.TypeRef type = parseTypeRef();
            String name = consume(IDENT, "expected parameter name").lexeme();
            params.add(new Ast.Param(type, name, structural));
        } while (match(COMMA));
        return params;
    }

    private Ast.TypeRef parseTypeRef() {
        if (match(STRING)) return Ast.TypeRef.stringLiteral(previous().lexeme());

        String name;
        if (match(VOID)) name = "void";
        else if (match(SELF)) name = "self";
        else if (match(NULL)) name = "null";
        else name = parseQualifiedName();

        List<Ast.TypeRef> args = new ArrayList<>();
        boolean infer = false;
        if (match(LT)) {
            if (match(GT)) infer = true;
            else {
                do args.add(parseTypeRef()); while (match(COMMA));
                consume(GT, "expected '>' after type arguments");
            }
        }
        return new Ast.TypeRef(name, args, infer);
    }

    private String parseQualifiedName() {
        StringBuilder name = new StringBuilder(consume(IDENT, "expected name").lexeme());
        while (match(DOT)) name.append('.').append(consume(IDENT, "expected name after '.'").lexeme());
        return name.toString();
    }

    private List<Ast.Stmt> parseBlock() {
        consume(LBRACE, "expected '{'");
        List<Ast.Stmt> body = new ArrayList<>();
        while (!check(RBRACE) && !check(EOF)) body.add(parseStatement());
        consume(RBRACE, "expected '}'");
        return body;
    }

    private Ast.Stmt parseStatement() {
        if (isBindingKind(peek().type())) return parseBindingStatement();
        if (check(LBRACKET) && looksLikeDestructure()) return parseDestructure();
        if (match(RETURN)) {
            Ast.Expr value = check(SEMICOLON) || isSafeStatementBoundary() ? null : parseExpression();
            consumeStatementTerminator("return statement should end with ';'");
            return new Ast.ReturnStmt(value);
        }
        if (match(DEFER)) {
            Ast.Expr expression = parseExpression();
            consumeStatementTerminator("defer statement should end with ';'");
            return new Ast.DeferStmt(expression);
        }
        if (match(IF)) return parseIf();
        if (match(TRY)) return parseTry();
        if (match(FOR)) return parseFor();

        Ast.Expr expression = parseExpression();
        consumeStatementTerminator("expression statement should end with ';'");
        return new Ast.ExprStmt(expression);
    }

    private Ast.Stmt parseFor() {
        consume(LPAREN, "expected '(' after for");

        if (isBindingKind(peek().type())) {
            Ast.BindingKind kind = parseBindingKind();
            if (check(IDENT) && checkNext(OF)) {
                String name = advance().lexeme();
                consume(OF, "expected 'of' in for-of loop");
                Ast.Expr iterable = parseExpression();
                consume(RPAREN, "expected ')' after for-of header");
                return new Ast.ForOfStmt(kind, name, iterable, parseBlock());
            }

            Ast.TypeRef type = null;
            String name;
            if (check(IDENT) && checkNext(EQUAL)) name = advance().lexeme();
            else {
                type = parseTypeRef();
                name = consume(IDENT, "expected loop initializer binding name").lexeme();
            }
            consume(EQUAL, "for initializer binding requires '='");
            Ast.Expr initializer = parseExpression();
            Ast.BindingStmt init = new Ast.BindingStmt(kind, type, name, initializer);
            consume(SEMICOLON, "expected ';' after for initializer");
            Ast.Expr condition = check(SEMICOLON) ? null : parseExpression();
            consume(SEMICOLON, "expected ';' after for condition");
            Ast.Expr update = check(RPAREN) ? null : parseExpression();
            consume(RPAREN, "expected ')' after for header");
            return new Ast.ForStmt(init, condition, update, parseBlock());
        }

        if (check(IDENT) && checkNext(OF)) {
            String name = advance().lexeme();
            consume(OF, "expected 'of' in for-of loop");
            Ast.Expr iterable = parseExpression();
            consume(RPAREN, "expected ')' after for-of header");
            return new Ast.ForOfStmt(Ast.BindingKind.VAL, name, iterable, parseBlock());
        }

        Ast.Stmt initializer = null;
        if (!check(SEMICOLON)) initializer = new Ast.ExprStmt(parseExpression());
        consume(SEMICOLON, "expected ';' after for initializer");
        Ast.Expr condition = check(SEMICOLON) ? null : parseExpression();
        consume(SEMICOLON, "expected ';' after for condition");
        Ast.Expr update = check(RPAREN) ? null : parseExpression();
        consume(RPAREN, "expected ')' after for header");
        return new Ast.ForStmt(initializer, condition, update, parseBlock());
    }

    private Ast.BindingStmt parseBindingStatement() {
        Ast.BindingKind kind = parseBindingKind();
        Ast.TypeRef type = null;
        String name;
        if (check(IDENT) && checkNext(EQUAL)) name = advance().lexeme();
        else {
            type = parseTypeRef();
            name = consume(IDENT, "expected binding name").lexeme();
        }
        consume(EQUAL, "binding requires initializer");
        Ast.Expr initializer = parseExpression();
        consumeStatementTerminator("binding should end with ';'");
        return new Ast.BindingStmt(kind, type, name, initializer);
    }

    private Ast.DestructureStmt parseDestructure() {
        consume(LBRACKET, "expected '['");
        List<Ast.DestructureBinding> bindings = new ArrayList<>();
        do {
            Ast.BindingKind kind = parseBindingKind();
            String name = consume(IDENT, "expected binding name in destructure").lexeme();
            bindings.add(new Ast.DestructureBinding(kind, name));
        } while (match(COMMA));
        consume(RBRACKET, "expected ']'");
        consume(EQUAL, "expected '=' after destructure pattern");
        Ast.Expr initializer = parseExpression();
        consumeStatementTerminator("destructure should end with ';'");
        return new Ast.DestructureStmt(bindings, initializer);
    }

    private boolean looksLikeDestructure() {
        return current + 1 < tokens.size() && isBindingKind(tokens.get(current + 1).type());
    }

    private Ast.IfStmt parseIf() {
        List<Ast.IfBranch> branches = new ArrayList<>();
        Ast.Expr condition = parseCondition();
        match(SEMICOLON);
        consume(DO, "expected 'do'");
        List<Ast.Stmt> body = parseUntil(ELSEIF, ELSE, FI);
        branches.add(new Ast.IfBranch(condition, body));

        while (match(ELSEIF)) {
            condition = parseCondition();
            match(SEMICOLON);
            consume(DO, "expected 'do'");
            body = parseUntil(ELSEIF, ELSE, FI);
            branches.add(new Ast.IfBranch(condition, body));
        }

        List<Ast.Stmt> elseBody = List.of();
        if (match(ELSE)) elseBody = parseUntil(FI);
        consume(FI, "expected 'fi' to close if");
        return new Ast.IfStmt(branches, elseBody);
    }

    private Ast.Expr parseCondition() {
        Ast.Expr expression = parseEquality();
        while (true) {
            if (match(COMMA)) expression = new Ast.BinaryExpr(",", expression, parseEquality());
            else if (match(PIPE)) expression = new Ast.BinaryExpr("|", expression, parseEquality());
            else break;
        }
        return expression;
    }

    private Ast.TryStmt parseTry() {
        List<Ast.Stmt> body = parseBlock();
        consume(CATCH, "expected catch after try block");
        consume(LPAREN, "expected '(' after catch");
        String error = consume(IDENT, "expected catch binding").lexeme();
        consume(RPAREN, "expected ')' after catch binding");
        List<Ast.Stmt> catchBody = parseBlock();
        List<Ast.Stmt> finallyBody = match(FINALLY) ? parseBlock() : List.of();
        return new Ast.TryStmt(body, error, catchBody, finallyBody);
    }

    private List<Ast.Stmt> parseUntil(Token.Type... terminators) {
        List<Ast.Stmt> body = new ArrayList<>();
        outer: while (!check(EOF)) {
            for (Token.Type terminator : terminators) if (check(terminator)) break outer;
            body.add(parseStatement());
        }
        return body;
    }

    public Ast.Expr parseExpression() { return parseAssignment(); }

    private Ast.Expr parseAssignment() {
        Ast.Expr expr = parseConditional();
        if (match(EQUAL)) {
            if (!(expr instanceof Ast.NameExpr)) throw error(previous(), "assignment target must be a local name in v0");
            return new Ast.AssignExpr(expr, parseAssignment());
        }
        return expr;
    }

    private Ast.Expr parseConditional() {
        Ast.Expr condition = parseOr();
        if (!match(QUESTION)) return condition;
        Ast.Expr whenTrue = parseAssignment();
        consume(COLON, "expected ':' in ternary expression");
        Ast.Expr whenFalse = parseConditional();
        return new Ast.ConditionalExpr(condition, whenTrue, whenFalse);
    }

    private Ast.Expr parseOr() {
        Ast.Expr expr = parseEquality();
        while (match(PIPE)) expr = new Ast.BinaryExpr("|", expr, parseEquality());
        return expr;
    }

    private Ast.Expr parseEquality() {
        Ast.Expr expr = parseComparison();
        while (match(EQUAL_EQUAL, BANG_EQUAL)) {
            String op = previous().lexeme();
            expr = new Ast.BinaryExpr(op, expr, parseComparison());
        }
        return expr;
    }

    private Ast.Expr parseComparison() {
        Ast.Expr expr = parseAdditive();
        while (match(LT, LTE, GT, GTE)) {
            String op = previous().lexeme();
            expr = new Ast.BinaryExpr(op, expr, parseAdditive());
        }
        return expr;
    }

    private Ast.Expr parseAdditive() {
        Ast.Expr expr = parseMultiplicative();
        while (match(PLUS, MINUS)) {
            String op = previous().lexeme();
            expr = new Ast.BinaryExpr(op, expr, parseMultiplicative());
        }
        return expr;
    }

    private Ast.Expr parseMultiplicative() {
        Ast.Expr expr = parseUnary();
        while (match(STAR, SLASH, PERCENT)) {
            String op = previous().lexeme();
            expr = new Ast.BinaryExpr(op, expr, parseUnary());
        }
        return expr;
    }

    private Ast.Expr parseUnary() {
        if (match(BANG, MINUS, PLUS)) return new Ast.UnaryExpr(previous().lexeme(), parseUnary());
        if (match(AWAIT)) return new Ast.AwaitExpr(parseUnary());
        return parsePostfix();
    }

    private Ast.Expr parsePostfix() {
        Ast.Expr expr = parsePrimary();
        while (true) {
            if (match(LPAREN)) {
                List<Ast.Expr> args = parseArgumentsUntil(RPAREN);
                consume(RPAREN, "expected ')' after arguments");
                expr = new Ast.CallExpr(expr, args);
            } else if (match(DOT)) {
                String member = consume(IDENT, "expected member name after '.'").lexeme();
                expr = new Ast.MemberExpr(expr, member);
            } else if (match(LBRACKET)) {
                Ast.Expr index = parseExpression();
                consume(RBRACKET, "expected ']' after index");
                expr = new Ast.IndexExpr(expr, index);
            } else break;
        }
        return expr;
    }

    private Ast.Expr parsePrimary() {
        if (match(INT)) return new Ast.LiteralExpr(Long.parseLong(previous().lexeme().replace("_", "")));
        if (match(FLOAT)) return new Ast.LiteralExpr(Double.parseDouble(previous().lexeme().replace("_", "")));
        if (match(IMAG)) {
            String raw = previous().lexeme().substring(0, previous().lexeme().length() - 1).replace("_", "");
            return new Ast.LiteralExpr(new Ast.Imaginary(Double.parseDouble(raw)));
        }
        if (match(STRING)) return new Ast.LiteralExpr(previous().lexeme());
        if (match(TRUE)) return new Ast.LiteralExpr(Boolean.TRUE);
        if (match(FALSE)) return new Ast.LiteralExpr(Boolean.FALSE);
        if (match(NULL)) throw error(previous(), "standalone null values are forbidden; use Option<T>");
        if (match(SELF)) return new Ast.NameExpr("self");
        if (match(IDENT)) return new Ast.NameExpr(previous().lexeme());
        if (match(NEW)) {
            Ast.TypeRef type = parseTypeRef();
            consume(LPAREN, "expected '(' after new type");
            List<Ast.Expr> args = parseArgumentsUntil(RPAREN);
            consume(RPAREN, "expected ')' after constructor arguments");
            return new Ast.NewExpr(type, args);
        }
        if (match(OBJ)) return parseObjectLiteral();
        if (match(ARR)) {
            consume(LBRACKET, "expected '[' after arr");
            List<Ast.Expr> items = parseArgumentsUntil(RBRACKET);
            consume(RBRACKET, "expected ']' after arr literal");
            return new Ast.ListExpr(items);
        }
        if (check(LPAREN) && looksLikeLambda()) return parseLambda();
        if (match(LPAREN)) {
            Ast.Expr first = parseExpression();
            if (match(COMMA)) {
                List<Ast.Expr> items = new ArrayList<>();
                items.add(first);
                do items.add(parseExpression()); while (match(COMMA));
                consume(RPAREN, "expected ')' after tuple");
                return new Ast.TupleExpr(items);
            }
            consume(RPAREN, "expected ')' after expression");
            return first;
        }
        if (match(LBRACKET)) {
            List<Ast.Expr> items = parseArgumentsUntil(RBRACKET);
            consume(RBRACKET, "expected ']'");
            return new Ast.ListExpr(items);
        }
        throw error(peek(), "expected expression");
    }

    private Ast.ObjectExpr parseObjectLiteral() {
        consume(LBRACE, "expected '{' after obj");
        List<Ast.ObjectField> fields = new ArrayList<>();
        if (!check(RBRACE)) {
            do {
                String name;
                if (match(IDENT, STRING)) name = previous().lexeme();
                else throw error(peek(), "expected object field name");
                consume(COLON, "expected ':' after object field name");
                fields.add(new Ast.ObjectField(name, parseExpression()));
            } while (match(COMMA));
        }
        consume(RBRACE, "expected '}' after obj literal");
        return new Ast.ObjectExpr(fields);
    }

    private Ast.LambdaExpr parseLambda() {
        consume(LPAREN, "expected '('");
        List<Ast.Param> params = parseParametersUntil(RPAREN);
        consume(RPAREN, "expected ')' after lambda parameters");
        consume(ARROW, "expected '->' after lambda parameters");
        if (check(LBRACE)) return new Ast.LambdaExpr(params, null, parseBlock());
        return new Ast.LambdaExpr(params, parseExpression(), null);
    }

    private boolean looksLikeLambda() {
        int depth = 0;
        for (int i = current; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LPAREN) depth++;
            else if (type == RPAREN) {
                depth--;
                if (depth == 0) return i + 1 < tokens.size() && tokens.get(i + 1).type() == ARROW;
            }
        }
        return false;
    }

    private List<Ast.Expr> parseArgumentsUntil(Token.Type terminator) {
        if (check(terminator)) return List.of();
        List<Ast.Expr> args = new ArrayList<>();
        do args.add(parseExpression()); while (match(COMMA));
        return args;
    }

    private void consumeMemberTerminator(Token.Type structuralTerminator, String message) {
        if (match(SEMICOLON) || check(structuralTerminator)) return;
        throw error(peek(), message);
    }

    private void consumeStatementTerminator(String message) {
        if (match(SEMICOLON) || isSafeStatementBoundary()) return;
        throw error(peek(), message);
    }

    private boolean isSafeStatementBoundary() {
        return check(RBRACE) || check(FI) || check(END) || check(ELSE) || check(ELSEIF)
                || check(CATCH) || check(FINALLY) || check(EOF);
    }

    private Ast.BindingKind parseBindingKind() {
        if (match(CONST)) return Ast.BindingKind.CONST;
        if (match(VAL)) return Ast.BindingKind.VAL;
        if (match(LET)) return Ast.BindingKind.LET;
        throw error(peek(), "expected const, val, or let");
    }

    private boolean isBindingKind(Token.Type type) { return type == CONST || type == VAL || type == LET; }

    private boolean match(Token.Type... types) {
        for (Token.Type type : types) {
            if (check(type)) { advance(); return true; }
        }
        return false;
    }

    private Token consume(Token.Type type, String message) {
        if (check(type)) return advance();
        throw error(peek(), message);
    }

    private boolean check(Token.Type type) { return peek().type() == type; }
    private boolean checkNext(Token.Type type) { return current + 1 < tokens.size() && tokens.get(current + 1).type() == type; }
    private Token advance() { if (!check(EOF)) current++; return previous(); }
    private Token peek() { return tokens.get(current); }
    private Token previous() { return tokens.get(current - 1); }

    private IllegalArgumentException error(Token token, String message) {
        return new IllegalArgumentException("Oreslang parse error at " + token.line() + ":" + token.column() + ": " + message);
    }

    private record Modifiers(Ast.Visibility visibility, boolean async, boolean isStatic, boolean isAbstract) { }
}
