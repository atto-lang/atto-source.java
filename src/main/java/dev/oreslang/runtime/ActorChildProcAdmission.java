package dev.oreslang.runtime;

import java.io.IOException;
import java.util.Objects;

/**
 * Actor-local permission bridge for ChildProcManager process admission.
 *
 * <p>Authorization happens on the actor's execution turn, not in a worker
 * where thread-local actor identity and its attenuated capabilities may be
 * absent. The returned immutable launch plan is host-only, not a capability
 * for guest code or an OS sandbox.</p>
 */
final class ActorChildProcAdmission {
    private ActorChildProcAdmission() { }

    static ChildProcLaunchAdmission.Plan admit(
            OresContext context, ChildProcLaunchAdmission.Spec spec) throws IOException {
        Objects.requireNonNull(context, "context");
        if (!ActorRuntime.inActorExecution()
                || ActorRuntime.currentActorId().isEmpty()
                || ActorRuntime.currentActorRuntime() == null
                || ActorRuntime.currentActorRuntime() != context.actors()) {
            throw new SecurityException(
                    "ChildProcManager launch admission requires the owning actor execution context");
        }

        IsolatePolicy actorPolicy = ActorRuntime.currentActorPolicy();
        if (actorPolicy == null || actorPolicy.adversarial()
                || ActorRuntime.currentActorKind() == ActorRuntime.ActorKind.UNTRUSTED) {
            throw new SecurityException(
                    "untrusted/adversarial actors cannot launch native subprocesses without OS sandboxing");
        }

        return ChildProcLaunchAdmission.admit(spec, (permission, resource) -> {
            RuntimePermissions.Permission scoped = switch (permission) {
                case RUN -> RuntimePermissions.Permission.RUN;
                case READ -> RuntimePermissions.Permission.READ;
                case ENV -> RuntimePermissions.Permission.ENV;
            };
            // OresContext.requirePermission checks both the context and the
            // current actor's effective coarse capabilities, then scoped
            // allows and denies, at the same synchronously executing turn.
            context.requirePermission(scoped, resource, "ChildProcManager.spawn_process_async");
        });
    }
}
