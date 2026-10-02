package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Target-independent ownership/borrow checker.
 *
 * Oreslang deliberately uses non-escaping borrows: &T / &mut T may be used
 * inside a lexical call scope, but cannot be returned, stored in heap fields,
 * module globals, or captured by closures. This keeps the actor model simple
 * and makes the same checked program data-race-safe when hosted by a
 * multithreaded runtime.
 */
public final class OwnershipChecker {
    private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Map<String, Ast.ModuleDecl> modules = new HashMap<>();
    private final Set<String> ambiguousFunctions = new LinkedHashSet<>();
    private final Set<String> ambiguousClasses = new LinkedHashSet<>();

    private OwnershipChecker(Ast.Program program) {
        index(program);
    }

    public static Ast.Program check(Ast.Program program) {
        OwnershipChecker checker = new OwnershipChecker(program);
        checker.validate(program);
        return program;
    }

    private void index(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            modules.put(module.name(), module);
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) index(functions, ambiguousFunctions, module.name(), fn.name(), fn);
                else if (decl instanceof Ast.ClassDecl klass) index(classes, ambiguousClasses, module.name(), klass.name(), klass);
            }
        }
    }

    private static <T> void index(Map<String, T> map, Set<String> ambiguous, String module, String name, T value) {
        map.put(module + "." + name, value);
        T existing = map.putIfAbsent(name, value);
        if (existing != null && existing != value) {
            ambiguous.add(name);
            map.remove(name);
        }
    }

    private void validate(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) checkFunction(module.name(), fn);
                else if (decl instanceof Ast.ClassDecl klass) checkClass(module.name(), klass);
                else if (decl instanceof Ast.FieldDecl field) checkModuleField(module.name(), field);
            }
        }
    }

    private void checkModuleField(String module, Ast.FieldDecl field) {
        if (containsBorrow(field.type()) || isBorrowExpression(field.initializer())) {
            throw new IllegalArgumentException("module binding '" + module + "." + field.name()
                    + "' cannot store a borrowed reference; store an owned or shared-readonly value");
        }
    }

    private void checkFunction(String module, Ast.FunctionDecl fn) {
        rejectBorrowReturn(module + "." + fn.name(), fn.returnType());
        Scope parameters = new Scope(null);
        for (Ast.Param param : fn.parameters()) defineParam(parameters, param, Origin.PARAMETER);
        checkBlock(fn.body(), parameters, null);
        parameters.close();
    }

    private void checkClass(String module, Ast.ClassDecl klass) {
        for (Ast.FieldDecl field : klass.fields()) {
            if (containsBorrow(field.type())) {
                throw new IllegalArgumentException("class field '" + module + "." + klass.name() + "." + field.name()
                        + "' cannot store a borrowed reference; use owned data or an immutable shared handle");
            }
        }
        for (Ast.MethodDecl method : klass.methods()) {
            rejectBorrowReturn(module + "." + klass.name() + "." + method.name(), method.returnType());
            Scope parameters = new Scope(null);
            if (!method.isStatic()) {
                parameters.define(new Binding("self", Ast.TypeRef.simple(qualifiedClassName(module, klass)),
                        method.mutableReceiver(), ValueKind.OWNED, Origin.SELF));
            }
            for (Ast.Param param : method.parameters()) defineParam(parameters, param, Origin.PARAMETER);
            checkBlock(method.body(), parameters, null);
            parameters.close();
        }
    }

    private void rejectBorrowReturn(String callable, Ast.TypeRef returnType) {
        if (containsBorrow(returnType)) {
            throw new IllegalArgumentException("borrowed references are non-escaping in Oreslang: '" + callable
                    + "' must return owned/shared data instead of " + show(returnType));
        }
    }

    private void defineParam(Scope scope, Ast.Param param, Origin origin) {
        scope.define(new Binding(param.name(), param.type(), param.mutable(), kind(param.type()), origin));
    }

    private void checkBlock(List<Ast.Stmt> body, Scope parent, String recursiveClosureName) {
        Scope scope = new Scope(parent);
        try {
            for (Ast.Stmt stmt : body) {
                StatementContext statement = new StatementContext();
                try {
                    checkStatement(stmt, scope, statement, recursiveClosureName);
                } finally {
                    statement.releaseTemporaries();
                }
            }
        } finally {
            scope.close();
        }
    }

    private void checkStatement(Ast.Stmt stmt, Scope scope, StatementContext statement, String recursiveClosureName) {
        if (stmt instanceof Ast.BindingStmt binding) {
            Ast.TypeRef type = binding.declaredType() != null ? binding.declaredType() : inferType(binding.initializer(), scope);
            Binding declared = new Binding(binding.name(), type, binding.kind() == Ast.BindingKind.LET, kind(type), Origin.LOCAL);

            if (binding.initializer() instanceof Ast.LambdaExpr lambda) {
                // Reserve first so recursive lambdas resolve their own name.
                scope.define(declared);
                declared.closure = analyzeLambda(lambda, scope, binding.name());
                return;
            }

            if (isBorrowExpression(binding.initializer())) {
                BorrowLease lease = analyzeBorrowValue(binding.initializer(), scope, statement);
                declared.lease = lease;
                declared.kind = lease.mutable ? ValueKind.MUT_REF : ValueKind.SHARED_REF;
                declared.type = type;
                statement.retain(lease);
                scope.define(declared);
                return;
            }

            use(binding.initializer(), Use.MOVE, scope, statement);
            scope.define(declared);
            return;
        }

        if (stmt instanceof Ast.DestructureStmt destructure) {
            use(destructure.initializer(), Use.MOVE, scope, statement);
            for (Ast.DestructureBinding item : destructure.bindings()) {
                scope.define(new Binding(item.name(), Ast.TypeRef.inferred(),
                        item.kind() == Ast.BindingKind.LET, ValueKind.OWNED, Origin.LOCAL));
            }
            return;
        }

        if (stmt instanceof Ast.ReturnStmt ret) {
            if (ret.value() == null) return;
            if (isBorrowExpression(ret.value()) || returnsReferenceBinding(ret.value(), scope)) {
                throw new IllegalArgumentException("borrowed references cannot escape a callable");
            }
            if (ret.value() instanceof Ast.LambdaExpr lambda) {
                analyzeLambda(lambda, scope, null); // captures are owned by construction
            } else {
                Binding closure = closureBinding(ret.value(), scope);
                if (closure != null && closure.closure != null && closure.closure.hasBorrowedCapture) {
                    throw new IllegalArgumentException("closure with borrowed captures cannot escape");
                }
                use(ret.value(), Use.MOVE, scope, statement);
            }
            return;
        }

        if (stmt instanceof Ast.ExprStmt expr) {
            use(expr.expression(), Use.READ, scope, statement);
            return;
        }

        if (stmt instanceof Ast.DeferStmt defer) {
            // Defer executes before this lexical scope closes. Treat captures
            // as reads and do not allow it to resurrect moved values.
            use(defer.expression(), Use.READ, scope, statement);
            return;
        }

        if (stmt instanceof Ast.IfStmt conditional) {
            Map<Binding, State> before = snapshot(scope);
            List<Map<Binding, State>> exits = new ArrayList<>();
            for (Ast.IfBranch branch : conditional.branches()) {
                restore(before);
                use(branch.condition(), Use.READ, scope, statement);
                checkBlock(branch.body(), scope, recursiveClosureName);
                exits.add(snapshot(scope));
            }
            restore(before);
            checkBlock(conditional.elseBody(), scope, recursiveClosureName);
            exits.add(snapshot(scope));
            restore(before);
            mergeBranchStates(before, exits);
            return;
        }

        if (stmt instanceof Ast.TryStmt attempted) {
            Map<Binding, State> before = snapshot(scope);
            List<Map<Binding, State>> exits = new ArrayList<>();

            restore(before);
            checkBlock(attempted.body(), scope, recursiveClosureName);
            exits.add(snapshot(scope));

            restore(before);
            Scope caught = new Scope(scope);
            caught.define(new Binding(attempted.errorName(), Ast.TypeRef.inferred(), false, ValueKind.OWNED, Origin.LOCAL));
            checkBlock(attempted.catchBody(), caught, recursiveClosureName);
            caught.close();
            exits.add(snapshot(scope));

            restore(before);
            mergeBranchStates(before, exits);
            checkBlock(attempted.finallyBody(), scope, recursiveClosureName);
            return;
        }

        if (stmt instanceof Ast.ForOfStmt loop) {
            use(loop.iterable(), Use.READ, scope, statement);
            Map<Binding, State> before = snapshot(scope);
            Scope iteration = new Scope(scope);
            iteration.define(new Binding(loop.bindingName(), Ast.TypeRef.inferred(),
                    loop.bindingKind() == Ast.BindingKind.LET, ValueKind.OWNED, Origin.LOCAL));
            checkBlock(loop.body(), iteration, recursiveClosureName);
            iteration.close();
            rejectLoopConsumption(before, scope);
            restore(before);
            return;
        }

        if (stmt instanceof Ast.ForStmt loop) {
            Scope loopScope = new Scope(scope);
            if (loop.initializer() != null) checkStatement(loop.initializer(), loopScope, statement, recursiveClosureName);
            if (loop.condition() != null) use(loop.condition(), Use.READ, loopScope, statement);
            Map<Binding, State> before = snapshot(loopScope);
            checkBlock(loop.body(), loopScope, recursiveClosureName);
            if (loop.update() != null) use(loop.update(), Use.READ, loopScope, statement);
            rejectLoopConsumption(before, loopScope);
            restore(before);
            loopScope.close();
        }
    }

    private void use(Ast.Expr expr, Use mode, Scope scope, StatementContext statement) {
        if (expr == null) return;

        if (expr instanceof Ast.LiteralExpr) return;

        if (expr instanceof Ast.NameExpr name) {
            Binding binding = scope.lookup(name.name());
            if (binding == null) return; // global/module/function/builtin
            useBinding(binding, mode);
            return;
        }

        if (expr instanceof Ast.AssignExpr assignment) {
            use(assignment.value(), Use.MOVE, scope, statement);
            mutatePlace(assignment.target(), scope, statement);
            return;
        }

        if (expr instanceof Ast.UnaryExpr unary) {
            if (unary.operator().equals("&") || unary.operator().equals("&mut")) {
                BorrowLease lease = borrow(unary.operand(), unary.operator().equals("&mut"), scope);
                statement.addTemporary(lease);
                return;
            }
            if (unary.operator().equals("*")) {
                use(unary.operand(), Use.READ, scope, statement);
                return;
            }
            use(unary.operand(), Use.READ, scope, statement);
            return;
        }

        if (expr instanceof Ast.BinaryExpr binary) {
            use(binary.left(), Use.READ, scope, statement);
            use(binary.right(), Use.READ, scope, statement);
            return;
        }

        if (expr instanceof Ast.ConditionalExpr conditional) {
            use(conditional.condition(), Use.READ, scope, statement);
            Map<Binding, State> before = snapshot(scope);
            restore(before);
            use(conditional.whenTrue(), mode, scope, statement);
            Map<Binding, State> yes = snapshot(scope);
            restore(before);
            use(conditional.whenFalse(), mode, scope, statement);
            Map<Binding, State> no = snapshot(scope);
            restore(before);
            mergeBranchStates(before, List.of(yes, no));
            return;
        }

        if (expr instanceof Ast.CallExpr call) {
            checkCall(call, scope, statement);
            return;
        }

        if (expr instanceof Ast.MemberExpr member) {
            if (isBoundMethodValue(member, scope)) {
                Binding receiver = rootBinding(member.receiver(), scope);
                if (receiver != null && (receiver.kind == ValueKind.SHARED_REF || receiver.kind == ValueKind.MUT_REF)) {
                    throw new IllegalArgumentException("bound method values cannot capture borrowed receivers; capture/move an owned receiver instead");
                }
                use(member.receiver(), Use.MOVE, scope, statement);
            } else {
                use(member.receiver(), Use.READ, scope, statement);
            }
            return;
        }

        if (expr instanceof Ast.IndexExpr indexed) {
            use(indexed.receiver(), Use.READ, scope, statement);
            use(indexed.index(), Use.READ, scope, statement);
            return;
        }

        if (expr instanceof Ast.NewExpr created) {
            for (Ast.Expr arg : created.arguments()) use(arg, Use.MOVE, scope, statement);
            return;
        }

        if (expr instanceof Ast.AwaitExpr awaited) {
            use(awaited.expression(), Use.MOVE, scope, statement);
            return;
        }

        if (expr instanceof Ast.ListExpr list) {
            for (Ast.Expr item : list.elements()) use(item, Use.MOVE, scope, statement);
            return;
        }

        if (expr instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr item : tuple.elements()) use(item, Use.MOVE, scope, statement);
            return;
        }

        if (expr instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) use(field.value(), Use.MOVE, scope, statement);
            return;
        }

        if (expr instanceof Ast.LambdaExpr lambda) {
            ClosureInfo info = analyzeLambda(lambda, scope, null);
            if (info.hasBorrowedCapture) {
                throw new IllegalArgumentException("closures cannot capture borrowed references");
            }
        }
    }

    private void checkCall(Ast.CallExpr call, Scope scope, StatementContext statement) {
        CallSignature signature = resolveCall(call, scope);

        if (call.callee() instanceof Ast.MemberExpr member && signature != null && signature.method != null) {
            if (signature.method.mutableReceiver()) borrow(member.receiver(), true, scope).releaseAtEndOf(statement);
            else borrow(member.receiver(), false, scope).releaseAtEndOf(statement);
        } else {
            use(call.callee(), Use.READ, scope, statement);
        }

        if (signature == null) {
            for (Ast.Expr arg : call.arguments()) use(arg, Use.MOVE, scope, statement);
            return;
        }

        if (signature.params.size() != call.arguments().size()) return; // type checker owns arity errors

        for (int i = 0; i < signature.params.size(); i++) {
            Ast.Param param = signature.params.get(i);
            Ast.Expr argument = call.arguments().get(i);
            if (isReference(param.type())) {
                boolean mutable = isMutableReference(param.type());
                if (isBorrowExpression(argument)) {
                    BorrowLease lease = analyzeBorrowValue(argument, scope, statement);
                    if (mutable && !lease.mutable) {
                        throw new IllegalArgumentException("&mut parameter requires an exclusive mutable borrow");
                    }
                } else {
                    Binding ref = bindingOf(argument, scope);
                    if (ref == null || (ref.kind != ValueKind.SHARED_REF && ref.kind != ValueKind.MUT_REF)) {
                        throw new IllegalArgumentException("reference parameter requires &value, &mut value, or an existing borrow reference");
                    }
                    if (mutable && ref.kind != ValueKind.MUT_REF) {
                        throw new IllegalArgumentException("&mut parameter requires a mutable borrow reference");
                    }
                    useBinding(ref, ref.kind == ValueKind.MUT_REF ? Use.MOVE : Use.READ);
                }
            } else {
                use(argument, Use.MOVE, scope, statement);
            }
        }
    }

    private CallSignature resolveCall(Ast.CallExpr call, Scope scope) {
        if (call.callee() instanceof Ast.NameExpr name) {
            Ast.FunctionDecl fn = findFunction(name.name());
            if (fn != null) return new CallSignature(fn.parameters(), null);
            Binding local = scope.lookup(name.name());
            if (local != null && local.type != null && local.type.name().equals("Fnc") && !local.type.arguments().isEmpty()) {
                List<Ast.Param> params = new ArrayList<>();
                for (int i = 0; i < local.type.arguments().size() - 1; i++) {
                    params.add(new Ast.Param(local.type.arguments().get(i), "$arg" + i));
                }
                return new CallSignature(params, null);
            }
        }

        if (call.callee() instanceof Ast.MemberExpr member) {
            String className = classNameOf(member.receiver(), scope);
            if (className != null) {
                Ast.ClassDecl klass = findClass(className);
                if (klass != null) {
                    Ast.MethodDecl method = findMethod(klass, member.member(), call.arguments().size(), new LinkedHashSet<>());
                    if (method != null) return new CallSignature(method.parameters(), method);
                }
            }
        }
        return null;
    }

    private ClosureInfo analyzeLambda(Ast.LambdaExpr lambda, Scope outer, String recursiveName) {
        Set<String> referenced = captureNames(lambda, recursiveName);
        Scope closure = new Scope(null);
        boolean borrowed = false;

        for (String name : referenced) {
            Binding source = outer.lookup(name);
            if (source == null) continue;
            if (source.kind == ValueKind.SHARED_REF || source.kind == ValueKind.MUT_REF) {
                borrowed = true;
                throw new IllegalArgumentException("closure cannot capture borrowed reference '" + name
                        + "'; capture owned data or immutable shared data instead");
            }
            if (source.mutable) {
                moveMutableCapture(source);
            } else if (source.kind != ValueKind.COPY) {
                move(source);
            } else {
                read(source);
            }
            Binding capture = new Binding(name, source.type, source.mutable, source.kind, Origin.CAPTURE);
            capture.closure = source.closure;
            closure.define(capture);
        }

        for (Ast.Param param : lambda.parameters()) defineParam(closure, param, Origin.PARAMETER);
        checkBlock(lambda.blockBody(), closure, recursiveName);
        closure.close();
        return new ClosureInfo(borrowed);
    }

    private Set<String> captureNames(Ast.LambdaExpr lambda, String recursiveName) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        LinkedHashSet<String> locals = new LinkedHashSet<>();
        for (Ast.Param param : lambda.parameters()) locals.add(param.name());
        if (recursiveName != null) locals.add(recursiveName);
        collectNames(lambda.blockBody(), locals, names);
        return names;
    }

    private void collectNames(List<Ast.Stmt> body, Set<String> locals, Set<String> out) {
        Set<String> current = new LinkedHashSet<>(locals);
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.BindingStmt binding) {
                collectNames(binding.initializer(), current, out);
                current.add(binding.name());
            } else if (stmt instanceof Ast.DestructureStmt destructure) {
                collectNames(destructure.initializer(), current, out);
                for (Ast.DestructureBinding b : destructure.bindings()) current.add(b.name());
            } else if (stmt instanceof Ast.ReturnStmt ret) {
                collectNames(ret.value(), current, out);
            } else if (stmt instanceof Ast.ExprStmt expr) {
                collectNames(expr.expression(), current, out);
            } else if (stmt instanceof Ast.DeferStmt defer) {
                collectNames(defer.expression(), current, out);
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    collectNames(branch.condition(), current, out);
                    collectNames(branch.body(), current, out);
                }
                collectNames(conditional.elseBody(), current, out);
            } else if (stmt instanceof Ast.TryStmt attempted) {
                collectNames(attempted.body(), current, out);
                Set<String> caught = new LinkedHashSet<>(current);
                caught.add(attempted.errorName());
                collectNames(attempted.catchBody(), caught, out);
                collectNames(attempted.finallyBody(), current, out);
            } else if (stmt instanceof Ast.ForOfStmt loop) {
                collectNames(loop.iterable(), current, out);
                Set<String> nested = new LinkedHashSet<>(current);
                nested.add(loop.bindingName());
                collectNames(loop.body(), nested, out);
            } else if (stmt instanceof Ast.ForStmt loop) {
                if (loop.initializer() instanceof Ast.BindingStmt binding) {
                    collectNames(binding.initializer(), current, out);
                    current.add(binding.name());
                } else if (loop.initializer() instanceof Ast.ExprStmt expr) {
                    collectNames(expr.expression(), current, out);
                }
                collectNames(loop.condition(), current, out);
                collectNames(loop.update(), current, out);
                collectNames(loop.body(), current, out);
            }
        }
    }

    private void collectNames(Ast.Expr expr, Set<String> locals, Set<String> out) {
        if (expr == null) return;
        if (expr instanceof Ast.NameExpr name) {
            if (!locals.contains(name.name())) out.add(name.name());
        } else if (expr instanceof Ast.AssignExpr a) {
            collectNames(a.target(), locals, out); collectNames(a.value(), locals, out);
        } else if (expr instanceof Ast.UnaryExpr u) collectNames(u.operand(), locals, out);
        else if (expr instanceof Ast.BinaryExpr b) { collectNames(b.left(), locals, out); collectNames(b.right(), locals, out); }
        else if (expr instanceof Ast.ConditionalExpr c) {
            collectNames(c.condition(), locals, out); collectNames(c.whenTrue(), locals, out); collectNames(c.whenFalse(), locals, out);
        } else if (expr instanceof Ast.CallExpr call) {
            collectNames(call.callee(), locals, out);
            for (Ast.Expr arg : call.arguments()) collectNames(arg, locals, out);
        } else if (expr instanceof Ast.MemberExpr m) collectNames(m.receiver(), locals, out);
        else if (expr instanceof Ast.IndexExpr i) { collectNames(i.receiver(), locals, out); collectNames(i.index(), locals, out); }
        else if (expr instanceof Ast.NewExpr n) for (Ast.Expr arg : n.arguments()) collectNames(arg, locals, out);
        else if (expr instanceof Ast.AwaitExpr a) collectNames(a.expression(), locals, out);
        else if (expr instanceof Ast.ListExpr l) for (Ast.Expr item : l.elements()) collectNames(item, locals, out);
        else if (expr instanceof Ast.TupleExpr t) for (Ast.Expr item : t.elements()) collectNames(item, locals, out);
        else if (expr instanceof Ast.ObjectExpr o) for (Ast.ObjectField field : o.fields()) collectNames(field.value(), locals, out);
        else if (expr instanceof Ast.LambdaExpr lambda) {
            Set<String> nested = new LinkedHashSet<>(locals);
            for (Ast.Param param : lambda.parameters()) nested.add(param.name());
            collectNames(lambda.blockBody(), nested, out);
        }
    }

    private BorrowLease analyzeBorrowValue(Ast.Expr expr, Scope scope, StatementContext statement) {
        if (!(expr instanceof Ast.UnaryExpr unary) || !(unary.operator().equals("&") || unary.operator().equals("&mut"))) {
            throw new IllegalArgumentException("expected borrow expression");
        }
        BorrowLease lease = borrow(unary.operand(), unary.operator().equals("&mut"), scope);
        statement.addTemporary(lease);
        return lease;
    }

    private BorrowLease borrow(Ast.Expr place, boolean mutable, Scope scope) {
        Binding binding = rootBinding(place, scope);
        if (binding == null) throw new IllegalArgumentException("borrow requires a local/parameter place");

        if (binding.kind == ValueKind.SHARED_REF || binding.kind == ValueKind.MUT_REF) {
            read(binding);
            if (mutable && binding.kind != ValueKind.MUT_REF) {
                throw new IllegalArgumentException("cannot create mutable borrow through an immutable reference");
            }
            return BorrowLease.noop(mutable);
        }

        if (binding.moved) throw new IllegalArgumentException("cannot borrow moved value '" + binding.name + "'");
        if (mutable) {
            if (!isMutablePlace(place, scope)) throw new IllegalArgumentException("&mut requires a mutable binding/place");
            if (binding.mutableBorrow || binding.sharedBorrows > 0) {
                throw new IllegalArgumentException("cannot mutably borrow '" + binding.name + "' while another borrow is live");
            }
            binding.mutableBorrow = true;
        } else {
            if (binding.mutableBorrow) {
                throw new IllegalArgumentException("cannot immutably borrow '" + binding.name + "' while an exclusive borrow is live");
            }
            binding.sharedBorrows++;
        }
        return new BorrowLease(binding, mutable);
    }

    private void mutatePlace(Ast.Expr place, Scope scope, StatementContext statement) {
        if (place instanceof Ast.UnaryExpr unary && unary.operator().equals("*")) {
            Binding reference = bindingOf(unary.operand(), scope);
            if (reference == null || reference.kind != ValueKind.MUT_REF) {
                throw new IllegalArgumentException("mutation through dereference requires &mut");
            }
            useBinding(reference, Use.READ);
            return;
        }

        Binding root = rootBinding(place, scope);
        if (root == null) throw new IllegalArgumentException("mutation requires a local/parameter place");

        if (root.kind == ValueKind.MUT_REF) {
            read(root);
            return;
        }
        if (root.kind == ValueKind.SHARED_REF) {
            throw new IllegalArgumentException("cannot mutate through immutable borrow '" + root.name + "'");
        }
        if (!isMutablePlace(place, scope)) {
            throw new IllegalArgumentException("cannot mutate immutable binding '" + root.name
                    + "'; use let, a mut parameter, or &mut");
        }
        if (root.moved) throw new IllegalArgumentException("cannot mutate moved value '" + root.name + "'");
        if (root.mutableBorrow || root.sharedBorrows > 0) {
            throw new IllegalArgumentException("cannot mutate '" + root.name + "' while it is borrowed");
        }
        root.moved = false; // assignment may reinitialize a previously moved let.
    }

    private boolean isMutablePlace(Ast.Expr place, Scope scope) {
        if (place instanceof Ast.NameExpr name) {
            Binding b = scope.lookup(name.name());
            return b != null && (b.mutable || b.kind == ValueKind.MUT_REF);
        }
        if (place instanceof Ast.MemberExpr member) {
            Binding root = rootBinding(member, scope);
            return root != null && (root.mutable || root.kind == ValueKind.MUT_REF);
        }
        if (place instanceof Ast.IndexExpr indexed) {
            Binding root = rootBinding(indexed, scope);
            return root != null && (root.mutable || root.kind == ValueKind.MUT_REF);
        }
        if (place instanceof Ast.UnaryExpr unary && unary.operator().equals("*")) {
            Binding ref = bindingOf(unary.operand(), scope);
            return ref != null && ref.kind == ValueKind.MUT_REF;
        }
        return false;
    }

    private Binding rootBinding(Ast.Expr expr, Scope scope) {
        if (expr instanceof Ast.NameExpr name) return scope.lookup(name.name());
        if (expr instanceof Ast.MemberExpr member) return rootBinding(member.receiver(), scope);
        if (expr instanceof Ast.IndexExpr indexed) return rootBinding(indexed.receiver(), scope);
        if (expr instanceof Ast.UnaryExpr unary && unary.operator().equals("*")) return bindingOf(unary.operand(), scope);
        return null;
    }

    private Binding bindingOf(Ast.Expr expr, Scope scope) {
        return expr instanceof Ast.NameExpr name ? scope.lookup(name.name()) : null;
    }

    private Binding closureBinding(Ast.Expr expr, Scope scope) {
        return bindingOf(expr, scope);
    }

    private boolean returnsReferenceBinding(Ast.Expr expr, Scope scope) {
        Binding binding = bindingOf(expr, scope);
        return binding != null && (binding.kind == ValueKind.SHARED_REF || binding.kind == ValueKind.MUT_REF);
    }

    private void useBinding(Binding binding, Use mode) {
        if (mode == Use.MOVE) move(binding);
        else read(binding);
    }

    private void read(Binding binding) {
        if (binding.moved) throw new IllegalArgumentException("use of moved value '" + binding.name + "'");
        if (binding.mutableBorrow && binding.kind != ValueKind.MUT_REF) {
            throw new IllegalArgumentException("cannot directly use '" + binding.name + "' while it is mutably borrowed");
        }
    }

    private void moveMutableCapture(Binding binding) {
        if (binding.moved) throw new IllegalArgumentException("use of moved value '" + binding.name + "'");
        if (binding.mutableBorrow || binding.sharedBorrows > 0) {
            throw new IllegalArgumentException("cannot move mutable capture '" + binding.name + "' while it is borrowed");
        }
        // Even Copy scalars live in a mutable binding cell. The closure owns
        // that cell after capture, so the outer binding must become unavailable.
        binding.moved = true;
    }

    private void move(Binding binding) {
        if (binding.kind == ValueKind.COPY || binding.kind == ValueKind.SHARED_REF) {
            read(binding);
            return;
        }
        if (binding.moved) throw new IllegalArgumentException("use of moved value '" + binding.name + "'");
        if (binding.mutableBorrow || binding.sharedBorrows > 0) {
            throw new IllegalArgumentException("cannot move '" + binding.name + "' while it is borrowed");
        }
        binding.moved = true;
    }

    private Ast.TypeRef inferType(Ast.Expr expr, Scope scope) {
        if (expr instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value instanceof Boolean) return Ast.TypeRef.simple("bool");
            if (value instanceof String) return Ast.TypeRef.simple("String");
            if (value instanceof Long) return Ast.TypeRef.simple("int");
            if (value instanceof Double) return Ast.TypeRef.simple("float");
            if (value instanceof Ast.Imaginary) return Ast.TypeRef.simple("complex");
        }
        if (expr instanceof Ast.NameExpr name) {
            Binding binding = scope.lookup(name.name());
            if (binding != null) return binding.type;
        }
        if (expr instanceof Ast.NewExpr created) return created.type();
        if (expr instanceof Ast.UnaryExpr unary) {
            Ast.TypeRef target = inferType(unary.operand(), scope);
            if (unary.operator().equals("&")) return new Ast.TypeRef("Ref", List.of(target), false);
            if (unary.operator().equals("&mut")) return new Ast.TypeRef("MutRef", List.of(target), false);
            if (unary.operator().equals("*") && isReference(target)) return target.arguments().getFirst();
        }
        if (expr instanceof Ast.LambdaExpr) return new Ast.TypeRef("Fnc", List.of(Ast.TypeRef.simple("void")), false);
        if (expr instanceof Ast.ListExpr) return new Ast.TypeRef("Array", List.of(Ast.TypeRef.inferred()), false);
        return Ast.TypeRef.inferred();
    }

    private ValueKind kind(Ast.TypeRef type) {
        if (type == null) return ValueKind.OWNED;
        if (type.name().equals("Ref")) return ValueKind.SHARED_REF;
        if (type.name().equals("MutRef")) return ValueKind.MUT_REF;
        return switch (type.name()) {
            case "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64",
                    "int", "uint", "bigint", "f32", "f64", "float", "decimal",
                    "complex64", "complex128", "complex", "bool", "Bool",
                    "string", "String", "void" -> ValueKind.COPY;
            default -> ValueKind.OWNED;
        };
    }

    private boolean isReference(Ast.TypeRef type) {
        return type != null && (type.name().equals("Ref") || type.name().equals("MutRef"));
    }

    private boolean isMutableReference(Ast.TypeRef type) {
        return type != null && type.name().equals("MutRef");
    }

    private boolean containsBorrow(Ast.TypeRef type) {
        if (type == null) return false;
        if (isReference(type)) return true;
        for (Ast.TypeRef argument : type.arguments()) if (containsBorrow(argument)) return true;
        return false;
    }

    private boolean isBorrowExpression(Ast.Expr expr) {
        return expr instanceof Ast.UnaryExpr unary
                && (unary.operator().equals("&") || unary.operator().equals("&mut"));
    }

    private String classNameOf(Ast.Expr expr, Scope scope) {
        if (expr instanceof Ast.NewExpr created) return created.type().name();
        Binding binding = bindingOf(expr, scope);
        if (binding != null && binding.type != null) {
            Ast.TypeRef type = binding.type;
            if (isReference(type) && !type.arguments().isEmpty()) type = type.arguments().getFirst();
            return type.name();
        }
        if (expr instanceof Ast.MemberExpr member && member.receiver() instanceof Ast.NameExpr namespace) {
            return namespace.name() + "." + member.member();
        }
        return null;
    }

    private boolean isBoundMethodValue(Ast.MemberExpr member, Scope scope) {
        String className = classNameOf(member.receiver(), scope);
        if (className == null) return false;
        Ast.ClassDecl klass = findClass(className);
        return klass != null && hasInstanceMethod(klass, member.member(), new LinkedHashSet<>());
    }

    private boolean hasInstanceMethod(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return false;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.name().equals(name)) {
                seen.remove(klass);
                return true;
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = findClass(parentRef.name());
            if (parent != null && hasInstanceMethod(parent, name, seen)) {
                seen.remove(klass);
                return true;
            }
        }
        seen.remove(klass);
        return false;
    }

    private Ast.FunctionDecl findFunction(String name) {
        if (ambiguousFunctions.contains(name)) return null;
        return functions.get(name);
    }

    private Ast.ClassDecl findClass(String name) {
        if (ambiguousClasses.contains(name)) return null;
        return classes.get(name);
    }

    private Ast.MethodDecl findMethod(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.name().equals(name) && method.arity() == arity) {
                seen.remove(klass);
                return method;
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = findClass(parentRef.name());
            if (parent == null) continue;
            Ast.MethodDecl result = findMethod(parent, name, arity, seen);
            if (result != null) {
                seen.remove(klass);
                return result;
            }
        }
        seen.remove(klass);
        return null;
    }

    private void rejectLoopConsumption(Map<Binding, State> before, Scope scope) {
        Map<Binding, State> after = snapshot(scope);
        for (Map.Entry<Binding, State> entry : before.entrySet()) {
            State next = after.get(entry.getKey());
            if (next != null && !entry.getValue().moved && next.moved) {
                throw new IllegalArgumentException("loop body cannot move outer value '" + entry.getKey().name
                        + "' because the loop may execute again");
            }
        }
    }

    private Map<Binding, State> snapshot(Scope scope) {
        LinkedHashMap<Binding, State> result = new LinkedHashMap<>();
        for (Binding binding : scope.visibleBindings()) result.put(binding, State.of(binding));
        return result;
    }

    private void restore(Map<Binding, State> snapshot) {
        for (Map.Entry<Binding, State> entry : snapshot.entrySet()) entry.getValue().apply(entry.getKey());
    }

    private void mergeBranchStates(Map<Binding, State> before, List<Map<Binding, State>> exits) {
        for (Binding binding : before.keySet()) {
            boolean moved = false;
            int shared = before.get(binding).sharedBorrows;
            boolean mutableBorrow = before.get(binding).mutableBorrow;
            for (Map<Binding, State> exit : exits) {
                State state = exit.get(binding);
                if (state != null) {
                    moved |= state.moved;
                    shared = Math.max(shared, state.sharedBorrows);
                    mutableBorrow |= state.mutableBorrow;
                }
            }
            binding.moved = moved;
            binding.sharedBorrows = shared;
            binding.mutableBorrow = mutableBorrow;
        }
    }

    private String qualifiedClassName(String module, Ast.ClassDecl klass) {
        return module.equals("__root__") ? klass.name() : module + "." + klass.name();
    }

    private String show(Ast.TypeRef type) {
        if (type == null) return "<inferred>";
        if (type.name().equals("Ref") || type.name().equals("MutRef")) {
            return (type.name().equals("MutRef") ? "&mut " : "&") + show(type.arguments().getFirst());
        }
        if (type.arguments().isEmpty()) return type.name();
        return type.name() + "<" + String.join(",", type.arguments().stream().map(this::show).toList()) + ">";
    }

    private enum Use { READ, MOVE }
    private enum Origin { PARAMETER, SELF, LOCAL, CAPTURE }
    private enum ValueKind { COPY, OWNED, SHARED_REF, MUT_REF }

    private static final class Binding {
        private final String name;
        private Ast.TypeRef type;
        private final boolean mutable;
        private ValueKind kind;
        private final Origin origin;
        private boolean moved;
        private int sharedBorrows;
        private boolean mutableBorrow;
        private BorrowLease lease;
        private ClosureInfo closure;

        private Binding(String name, Ast.TypeRef type, boolean mutable, ValueKind kind, Origin origin) {
            this.name = name;
            this.type = type;
            this.mutable = mutable;
            this.kind = kind;
            this.origin = origin;
        }
    }

    private static final class Scope {
        private final Scope parent;
        private final Map<String, Binding> bindings = new LinkedHashMap<>();

        private Scope(Scope parent) { this.parent = parent; }

        private void define(Binding binding) {
            if (bindings.putIfAbsent(binding.name, binding) != null) {
                throw new IllegalArgumentException("duplicate ownership binding '" + binding.name + "'");
            }
        }

        private Binding lookup(String name) {
            Binding binding = bindings.get(name);
            return binding != null ? binding : parent == null ? null : parent.lookup(name);
        }

        private List<Binding> visibleBindings() {
            LinkedHashMap<String, Binding> visible = new LinkedHashMap<>();
            if (parent != null) for (Binding binding : parent.visibleBindings()) visible.put(binding.name, binding);
            visible.putAll(bindings);
            return List.copyOf(visible.values());
        }

        private void close() {
            List<Binding> local = new ArrayList<>(bindings.values());
            for (int i = local.size() - 1; i >= 0; i--) {
                Binding binding = local.get(i);
                if (binding.lease != null) binding.lease.release();
            }
            bindings.clear();
        }
    }

    private static final class StatementContext {
        private final ArrayDeque<BorrowLease> temporaries = new ArrayDeque<>();
        private final Set<BorrowLease> retained = new LinkedHashSet<>();

        private void addTemporary(BorrowLease lease) {
            if (!lease.noop) temporaries.push(lease);
        }

        private void retain(BorrowLease lease) { retained.add(lease); }

        private void releaseTemporaries() {
            while (!temporaries.isEmpty()) {
                BorrowLease lease = temporaries.pop();
                if (!retained.contains(lease)) lease.release();
            }
        }
    }

    private static final class BorrowLease {
        private final Binding owner;
        private final boolean mutable;
        private final boolean noop;
        private boolean released;

        private BorrowLease(Binding owner, boolean mutable) {
            this.owner = owner;
            this.mutable = mutable;
            this.noop = false;
        }

        private BorrowLease(boolean mutable) {
            this.owner = null;
            this.mutable = mutable;
            this.noop = true;
        }

        private static BorrowLease noop(boolean mutable) { return new BorrowLease(mutable); }

        private void releaseAtEndOf(StatementContext context) { context.addTemporary(this); }

        private void release() {
            if (released || noop) return;
            released = true;
            if (mutable) owner.mutableBorrow = false;
            else owner.sharedBorrows--;
        }
    }

    private record ClosureInfo(boolean hasBorrowedCapture) { }
    private record CallSignature(List<Ast.Param> params, Ast.MethodDecl method) { }

    private record State(boolean moved, int sharedBorrows, boolean mutableBorrow) {
        private static State of(Binding binding) {
            return new State(binding.moved, binding.sharedBorrows, binding.mutableBorrow);
        }
        private void apply(Binding binding) {
            binding.moved = moved;
            binding.sharedBorrows = sharedBorrows;
            binding.mutableBorrow = mutableBorrow;
        }
    }
}
