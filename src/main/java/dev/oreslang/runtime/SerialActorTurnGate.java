package dev.oreslang.runtime;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime-only, actor-turn-local completion barrier. An Erlang/Scala-style
 * serial actor may suspend its physical carrier, but the logical user-mail
 * turn is occupied until every independent completion barrier has retired.
 *
 * All operations must be called under ActorRuntime.ActorCell.lifecycleLock.
 * This class owns no Java monitor across await and grants no guest authority.
 */
public final class SerialActorTurnGate {
    public enum Completion { STALE, HELD, RELEASED }

    /**
     * One-shot identity for exactly one asynchronous barrier. The constructor
     * is inaccessible to guests; identity deliberately has no value equality.
     * Replaying a completed ticket cannot consume any other pending barrier.
     */
    public static final class Ticket {
        private Ticket() { }
    }

    private Object admittedMail;
    private final Set<Ticket> barriers = new HashSet<>();

    /** Issue a fresh, single-use ticket for one async operation on this mail. */
    public Ticket hold(Object mail) {
        Objects.requireNonNull(mail, "admitted actor mail identity");
        if (!barriers.isEmpty() && admittedMail != mail) {
            throw new IllegalStateException(
                    "cannot hold a second serial actor mail while previous is suspended");
        }
        if (barriers.size() == Integer.MAX_VALUE) {
            throw new IllegalStateException("serial actor completion barrier overflow");
        }
        if (barriers.isEmpty()) admittedMail = mail;
        Ticket ticket = new Ticket();
        barriers.add(ticket);
        return ticket;
    }

    /**
     * Return STALE for duplicate, foreign, or retired tickets. Retiring the
     * final distinct ticket releases the actor-wide logical mail turn.
     */
    public Completion complete(Ticket ticket) {
        if (ticket == null || !barriers.remove(ticket)) return Completion.STALE;
        if (!barriers.isEmpty()) return Completion.HELD;
        admittedMail = null;
        return Completion.RELEASED;
    }

    public boolean occupied() { return !barriers.isEmpty(); }
    public int pendingBarriers() { return barriers.size(); }

    /** Stop/restart invalidates all old tickets, including if mail is reused. */
    public void retire() {
        admittedMail = null;
        barriers.clear();
    }
}
