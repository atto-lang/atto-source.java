package dev.oreslang.runtime;

/** Test-only scalar observation; never exposes arena or turn authority. */
public final class ActorFrameTestProbe {
    private ActorFrameTestProbe() { }
    public static int currentPeakBytes() {
        return ActorRuntime.currentActorFrameArena().peakBytes();
    }
}
