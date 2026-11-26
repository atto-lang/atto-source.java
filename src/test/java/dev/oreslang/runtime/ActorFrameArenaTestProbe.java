package dev.oreslang.runtime;

/**
 * Test-scope inspection of actor frame accounting. No public runtime API or
 * guest-visible frame capability is introduced.
 */
public final class ActorFrameArenaTestProbe {
    private ActorFrameArenaTestProbe() { }

    public static int currentActorPeakBytes() {
        ActorFrameArena arena = ActorRuntime.currentActorFrameArena();
        if (arena == null) {
            throw new IllegalStateException("no actor-owned frame arena in the current turn");
        }
        return arena.peakBytes();
    }
}
