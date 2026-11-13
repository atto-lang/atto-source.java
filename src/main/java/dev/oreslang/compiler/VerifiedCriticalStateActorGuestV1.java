package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.SharedCodeImageStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Experimental *compiled Atto receive body* for one manifest-bound declared
 * actor with one mutable i64 state field and exactly three typed MIR effects.
 *
 * Source owner: PackageNamespaceBuild, not a hand-written host Counter.
 * Actor state is allocated in ActorRuntime.ActorMemorySlice only after the
 * runtime has entered the target actor's own dispatcher turn. Each mailbox
 * message is checked against the runtime's current envelope; the state handle
 * never crosses DATA or escapes into a generated guest environment.
 *
 * This does NOT compile the Atto main, general actor methods, arbitrary state
 * schemas or nested critical callbacks. TypeChecker/build() continue to reject
 * those forms; the caller must explicitly use this bounded package entrypoint.
 */
final class VerifiedCriticalStateActorGuestV1 implements ActorRuntime.ActorCodeExecutor {
    private final ActorCriticalStateMirV1.Plan plan;
    private final SharedCodeImageStore.CodeImage image;

    private VerifiedCriticalStateActorGuestV1(
            ActorCriticalStateMirV1.Plan plan, SharedCodeImageStore.CodeImage image) {
        this.plan = Objects.requireNonNull(plan);
        this.image = Objects.requireNonNull(image);
    }

    static ActorRuntime.ActorCodeExecutor lower(
            String packageIdentity, Map<String, Ast.Program> sourceUnits,
            PackageNamespaceBuild.ActorAudit audit, SharedCodeImageStore store) {
        Objects.requireNonNull(store, "VM-owned immutable code-image store");
        ActorCriticalStateMirV1.Plan plan =
                ActorCriticalStateMirV1.lower(packageIdentity, sourceUnits, audit);
        // Freeze the *whole physical manifest snapshot*, not just the receiver
        // source: the opaque CodeImage identity is bound to every source hash.
        List<Ast.Decl> all = new ArrayList<>();
        for (var source : new TreeMap<>(sourceUnits).entrySet()) {
            if (!plan.namespace().equals(source.getValue().namespace())
                    || !source.getValue().imports().isEmpty()) {
                throw unsupported("unsupported foreign namespace/import in sealed actor source set");
            }
            for (Ast.ModuleDecl module : source.getValue().modules()) {
                if (!Parser.ROOT_MODULE.equals(module.name()))
                    throw unsupported("named module actor linking is unsupported");
                all.addAll(module.declarations());
            }
        }
        Ast.Program closedImage = new Ast.Program(plan.namespace(), List.of(),
                List.of(new Ast.ModuleDecl(Parser.ROOT_MODULE, List.copyOf(all))));
        SharedCodeImageStore.CodeImage image = store.publish(
                codeImageId(plan), closedImage);
        return new VerifiedCriticalStateActorGuestV1(plan, image);
    }

    private static String codeImageId(ActorCriticalStateMirV1.Plan plan) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (var entry : new TreeMap<>(plan.sourceSha256()).entrySet()) {
                sha256.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                sha256.update((byte) 0);
                sha256.update(entry.getValue().getBytes(StandardCharsets.US_ASCII));
                sha256.update((byte) '\n');
            }
            sha256.update(plan.manifestSha256().getBytes(StandardCharsets.US_ASCII));
            return plan.packageIdentity() + "/" + plan.namespace() + "/"
                    + plan.actor() + "#critical-state-mir-v1:"
                    + HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("required SHA-256 unavailable", impossible);
        }
    }

    @Override
    public SharedCodeImageStore.CodeImage codeImage() { return image; }

    @Override
    public ActorRuntime.ActorOwnedGuestState initializeActor(
            String actorTypeName, ActorRuntime.ActorContext<Object> context) {
        validateActorType(actorTypeName);
        Objects.requireNonNull(context, "actor initialization context");
        if (context.kind() != ActorRuntime.ActorKind.SHARED
                || ActorRuntime.currentActorRuntime() != context.runtime()
                || !ActorRuntime.currentActorId().orElseThrow().equals(context.self().id())
                || context.codeImage().orElseThrow() != image
                || context.currentMail().isPresent()) {
            throw new SecurityException("declared actor state requires its own declared SHARED startup turn");
        }
        ActorRuntime.ActorMemorySlice slice = context.privateMemory().orElseThrow(
                () -> new SecurityException("declared actor has no owner-backed state slice"));
        if (!slice.owner().equals(context.self().id()))
            throw new SecurityException("actor state owner does not match current runtime turn");
        ActorRuntime.PrivateMemoryBlock block = slice.allocatePrivateBytes(Long.BYTES);
        try {
            block.writeLong(0, plan.initialValue());
            return new State(context, block, image);
        } catch (RuntimeException | Error failure) {
            block.close();
            throw failure;
        }
    }

    @Override
    public void receiveActor(
            String actorTypeName, ActorRuntime.ActorOwnedGuestState state,
            ActorRuntime.ActorInboxMail<Object> mail,
            ActorRuntime.ActorContext<Object> context) {
        validateActorType(actorTypeName);
        if (!(state instanceof State scalar))
            throw new SecurityException("runtime source state has wrong compiler owner");
        scalar.validate(context);
        Objects.requireNonNull(mail, "runtime-minted actor mailbox envelope");
        if (context.currentMail().orElse(null) != mail
                || !mail.recipient().equals(scalar.ownerActorId()))
            throw new SecurityException("mailbox envelope is not the current actor's admitted turn");
        if (!(mail.value() instanceof Long value))
            throw new IllegalArgumentException("declared actor receive requires owned i64 mail");

        long copied = 0L;
        for (ActorCriticalStateMirV1.Operation op : plan.operations()) {
            switch (op.opcode()) {
                case CHECKED_ADD_MAIL_I64 -> scalar.checkedAdd(context, value);
                case COPY_I64_TO_ACTOR_OUTPUT -> {
                    // The state loan closes before channel transport; there is
                    // no address/view to transfer, only a copied immutable i64.
                    copied = scalar.readCopy(context);
                    context.runtime().emitCurrentActorOutput(copied);
                }
                case STOP_ON_MAIL_I64 -> {
                    if (value.longValue() == plan.stopOnMailValue())
                        context.runtime().endCurrentActor();
                }
            }
        }
    }

    @Override
    public void tearDownActor(
            String actorTypeName, ActorRuntime.ActorOwnedGuestState state,
            ActorRuntime.ActorContext<Object> context) {
        validateActorType(actorTypeName);
        if (!(state instanceof State scalar))
            throw new SecurityException("runtime teardown state has wrong compiler owner");
        scalar.validate(context);
        scalar.close();
    }

    private void validateActorType(String name) {
        if (!plan.actor().equals(name))
            throw new SecurityException("source actor selector did not match manifest-bound nominal type");
    }

    private static IllegalArgumentException unsupported(String message) {
        return new IllegalArgumentException(
                "ACTOR_CRITICAL_STATE_GUEST_V1_UNSUPPORTED: " + message);
    }

    /**
     * Actor-incarnation-confined persistent state. An opaque direct memory
     * block, never a guest-visible Java field or pointer. The scheduler is
     * already single-writer per actor; the per-scope nonparking CAS additionally
     * rejects nested/overlapping read-write loans on one physical state.
     */
    private static final class State implements ActorRuntime.ActorOwnedGuestState {
        private final ActorRuntime runtime;
        private final ActorRuntime.ActorId owner;
        private final long generation;
        private final ActorRuntime.PrivateMemoryBlock block;
        private final SharedCodeImageStore.CodeImage codeImage;
        private final AtomicInteger activeLoan = new AtomicInteger();
        private boolean closed;

        private State(ActorRuntime.ActorContext<Object> context,
                      ActorRuntime.PrivateMemoryBlock block,
                      SharedCodeImageStore.CodeImage codeImage) {
            runtime = context.runtime();
            this.codeImage = codeImage;
            owner = context.self().id();
            generation = context.self().generation();
            this.block = block;
        }

        @Override public ActorRuntime.ActorId ownerActorId() { return owner; }

        private void validate(ActorRuntime.ActorContext<Object> context) {
            if (context == null || closed || block.closed()
                    || context.runtime() != runtime
                    || ActorRuntime.currentActorRuntime() != runtime
                    || !owner.equals(ActorRuntime.currentActorId().orElse(null))
                    || context.codeImage().orElse(null) != codeImage
                    || context.kind() != ActorRuntime.ActorKind.SHARED
                    || context.self().generation() != generation
                    || !owner.equals(context.self().id())
                    || !owner.equals(block.owner())) {
                throw new SecurityException(
                        "critical state requires a live owner and incarnation-matched runtime turn");
            }
        }

        private long checkedAdd(ActorRuntime.ActorContext<Object> context, long delta) {
            enter(context);
            try {
                long before = block.readLong(0);
                long next = Math.addExact(before, delta);
                block.writeLong(0, next);
                return next;
            } finally {
                activeLoan.set(0);
            }
        }

        private long readCopy(ActorRuntime.ActorContext<Object> context) {
            enter(context);
            try {
                return block.readLong(0);
            } finally {
                activeLoan.set(0);
            }
        }

        private void enter(ActorRuntime.ActorContext<Object> context) {
            validate(context);
            if (!activeLoan.compareAndSet(0, 1))
                throw new SecurityException("nested or overlapping critical state loan");
            try {
                validate(context);
            } catch (RuntimeException | Error failure) {
                activeLoan.set(0);
                throw failure;
            }
        }

        private void close() {
            if (!activeLoan.compareAndSet(0, 1))
                throw new SecurityException("cannot retire actor with a live critical loan");
            try {
                block.close();
                closed = true;
            } finally {
                activeLoan.set(0);
            }
        }
    }
}
