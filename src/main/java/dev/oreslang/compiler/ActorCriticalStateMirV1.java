package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorContractWitness;
import dev.oreslang.types.ActorReceiverBinder;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Manifest-bound, source-typed critical-state MIR for ONE scalar actor ingress.
 *
 * This is a typed compiler IR, NOT a runnable ActorCodeExecutor, actor context
 * lease or permission to run guest code. A subsequent lowering must compile
 * these exact operations with the actor runtime's generation-fenced physical
 * turns and non-escaping critical loans. In particular, do not reinterpret
 * the Plan as a host-run simulation of the Atto program.
 *
 * Only PackageNamespaceBuild may call lower(), after parsing all source units
 * from one canonical manifest snapshot and auditing their closed source set.
 */
public final class ActorCriticalStateMirV1 {
    private ActorCriticalStateMirV1() { }

    /** The guest source's checked arithmetic operation (never a host callback). */
    public enum Opcode { CHECKED_ADD_MAIL_I64, COPY_I64_TO_ACTOR_OUTPUT, STOP_ON_MAIL_I64 }

    public record Operation(Opcode opcode, String field, String sourceUnit) {
        public Operation {
            Objects.requireNonNull(opcode, "critical MIR opcode");
            if (field == null || field.isBlank() || sourceUnit == null || sourceUnit.isBlank())
                throw new IllegalArgumentException("critical MIR operation lacks physical source identity");
        }
    }

    /**
     * Data-only typed IR. Values and operations are validated against one
     * canonical manifest/source snapshot; no mutable state pointer is retained.
     */
    public static final class Plan {
        private final String packageIdentity, namespace, actor;
        private final String stateSourceUnit, ingressSourceUnit, field;
        private final String mailParameter, manifestSha256;
        private final long initialValue, stopOnMailValue;
        private final List<Operation> operations;
        private final Map<String, String> sourceSha256;

        // No caller/guest may mint a data object that could later be confused
        // for compiler provenance. Only this verified lowerer constructs one.
        private Plan(String packageIdentity, String namespace, String actor,
                     String stateSourceUnit, String ingressSourceUnit,
                     String field, long initialValue, String mailParameter,
                     long stopOnMailValue, List<Operation> operations,
                     String manifestSha256, Map<String, String> sourceSha256) {
            for (String identity : List.of(packageIdentity, namespace, actor,
                    stateSourceUnit, ingressSourceUnit, field, mailParameter, manifestSha256)) {
                if (identity == null || identity.isBlank())
                    throw new IllegalArgumentException("critical MIR requires complete manifest provenance");
            }
            this.packageIdentity = packageIdentity;
            this.namespace = namespace;
            this.actor = actor;
            this.stateSourceUnit = stateSourceUnit;
            this.ingressSourceUnit = ingressSourceUnit;
            this.field = field;
            this.initialValue = initialValue;
            this.mailParameter = mailParameter;
            this.stopOnMailValue = stopOnMailValue;
            this.operations = List.copyOf(operations);
            this.manifestSha256 = manifestSha256;
            this.sourceSha256 = Map.copyOf(sourceSha256);
            if (operations.size() != 3
                    || operations.get(0).opcode() != Opcode.CHECKED_ADD_MAIL_I64
                    || operations.get(1).opcode() != Opcode.COPY_I64_TO_ACTOR_OUTPUT
                    || operations.get(2).opcode() != Opcode.STOP_ON_MAIL_I64)
                throw new IllegalArgumentException("critical MIR instruction sequence is not validated");
            if (!sourceSha256.containsKey(stateSourceUnit)
                    || !sourceSha256.containsKey(ingressSourceUnit))
                throw new IllegalArgumentException("critical MIR source digest is missing");
            if (!operations.get(0).field().equals(field)
                    || !operations.get(1).field().equals(field)
                    || !operations.get(2).field().equals(field))
                throw new IllegalArgumentException("critical MIR uses inconsistent state fields");
        }

        public String packageIdentity() { return packageIdentity; }
        public String namespace() { return namespace; }
        public String actor() { return actor; }
        public String stateSourceUnit() { return stateSourceUnit; }
        public String ingressSourceUnit() { return ingressSourceUnit; }
        public String field() { return field; }
        public long initialValue() { return initialValue; }
        public String mailParameter() { return mailParameter; }
        public long stopOnMailValue() { return stopOnMailValue; }
        public List<Operation> operations() { return operations; }
        public String manifestSha256() { return manifestSha256; }
        public Map<String, String> sourceSha256() { return sourceSha256; }
    }

    static Plan lower(String packageIdentity, Map<String, Ast.Program> units,
                      PackageNamespaceBuild.ActorAudit audit) {
        Objects.requireNonNull(units, "canonical package source units");
        Objects.requireNonNull(audit, "manifest-derived actor audit");
        ActorContractWitness.Result witness = audit.contractWitness();
        if (!packageIdentity.equals(witness.packageIdentity())
                || witness.actors().size() != 1)
            throw deny("one manifest-owned declared actor is required");
        var actorKey = witness.actors().keySet().iterator().next();
        var actorProof = witness.actors().get(actorKey);
        if (!packageIdentity.equals(actorKey.packageIdentity()) || actorProof.stateUnit() == null)
            throw deny("one attached state source is required");
        Ast.Program stateUnit = units.get(actorProof.stateUnit());
        if (stateUnit == null || !actorKey.namespace().equals(stateUnit.namespace()))
            throw deny("state source namespace or provenance mismatch");
        Ast.ActorStateAttachmentDecl state = null;
        for (var module : stateUnit.modules()) {
            if (!Parser.ROOT_MODULE.equals(module.name())) continue;
            for (var decl : module.declarations()) {
                if (decl instanceof Ast.ActorStateAttachmentDecl attached
                        && actorKey.actor().equals(attached.actorName())) {
                    if (state != null) throw deny("ambiguous attached state schema");
                    state = attached;
                }
            }
        }
        if (state == null || state.fields().size() != 1)
            throw deny("scalar MIR requires exactly one attached state field");
        Ast.AttachedActorStateField field = state.fields().getFirst();
        if (field.bindingKind() != Ast.BindingKind.LET
                || !field.type().name().equals("int")
                || !(field.initializer() instanceof Ast.LiteralExpr initial)
                || !(initial.value() instanceof Long))
            throw deny("scalar MIR requires one mutable int field and checked int default");

        ActorReceiverBinder.SourceBinding ingress = null;
        for (var binding : witness.boundMethods().entrySet()) {
            var key = binding.getKey();
            if (key.packageIdentity().equals(packageIdentity)
                    && key.namespace().equals(actorKey.namespace())
                    && key.actor().equals(actorKey.actor())
                    && key.name().equals("receive")) {
                if (ingress != null) throw deny("duplicate receive ingress");
                ingress = binding.getValue();
            }
        }
        if (ingress == null) throw deny("missing manifest-linked receive ingress");
        Ast.ExternalActorMethodDecl receive = ingress.nominalBinding().method();
        if (!receive.runtimeOnly() || receive.visibility() != Ast.Visibility.PRIVATE
                || receive.parameters().size() != 1)
            throw deny("receive requires private runtime-only owned ActorMail<int> -> void");
        Ast.Param ownedMail = receive.parameters().getFirst();
        Ast.TypeRef mailType = ownedMail.type();
        if (ownedMail.mutable() || ownedMail.structural()
                || !mailType.name().equals("ActorMail")
                || mailType.isBorrow() || mailType.inferArguments()
                || mailType.arguments().size() != 1
                || !mailType.arguments().getFirst().name().equals("int")
                || mailType.arguments().getFirst().isBorrow()
                || mailType.arguments().getFirst().inferArguments()
                || !mailType.arguments().getFirst().arguments().isEmpty()
                || !receive.returnType().name().equals("void")
                || receive.returnType().isBorrow()
                || receive.returnType().inferArguments()
                || !receive.returnType().arguments().isEmpty())
            throw deny("receive requires one immutable owned ActorMail<int> -> void");

        String mail = receive.parameters().getFirst().name();
        if (receive.body().size() != 4)
            throw deny("scalar ingress requires write, read, stop-if, return only");
        if (!(receive.body().get(0) instanceof Ast.ActorStateAccessStmt writer)
                || writer.mode() != Ast.CriticalStateMode.WRITE
                || !(receive.body().get(1) instanceof Ast.ActorStateAccessStmt reader)
                || reader.mode() != Ast.CriticalStateMode.READ
                || !(receive.body().get(3) instanceof Ast.ReturnStmt last)
                || last.value() != null)
            throw deny("scalar ingress must contain ordered nonescaping loans and void return");
        if (!(receive.body().get(2) instanceof Ast.IfStmt stop)
                || stop.branches().size() != 1 || !stop.elseBody().isEmpty())
            throw deny("scalar ingress needs one explicit stop-on-message branch");
        var branch = stop.branches().getFirst();
        if (!(branch.condition() instanceof Ast.BinaryExpr eq)
                || !(eq.operator().equals("==") || eq.operator().equals("eq"))
                || !mailValue(eq.left(), mail))
            throw deny("stop condition must compare owned mail.value to an int literal");
        Long stopValue = signedI64Literal(eq.right());
        if (stopValue == null)
            throw deny("stop condition requires one checked signed int literal");
        if (branch.body().size() != 1
                || !(branch.body().getFirst() instanceof Ast.ExprStmt stopStatement)
                || !(stopStatement.expression() instanceof Ast.CallExpr endCall)
                || endCall.typeArgumentsPresent() || !endCall.arguments().isEmpty()
                || !(endCall.callee() instanceof Ast.MemberExpr endMethod)
                || !endMethod.member().equals("end")
                || !(endMethod.receiver() instanceof Ast.NameExpr endOwner)
                || !endOwner.name().equals("self"))
            throw deny("stop branch may only request the current actor's end()");

        // The existing critical-state preflight has already authenticated the
        // lexical fields and exact bodies. Recompute them defensively so a
        // caller-selected AST cannot substitute stale audited descriptors.
        var checked = ActorCriticalStatePreflight.verify(units, witness);
        var expected = List.of(new ActorCriticalStatePreflight.CriticalOperation(
                        ingress.methodUnit(), actorKey.namespace(), actorKey.actor(),
                        "receive", Ast.CriticalStateMode.WRITE, field.name(), "int"),
                new ActorCriticalStatePreflight.CriticalOperation(
                        ingress.methodUnit(), actorKey.namespace(), actorKey.actor(),
                        "receive", Ast.CriticalStateMode.READ, field.name(), "int"));
        if (!checked.equals(audit.criticalOperations()) || !checked.equals(expected))
            throw deny("critical-loan operations do not match the authenticated source snapshot");

        // Re-inspect both operands: the existing preflight also admits an
        // integer literal; this MIR insists on the actual owned mail input.
        if (!writerIncrement(writer, field.name(), mail)
                || !readerCopySend(reader, field.name()))
            throw deny("critical MIR requires checked mail increment and copy-only output");

        return new Plan(packageIdentity, actorKey.namespace(), actorKey.actor(),
                actorProof.stateUnit(), ingress.methodUnit(), field.name(),
                (Long) initial.value(), mail, stopValue,
                List.of(new Operation(Opcode.CHECKED_ADD_MAIL_I64, field.name(), ingress.methodUnit()),
                        new Operation(Opcode.COPY_I64_TO_ACTOR_OUTPUT, field.name(), ingress.methodUnit()),
                        new Operation(Opcode.STOP_ON_MAIL_I64, field.name(), ingress.methodUnit())),
                audit.manifestSha256(), audit.sourceSha256());
    }

    private static Long signedI64Literal(Ast.Expr expr) {
        if (expr instanceof Ast.LiteralExpr literal
                && literal.value() instanceof Long number)
            return number;
        if (expr instanceof Ast.UnaryExpr unary
                && unary.operator().equals("-")
                && unary.operand() instanceof Ast.LiteralExpr literal
                && literal.value() instanceof Long number
                && number != Long.MIN_VALUE)
            return -number;
        return null;
    }

    private static boolean mailValue(Ast.Expr expr, String mail) {
        return expr instanceof Ast.MemberExpr member
                && member.member().equals("value")
                && member.receiver() instanceof Ast.NameExpr owner
                && owner.name().equals(mail);
    }

    private static boolean stateField(Ast.Expr expr, String view, String field) {
        return expr instanceof Ast.MemberExpr member
                && member.member().equals(field)
                && member.receiver() instanceof Ast.NameExpr owner
                && owner.name().equals(view);
    }

    private static boolean writerIncrement(
            Ast.ActorStateAccessStmt writer, String field, String mail) {
        if (writer.body().size() != 1
                || !(writer.body().getFirst() instanceof Ast.ExprStmt stmt)
                || !(stmt.expression() instanceof Ast.AssignExpr assign)
                || !stateField(assign.target(), writer.viewName(), field)
                || !(assign.value() instanceof Ast.BinaryExpr add)
                || !add.operator().equals("+"))
            return false;
        return stateField(add.left(), writer.viewName(), field)
                && mailValue(add.right(), mail);
    }

    private static boolean readerCopySend(Ast.ActorStateAccessStmt reader, String field) {
        if (reader.body().size() != 1
                || !(reader.body().getFirst() instanceof Ast.ExprStmt stmt)
                || !(stmt.expression() instanceof Ast.CallExpr call)
                || call.typeArgumentsPresent() || call.arguments().size() != 1
                || !(call.callee() instanceof Ast.MemberExpr member)
                || !member.member().equals("send")
                || !(member.receiver() instanceof Ast.NameExpr owner)
                || !owner.name().equals("self"))
            return false;
        return stateField(call.arguments().getFirst(), reader.viewName(), field);
    }

    private static IllegalArgumentException deny(String reason) {
        return new IllegalArgumentException(
                "ACTOR_CRITICAL_STATE_MIR_V1_UNSUPPORTED: " + reason);
    }
}
