package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.PersistentScalarMirV1;
import dev.oreslang.parser.Parser;

import java.util.List;
import java.util.Objects;

/**
 * Trusted compiler / ActorRuntime bridge for exactly the verified persistent
 * i64 actor MIR subset. Uses ActorRuntime's real PRIVATE mailbox, actor-affine
 * state creation, scheduler and teardown; there is no parallel actor scheduler.
 *
 * Guest code cannot select the DurableActorStore, the Address or CodeImage.
 * A supervisor must provision the address before spawning.
 */
public final class PersistentScalarCodeExecutor implements ActorRuntime.ActorCodeExecutor {
    private final SharedCodeImageStore.CodeImage image;
    private final PersistentScalarMirV1.Plan plan;
    private final DurableActorStore store;
    private final DurableActorStore.Address address;

    private PersistentScalarCodeExecutor(
            SharedCodeImageStore.CodeImage image,
            PersistentScalarMirV1.Plan plan,
            DurableActorStore store,
            DurableActorStore.Address address) {
        this.image = Objects.requireNonNull(image, "image");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.store = Objects.requireNonNull(store, "storage");
        this.address = Objects.requireNonNull(address, "logical actor address");
        if (!plan.equals(PersistentScalarMirV1.lower(image.program())))
            throw new SecurityException("persistent code image does not match verified MIR");
    }

    /** Host compiler entry point, never a guest-visible actor constructor. */
    public static PersistentScalarCodeExecutor compile(
            String source,
            SharedCodeImageStore images,
            DurableActorStore storage,
            DurableActorStore.Address address) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(images, "images");
        Ast.Program parsed = Parser.parse(source);
        PersistentScalarMirV1.Plan plan = PersistentScalarMirV1.lower(parsed);
        String unit = "persistent-scalar:" + plan.codeGeneration();
        SharedCodeImageStore.CodeImage current = images.get(unit);
        if (current == null) {
            current = images.publish(unit, parsed);
        } else if (!plan.equals(PersistentScalarMirV1.lower(current.program()))) {
            throw new SecurityException("reused persistent code unit has different MIR");
        }
        return new PersistentScalarCodeExecutor(current, plan, storage, address);
    }

    public PersistentScalarMirV1.Plan plan() { return plan; }
    public DurableActorStore.Address address() { return address; }
    public String actor_type_name() { return plan.actorName(); }

    @Override
    public SharedCodeImageStore.CodeImage codeImage() { return image; }

    /** Immutable, data-only mailbox commands; not Java callbacks or actor capabilities. */
    public static List<Object> increment_message(String deliveryId, long amount) {
        return List.of("increment", requireId(deliveryId), amount);
    }

    public static List<Object> get_count_message(String requestId) {
        return List.of("get_count", requireId(requestId));
    }

    private static String requireId(String id) {
        if (id == null || id.isBlank() || id.length() > 256)
            throw new IllegalArgumentException("persistent command id length must be 1..256");
        return id;
    }

    private record OwnedState(ActorRuntime.ActorId ownerActorId,
                              PersistentScalarActorV1 actor)
            implements ActorRuntime.ActorOwnedGuestState {
        private OwnedState {
            Objects.requireNonNull(ownerActorId, "owner");
            Objects.requireNonNull(actor, "persistent state");
        }
    }

    private void verifyContext(String actorName, ActorRuntime.ActorContext<Object> context) {
        if (!plan.actorName().equals(actorName))
            throw new SecurityException("unverified persistent actor name");
        if (context.kind() != ActorRuntime.ActorKind.PRIVATE)
            throw new SecurityException("persistent scalar actor must be PRIVATE");
        if (context.codeImage().orElseThrow() != image)
            throw new SecurityException("persistent actor code image identity changed");
        if (context.privateMemory().isEmpty())
            throw new SecurityException("private actor memory domain missing");
    }

    @Override
    public ActorRuntime.ActorOwnedGuestState initializeActor(
            String actorName, ActorRuntime.ActorContext<Object> context) {
        verifyContext(actorName, context);
        // Activation + replay occurs on this actor's own scheduler turn before
        // ActorRef.getReady() is fulfilled.
        return new OwnedState(context.self().id(),
                PersistentScalarActorV1.open(plan, store, address));
    }

    @Override
    public void receiveActor(
            String actorName,
            ActorRuntime.ActorOwnedGuestState actorState,
            ActorRuntime.ActorInboxMail<Object> mail,
            ActorRuntime.ActorContext<Object> context) {
        verifyContext(actorName, context);
        if (!(actorState instanceof OwnedState owned)
                || !owned.ownerActorId().equals(context.self().id()))
            throw new SecurityException("persistent state actor ownership mismatch");
        if (!(mail.value() instanceof List<?> parts))
            throw new IllegalArgumentException("persistent actor expects data-only command list");

        // Results are emitted through ActorRuntime's owner-verified output lane.
        // The increment result exists only AFTER commitTurn returned success.
        if (parts.size() == 3 && "increment".equals(parts.get(0))
                && parts.get(1) instanceof String id && parts.get(2) instanceof Long delta) {
            var done = owned.actor().increment(requireId(id), delta);
            if (done.isPresent()) {
                context.runtime().emitCurrentActorOutput(List.of(id, done.getAsLong()));
            }
        } else if (parts.size() == 2 && "get_count".equals(parts.get(0))
                && parts.get(1) instanceof String id) {
            long count = owned.actor().get_count(); // verifies current durable epoch
            context.runtime().emitCurrentActorOutput(List.of(requireId(id), count));
        } else {
            throw new IllegalArgumentException("unsupported persistent scalar command");
        }
    }

    @Override
    public void tearDownActor(
            String actorName,
            ActorRuntime.ActorOwnedGuestState actorState,
            ActorRuntime.ActorContext<Object> context) {
        verifyContext(actorName, context);
        if (!(actorState instanceof OwnedState owned)
                || !owned.ownerActorId().equals(context.self().id()))
            throw new SecurityException("invalid persistent teardown owner");
        owned.actor().close(); // host-owned storage remains open
    }
}
