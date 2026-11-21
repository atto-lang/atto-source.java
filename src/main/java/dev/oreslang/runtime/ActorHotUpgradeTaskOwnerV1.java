package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Actor/code-route binding for the canonical {@link OresTaskScope}.
 *
 * <p>This is no longer a second Task ownership implementation. OresTaskScope
 * owns cancellation, child scopes, physical joins and pre-admission owner
 * reservation. This wrapper contributes only the actor route-epoch capability:
 * root supervisor scope may bind the current route; descendants are pinned to
 * the exact route epoch from which they were minted.</p>
 *
 * <p>Guest source still cannot obtain this wrapper. Compiler lowering must
 * eventually provide the same actor/route/code identity rather than invoking
 * this trusted-host proof surface.</p>
 */
final class ActorHotUpgradeTaskOwnerV1 implements AutoCloseable {
    private final LogicalSourceActorRouterV1 router;
    private final long boundRouteEpoch;
    private final OresTaskScope scope;

    ActorHotUpgradeTaskOwnerV1(LogicalSourceActorRouterV1 router) {
        this(Objects.requireNonNull(router, "router"), 0L,
                OresTaskScope.compilerOwned());
    }

    private ActorHotUpgradeTaskOwnerV1(
            LogicalSourceActorRouterV1 router,
            long boundRouteEpoch,
            OresTaskScope scope) {
        this.router = Objects.requireNonNull(router, "router");
        if (boundRouteEpoch < 0L)
            throw new IllegalArgumentException("invalid Task owner route epoch");
        this.boundRouteEpoch = boundRouteEpoch;
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    ActorHotUpgradeTaskOwnerV1 childScope() {
        requireHost();
        // Root is a stable logical-supervisor authority. Every descendant is
        // bound to the physical code-route epoch current at first descent.
        long epoch = boundRouteEpoch == 0L
                ? router.ref().routeEpoch() : boundRouteEpoch;
        return new ActorHotUpgradeTaskOwnerV1(
                router, epoch, scope.childScope());
    }

    <T> OresFuture<T> start(
            OresScheduler scheduler, OresScheduler.Task<T> frame) {
        requireHost();
        OresTask<T> task = router.startCompilerTaskForUpgrade(
                scope, scheduler, frame, boundRouteEpoch);
        return task.getAwaited();
    }

    boolean physicallyQuiescent() {
        return scope.physicallyQuiescent();
    }

    int retainedChildScopesForTesting() {
        return scope.retainedChildScopesForTesting();
    }

    OresTaskScope canonicalScopeForTesting() {
        return scope;
    }

    @Override
    public void close() {
        requireHost();
        scope.close();
    }

    private static void requireHost() {
        if (ActorRuntime.inActorExecution()
                || ActorRuntime.isActorCarrierThread()
                || ActorRuntime.inActorGroupMailmanExecution()
                || OresScheduler.current() != null
                || OresScheduler.isSchedulerCarrierThread()
                || AsyncRuntime.isAsyncCarrierThread()) {
            // Closing a TaskScope joins physical children and can block.
            // Terminal callbacks on actor/Mailman carriers are not trusted
            // host supervisors even after CURRENT actor state is cleared.
            throw new SecurityException(
                    "actor generation Task scope requires trusted host control");
        }
    }
}
