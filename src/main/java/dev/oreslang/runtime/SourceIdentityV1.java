package dev.oreslang.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Independent Java/Truffle cold-path source ID admission, not an instrumented guest AST. */
public final class SourceIdentityV1 {
    private SourceIdentityV1() { }

    public enum Kind { ROUTINE, SITE }

    /** Fixed-width identity; two long lanes avoid storing UUID text on hot paths. */
    public record Id(Kind kind, long high, long low) {
        public Id { Objects.requireNonNull(kind, "kind"); }
    }

    public record Site(Id routineId, Id siteId, int descriptorIndex) {
        public Site {
            Objects.requireNonNull(routineId, "routineId");
            Objects.requireNonNull(siteId, "siteId");
            if (routineId.kind() != Kind.ROUTINE || siteId.kind() != Kind.SITE
                    || descriptorIndex < 0) {
                throw new IllegalArgumentException("invalid static site descriptor kind/index");
            }
        }
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return -1;
    }

    /** Strict static ID syntax; W3C runtime trace/span IDs are NOT source IDs. */
    public static Id parse(String text, Kind kind) {
        Objects.requireNonNull(kind, "kind");
        String prefix = kind == Kind.ROUTINE ? "rid_" : "sid_";
        if (text == null || text.length() != 36 || !text.startsWith(prefix)) {
            throw new IllegalArgumentException("invalid source ID prefix/width");
        }
        long high = 0L;
        long low = 0L;
        for (int i = 0; i < 32; i++) {
            int digit = hex(text.charAt(i + 4));
            if (digit < 0) throw new IllegalArgumentException("source ID must be lowercase hex");
            if (i < 16) high = (high << 4) | digit;
            else low = (low << 4) | digit;
        }
        if (high == 0L && low == 0L) {
            throw new IllegalArgumentException("source ID must be nonzero");
        }
        return new Id(kind, high, low);
    }

    /** Cold-path bounded validation before publishing an immutable descriptor table. */
    public static List<Site> freezeValidated(List<Site> descriptors) {
        Objects.requireNonNull(descriptors, "descriptors");
        if (descriptors.size() > 4096) {
            throw new IllegalArgumentException("source descriptor budget exceeded");
        }
        var ids = new HashSet<Id>();
        var indices = new HashSet<Integer>();
        for (Site site : descriptors) {
            if (site == null || !ids.add(site.siteId()) || !indices.add(site.descriptorIndex())) {
                throw new IllegalArgumentException("duplicate/invalid source descriptor");
            }
        }
        return List.copyOf(descriptors);
    }
}
