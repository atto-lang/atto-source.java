package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.Map;

/**
 * Host-only allocation bounds and actor-incarnation authority for compressed32.
 * Not an OS/GraalVM isolate or a machine-code sandbox. Guest lowering MUST make
 * checks unavoidable and must never expose native heap bases to guest code.
 */
public final class ActorIsolationAccessV1 {
    public static final long MAX_COMPRESSED32_SPAN = 0xffff_ffffL * 8L;

    public static final class Grant {
        private final Object issuer;
        private final long id;
        private Grant(Object issuer, long id) {
            this.issuer = issuer;
            this.id = id;
        }
    }
    private record Range(long start, long end) { }

    private final long tenant, actor, generation, span;
    private final Object issuer = new Object();
    private final Map<Long, Range> live = new HashMap<>();
    private long nextId = 1;
    private boolean retired;

    public ActorIsolationAccessV1(long tenant, long actor, long generation, long span) {
        if (tenant <= 0 || actor <= 0 || generation <= 0 ||
                span <= 0 || span > MAX_COMPRESSED32_SPAN) {
            throw new IllegalArgumentException("invalid actor incarnation or compressed32 span");
        }
        this.tenant = tenant;
        this.actor = actor;
        this.generation = generation;
        this.span = span;
    }

    public synchronized Grant issueAllocation(long start, long length) {
        if (retired) throw new IllegalStateException("retired actor");
        if (start < 0 || start % 8 != 0 || length <= 0 ||
                start > span || length > span - start ||
                nextId == Long.MAX_VALUE) {
            throw new IllegalArgumentException("invalid actor allocation bounds");
        }
        long end = start + length;
        for (Range r : live.values()) {
            if (start < r.end && r.start < end) {
                throw new IllegalArgumentException("overlapping live actor allocations");
            }
        }
        Grant grant = new Grant(issuer, nextId++);
        live.put(grant.id, new Range(start, end));
        return grant;
    }

    /** Returns an actor-relative offset, never a native pointer. */
    public synchronized long checkedOffset(Grant grant, long storedWord, long bytes) {
        if (retired) throw new IllegalStateException("retired actor");
        if (grant == null || grant.issuer != issuer) {
            throw new SecurityException("foreign actor or tenant grant");
        }
        Range r = live.get(grant.id);
        if (r == null) throw new SecurityException("stale allocation grant");
        if (storedWord <= 0 || storedWord > 0xffff_ffffL || bytes <= 0) {
            throw new IllegalArgumentException("null or invalid compressed32 access");
        }
        long offset = (storedWord - 1) * 8L;
        if (offset < r.start || offset > r.end || bytes > r.end - offset) {
            throw new IndexOutOfBoundsException("access outside actor allocation");
        }
        return offset;
    }

    public synchronized void release(Grant grant) {
        if (retired) throw new IllegalStateException("retired actor");
        if (grant == null || grant.issuer != issuer || live.remove(grant.id) == null) {
            throw new SecurityException("foreign or stale allocation grant");
        }
        // Trusted allocator must scrub bytes before reusing this range.
    }

    public synchronized void retire() {
        retired = true;
        live.clear();
        // Caller must first fence actor turns/FFI pins and scrub the arena.
    }

    public synchronized boolean retired() { return retired; }
}
