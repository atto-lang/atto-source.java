package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Runtime-only, actor-turn-local completion barrier. An Erlang/Scala-style
 * serial actor may suspend its physical carrier, but the logical user-mail
 * turn is occupied until every attached completion barrier has retired.
 *
 * Mutate under ActorRuntime.ActorCell.lifecycleLock. This class deliberately
 * has no Guest/FFI-visible authority and never owns a Java monitor across await.
 */
public final class SerialActorTurnGate {
    public enum Completion { STALE, HELD, RELEASED }

    private Object admittedMail;
    private int barriers;

    /** Join another async operation to the current admitted user mail. */
    public void hold(Object mail) {
        Objects.requireNonNull(mail, "admitted actor mail identity");
        if (barriers != 0 && admittedMail != mail) {
            throw new IllegalStateException(
                    "cannot hold a second serial actor mail while previous is suspended");
        }
        if (barriers == Integer.MAX_VALUE) {
            throw new IllegalStateException("serial actor completion barrier overflow");
        }
        if (barriers == 0) admittedMail = mail;
        barriers++;
    }

    /**
     * Late, duplicate, or post-cancellation completions have no authority to
     * release a new turn. Compare immutable mail object identity (not value).
     */
    public Completion complete(Object mail) {
        if (barriers == 0 || admittedMail != mail) return Completion.STALE;
        if (--barriers != 0) return Completion.HELD;
        admittedMail = null;
        return Completion.RELEASED;
    }

    public boolean occupied() { return barriers != 0; }
    public int pendingBarriers() { return barriers; }

    /** Lifecycle shutdown permanently invalidates old mail completion tokens. */
    public void retire() {
        admittedMail = null;
        barriers = 0;
    }
}
