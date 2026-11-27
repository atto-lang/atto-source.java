package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.ActorScalarSuspendingMachine;
import dev.oreslang.runtime.ActorScalarSuspendingMachine.Instruction;
import dev.oreslang.runtime.ActorScalarSuspendingMachine.Op;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Opt-in, fail-closed compiler slice for verified top-level suspends int
 * functions, nested CALL and `await async_fn()` scalar futures.
 *
 * <p>Always runs the full production parser, type checker and ownership checker
 * before accepting a bounded subset of the checked AST. Async zero-argument
 * int functions become explicit scheduler-side Future ports; they are not
 * evaluated as Java closures and no host pointer is embedded in guest frames.
 *
 * <p>This is NOT a complete actor receive, object, defer, critical-section,
 * exception, or generic source lowering. Unsupported nodes fail before any
 * actor task is admitted.
 */
public final class ActorScalarSuspendingLowering {
    private ActorScalarSuspendingLowering() { }

    public record Compiled(ActorScalarSuspendingMachine.Program program, int entry) { }

    private record Pending(Ast.FunctionDecl source, Map<String,Integer> slots) { }

    public static Compiled compile(String source, String entryName) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(entryName);
        Ast.Program checked = OresCompiler.parseAndTypeCheck(source);
        if (!checked.imports().isEmpty()) {
            throw new IllegalArgumentException("scalar suspends slice rejects imports");
        }
        Map<String,Ast.FunctionDecl> suspends = new LinkedHashMap<>();
        Map<String,Ast.FunctionDecl> asyncPorts = new LinkedHashMap<>();
        for (Ast.ModuleDecl module : checked.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.FunctionDecl fn)) {
                    throw new IllegalArgumentException("scalar suspends slice rejects non-function declarations");
                }
                if (!fn.genericParameters().isEmpty() || fn.generator()
                        || fn.trapped() || fn.nonLexical() || fn.structural()
                        || fn.actorKind() != Ast.ActorKind.NONE
                        || fn.returnType() == null || !isInt(fn.returnType())) {
                    throw new IllegalArgumentException("unsupported scalar callable declaration");
                }
                if (fn.suspends() && !fn.async()) {
                    if (suspends.putIfAbsent(fn.name(), fn) != null) {
                        throw new IllegalArgumentException("ambiguous suspendable function name");
                    }
                } else if (fn.async() && !fn.suspends() && fn.parameters().isEmpty()) {
                    if (asyncPorts.putIfAbsent(fn.name(), fn) != null) {
                        throw new IllegalArgumentException("ambiguous async port");
                    }
                } else {
                    throw new IllegalArgumentException("only suspends functions and async int ports admitted");
                }
            }
        }
        for (String portName : asyncPorts.keySet()) {
            if (suspends.containsKey(portName)) {
                throw new IllegalArgumentException("ambiguous scalar port and function name");
            }
        }
        if (!suspends.containsKey(entryName)) {
            throw new IllegalArgumentException("entry must be a checked suspends function");
        }
        if (suspends.size() > 256 || asyncPorts.size() > 32) {
            throw new IllegalArgumentException("scalar suspends image exceeds quotas");
        }
        var fnIds = new LinkedHashMap<String,Integer>();
        for (String name : suspends.keySet()) fnIds.put(name, fnIds.size());
        var portIds = new LinkedHashMap<String,Integer>();
        for (String name : asyncPorts.keySet()) portIds.put(name, portIds.size());
        List<ActorScalarSuspendingMachine.Function> lowered = new ArrayList<>();
        for (Ast.FunctionDecl fn : suspends.values()) {
            var slots = new LinkedHashMap<String,Integer>();
            for (Ast.Param param : fn.parameters()) {
                if (!isInt(param.type()) || slots.putIfAbsent(param.name(),slots.size()) != null) {
                    throw new IllegalArgumentException("only unique int scalar parameters supported");
                }
            }
            var instructions = new ArrayList<Instruction>();
            boolean terminated = false;
            for (Ast.Stmt stmt : fn.body()) {
                if (terminated) throw new IllegalArgumentException("unreachable scalar statement");
                if (stmt instanceof Ast.BindingStmt binding) {
                    if (binding.declaredType() != null && !isInt(binding.declaredType())) {
                        throw new IllegalArgumentException("only int locals supported");
                    }
                    if (slots.containsKey(binding.name()) || slots.size() >= 32) {
                        throw new IllegalArgumentException("duplicate or excessive scalar local");
                    }
                    // The binding is not in scope during its initializer.
                    emit(binding.initializer(), slots, fnIds, portIds, instructions);
                    int slot = slots.size();
                    slots.put(binding.name(), slot);
                    instructions.add(Instruction.at(Op.STORE, slot));
                } else if (stmt instanceof Ast.ReturnStmt returned) {
                    emit(returned.value(), slots, fnIds, portIds, instructions);
                    instructions.add(Instruction.of(Op.RET));
                    terminated = true;
                } else {
                    throw new IllegalArgumentException("scalar suspends slice rejects statement");
                }
            }
            if (!terminated) throw new IllegalArgumentException("scalar suspends function needs a return");
            // Fixed verified per-frame operand headroom; any runtime mismatch
            // fails inside ActorCallContinuation instead of spilling to host.
            lowered.add(new ActorScalarSuspendingMachine.Function(
                    fn.name(), fn.parameters().size(), slots.size(), 32, instructions));
        }
        return new Compiled(
                new ActorScalarSuspendingMachine.Program(lowered, List.copyOf(portIds.keySet())),
                fnIds.get(entryName));
    }

    private static boolean isInt(Ast.TypeRef type) {
        return type != null && type.name().equals("int")
                && type.arguments().isEmpty() && !type.inferArguments();
    }

    private static void emit(
            Ast.Expr expr, Map<String,Integer> locals,
            Map<String,Integer> calls, Map<String,Integer> ports,
            List<Instruction> out) {
        if (expr instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value instanceof Long n) {
                out.add(Instruction.constant(n));
                return;
            }
            if (value instanceof Integer n) {
                out.add(Instruction.constant(n.longValue()));
                return;
            }
            if (value instanceof BigInteger n && n.bitLength() <= 63) {
                out.add(Instruction.constant(n.longValueExact()));
                return;
            }
            throw new IllegalArgumentException("only checked i64 literals supported");
        }
        if (expr instanceof Ast.NameExpr name) {
            Integer slot = locals.get(name.name());
            if (slot == null) throw new IllegalArgumentException("unknown scalar local");
            out.add(Instruction.at(Op.LOAD, slot));
            return;
        }
        if (expr instanceof Ast.BinaryExpr binary && binary.operator().equals("+")) {
            emit(binary.left(),locals,calls,ports,out);
            emit(binary.right(),locals,calls,ports,out);
            out.add(Instruction.of(Op.ADD));
            return;
        }
        if (expr instanceof Ast.CallExpr call
                && call.callee() instanceof Ast.NameExpr name
                && !call.typeArgumentsPresent()) {
            Integer target = calls.get(name.name());
            if (target == null) {
                throw new IllegalArgumentException("only direct known suspends scalar calls allowed");
            }
            for (Ast.Expr arg : call.arguments()) emit(arg,locals,calls,ports,out);
            out.add(Instruction.at(Op.CALL,target));
            return;
        }
        if (expr instanceof Ast.AwaitExpr await
                && await.expression() instanceof Ast.CallExpr call
                && call.callee() instanceof Ast.NameExpr name
                && call.arguments().isEmpty() && !call.typeArgumentsPresent()) {
            Integer port = ports.get(name.name());
            if (port == null) throw new IllegalArgumentException("unknown authorized scalar future port");
            out.add(Instruction.at(Op.AWAIT_PORT,port));
            return;
        }
        throw new IllegalArgumentException("unsupported scalar suspends expression");
    }
}
