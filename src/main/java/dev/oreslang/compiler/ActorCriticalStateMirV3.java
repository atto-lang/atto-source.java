package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Declared-actor critical-state MIR v3, compiled only from the same manifest
 * snapshot already authenticated by PackageNamespaceBuild.auditSnapshot.
 *
 * This is a typed, ordered compiler IR with symbolic generation-fenced loan
 * requirements. No guest-mintable scheduler IDs or executable actor authority
 * are created. Production codegen remains blocked until the owning actor
 * runtime lowers each instruction and releases every loan on all exits.
 */
public final class ActorCriticalStateMirV3 {
    private ActorCriticalStateMirV3() { }

    public enum Instruction {
        REQUIRE_ACTOR_TURN,
        ACQUIRE_WRITE, LOAD_STATE_I64, LOAD_MAIL_I64, CHECKED_ADD_I64,
        STORE_STATE_I64, RELEASE_WRITE,
        ACQUIRE_READ, COPY_STATE_I64, RELEASE_READ,
        MAILBOX_SEND_COPY_I64, CHECK_STOP, RETURN_VOID
    }

    private static final List<Instruction> RECEIVE = List.of(
            Instruction.REQUIRE_ACTOR_TURN,
            Instruction.ACQUIRE_WRITE,
            Instruction.LOAD_STATE_I64,
            Instruction.LOAD_MAIL_I64,
            Instruction.CHECKED_ADD_I64,
            Instruction.STORE_STATE_I64,
            Instruction.RELEASE_WRITE,
            Instruction.ACQUIRE_READ,
            Instruction.COPY_STATE_I64,
            Instruction.RELEASE_READ,
            Instruction.MAILBOX_SEND_COPY_I64,
            Instruction.CHECK_STOP,
            Instruction.RETURN_VOID);

    public record Plan(
            String packageIdentity,
            String sourceUnit,
            String sourceSha256,
            String namespace,
            String actor,
            String method,
            String stateField,
            String mailBinding,
            String detachedBinding,
            long stopValue,
            String scalarType,
            List<Instruction> instructions,
            List<StateRegionLoanReceipt.Requirement> loans) {
        public Plan {
            for (var value : List.of(packageIdentity, sourceUnit, sourceSha256,
                    namespace, actor, method, stateField, mailBinding,
                    detachedBinding, scalarType)) {
                if (value == null || value.isBlank())
                    throw new IllegalArgumentException("ACTOR_STATE_MIR_V3: empty source identity");
            }
            if (sourceSha256.length() != 64 || !sourceSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("ACTOR_STATE_MIR_V3: source digest invalid");
            if (!scalarType.equals("int") || !method.equals("receive")
                    || mailBinding.equals(detachedBinding)
                    || detachedBinding.equals(stateField)
                    || mailBinding.equals(stateField))
                throw new IllegalArgumentException("ACTOR_STATE_MIR_V3: only typed scalar receive");
            instructions = List.copyOf(Objects.requireNonNull(instructions));
            if (!instructions.equals(RECEIVE))
                throw new IllegalArgumentException(
                        "ACTOR_STATE_MIR_V3: invalid loan, Copy or mailbox instruction sequence");
            loans = List.copyOf(Objects.requireNonNull(loans));
            if (loans.size() != 4)
                throw new IllegalArgumentException("ACTOR_STATE_MIR_V3: require 2 scoped loans");
            if (!loans.get(0).authorizes_release(loans.get(1))
                    || !loans.get(2).authorizes_release(loans.get(3))
                    || loans.get(0).kind() != StateRegionLoanReceipt.Kind.WRITE
                    || loans.get(2).kind() != StateRegionLoanReceipt.Kind.READ
                    || loans.get(0).loan_id() == loans.get(2).loan_id()
                    || !loans.get(0).region_binding().equals(loans.get(2).region_binding())
                    || !loans.get(0).place().equals(loans.get(2).place())
                    || !loans.get(0).place().equals("self." + stateField)) {
                throw new IllegalArgumentException(
                        "ACTOR_STATE_MIR_V3: unauthenticated or unbalanced reader/writer loans");
            }
        }
    }

    /** Package-private: caller must hold one verified manifest snapshot. */
    static Plan lower(PackageNamespaceBuild.ActorAudit audit, String packageIdentity,
                      Map<String, Ast.Program> parsed) {
        Objects.requireNonNull(audit, "manifest-bound audit");
        if (packageIdentity == null || packageIdentity.isBlank()
                || audit.manifestSha256() == null
                || !audit.manifestSha256().matches("[0-9a-f]{64}"))
            throw reject("missing manifest provenance");
        var operations = audit.criticalOperations();
        if (audit.contractWitness().actors().size() != 1 || operations.size() != 2)
            throw reject("only one sealed actor with write then detached read is admitted");
        var write = operations.get(0);
        var read = operations.get(1);
        if (write.mode() != Ast.CriticalStateMode.WRITE
                || read.mode() != Ast.CriticalStateMode.READ
                || !write.sourceUnit().equals(read.sourceUnit())
                || !write.namespace().equals(read.namespace())
                || !write.actor().equals(read.actor())
                || !write.method().equals(read.method())
                || !write.field().equals(read.field())
                || !write.scalarType().equals("int")
                || !read.scalarType().equals("int"))
            throw reject("critical-state witness does not prove detached read after write");
        var shape = verifyReceiveShape(parsed, write);
        String sourceDigest = audit.sourceSha256().get(write.sourceUnit());
        if (sourceDigest == null || sourceDigest.isBlank())
            throw reject("missing physical receive source identity");
        var key = audit.contractWitness().actors().keySet().iterator().next();
        if (!key.namespace().equals(write.namespace()) || !key.actor().equals(write.actor()))
            throw reject("actor witness does not own critical-state methods");

        // IDs are symbolic statement-local identities, never runtime IDs.
        // A runtime must mint authenticated actor-turn-specific generations.
        String region = "state:" + packageIdentity + ":" + write.namespace() + ":"
                + write.actor();
        String place = "self." + write.field();
        var writer = new StateRegionLoanReceipt.Requirement(region, true, true,
                1L, place, StateRegionLoanReceipt.Kind.WRITE,
                StateRegionLoanReceipt.Action.BORROW);
        var reader = new StateRegionLoanReceipt.Requirement(region, true, true,
                2L, place, StateRegionLoanReceipt.Kind.READ,
                StateRegionLoanReceipt.Action.BORROW);

        return new Plan(packageIdentity, write.sourceUnit(), sourceDigest,
                write.namespace(), write.actor(), write.method(), write.field(),
                shape.mailBinding(), shape.detachedBinding(), shape.stopValue(),
                write.scalarType(), RECEIVE, List.of(
                    writer, writer.release_requirement(),
                    reader, reader.release_requirement()));
    }

    private record ReceiveShape(
            String mailBinding, String detachedBinding, long stopValue) { }

    /**
     * Verify the complete, ordered typed method body. Preflight proves
     * individual writes/reads, but extra guest effects between them would
     * make a fabricated linear MIR unsafe. This closed v3 lowering therefore
     * allows exactly five source-level statements and no hidden branches.
     */
    private static ReceiveShape verifyReceiveShape(
            Map<String, Ast.Program> parsed,
            ActorCriticalStatePreflight.CriticalOperation write) {
        if (parsed == null) throw reject("missing immutable source snapshot");
        Ast.Program program = parsed.get(write.sourceUnit());
        if (program == null || !write.namespace().equals(program.namespace()))
            throw reject("physical receive source missing or in another namespace");

        Ast.ExternalActorMethodDecl method = null;
        for (var module : program.modules()) {
            if (!module.name().equals(dev.oreslang.parser.Parser.ROOT_MODULE)) continue;
            for (var declaration : module.declarations()) {
                if (declaration instanceof Ast.ExternalActorMethodDecl candidate
                        && candidate.actorName().equals(write.actor())
                        && candidate.name().equals(write.method())) {
                    if (method != null)
                        throw reject("ambiguous external receive source declarations");
                    method = candidate;
                }
            }
        }
        if (method == null || method.parameters().size() != 1
                || !method.name().equals("receive")
                || !method.returnType().name().equals("void")
                || !method.returnType().arguments().isEmpty()
                || method.body().size() != 5)
            throw reject("receive method requires exact five-statement typed body");

        String mail = method.parameters().getFirst().name();
        var stmts = method.body();
        if (!(stmts.get(0) instanceof Ast.ActorStateAccessStmt writer)
                || writer.mode() != Ast.CriticalStateMode.WRITE
                || !(stmts.get(1) instanceof Ast.BindingStmt copy)
                || copy.kind() != Ast.BindingKind.VAL
                || copy.declaredType() == null
                || !copy.declaredType().name().equals("int")
                || !copy.declaredType().arguments().isEmpty()
                || !(copy.initializer() instanceof Ast.ActorStateReadExpr read)
                || !read.fieldName().equals(write.field())
                || !writer.viewName().equals(read.viewName())
                || !(stmts.get(2) instanceof Ast.ExprStmt send)
                || !(send.expression() instanceof Ast.CallExpr call)
                || call.typeArgumentsPresent()
                || call.arguments().size() != 1
                || !(call.arguments().getFirst() instanceof Ast.NameExpr value)
                || !value.name().equals(copy.name())
                || !(call.callee() instanceof Ast.MemberExpr receiver)
                || !receiver.member().equals("send")
                || !(receiver.receiver() instanceof Ast.NameExpr self)
                || !self.name().equals("self"))
            throw reject("write, detached Copy and mailbox send must be in exact order");

        if (!(stmts.get(3) instanceof Ast.IfStmt stop)
                || stop.branches().size() != 1 || !stop.elseBody().isEmpty()
                || stop.branches().getFirst().body().size() != 1)
            throw reject("STOP must have one exact conditional shutdown branch");
        var condition = stop.branches().getFirst().condition();
        if (!(condition instanceof Ast.BinaryExpr equals)
                || !equals.operator().equals("==")
                || !(equals.left() instanceof Ast.MemberExpr mailValue)
                || !mailValue.member().equals("value")
                || !(mailValue.receiver() instanceof Ast.NameExpr owner)
                || !owner.name().equals(mail)
                || !(equals.right() instanceof Ast.LiteralExpr stopLiteral)
                || !(stopLiteral.value() instanceof Long stopValue))
            throw reject("STOP must compare authenticated ActorMail<int>.value");
        var stopStmt = stop.branches().getFirst().body().getFirst();
        if (!(stopStmt instanceof Ast.ExprStmt expr)
                || !(expr.expression() instanceof Ast.CallExpr end)
                || end.typeArgumentsPresent() || !end.arguments().isEmpty()
                || !(end.callee() instanceof Ast.MemberExpr callee)
                || !callee.member().equals("end")
                || !(callee.receiver() instanceof Ast.NameExpr actor)
                || !actor.name().equals("self")
                || !(stmts.get(4) instanceof Ast.ReturnStmt terminal)
                || terminal.value() != null)
            throw reject("STOP must call self.end(), followed by void return");

        return new ReceiveShape(mail, copy.name(), stopValue);
    }

    private static IllegalArgumentException reject(String reason) {
        return new IllegalArgumentException("ACTOR_STATE_MIR_V3_UNSUPPORTED: " + reason);
    }
}
