package atto.compiler.adt;

import java.util.Objects;

/**
 * HOST-ONLY runtime contract for checked int.parse -> Result<int,Error>.
 * Not a source .atto parser, guest code generator or Native Image guest proof.
 * One parser belongs to one trusted TenantVM; no error object may cross tenants.
 */
public final class AttoIntParseRuntime {
    public static final int MAX_PARSE_BYTES = 1024;
    public static final String INVALID_INTEGER = "invalid integer";

    public sealed interface Result permits Ok, Err {}
    public record Ok(long value) implements Result {}
    public record Err(Error error) implements Result {
        public Err { Objects.requireNonNull(error); }
    }

    /** Immutable, nominal Error with reference identity; not an Exception. */
    public static final class Error {
        private final long tenant_id;
        private final long identity;
        private final String message;
        private Error(long tenant_id, long identity, String message) {
            this.tenant_id = tenant_id;
            this.identity = identity;
            this.message = message;
        }
        public long tenant_id() { return tenant_id; }
        public long identity() { return identity; }
        public String message() { return message; }
    }

    /** Trusted-host scope; not a remotely transferable actor capability. */
    public static final class TenantParser {
        private final long tenant_id;
        private long next_error_id;
        public TenantParser(long tenant_id) {
            if (tenant_id <= 0L) throw new IllegalArgumentException("E_TENANT_ID");
            this.tenant_id = tenant_id;
        }
        public long tenant_id() { return tenant_id; }
        public boolean can_observe(Error error) {
            return error != null && error.tenant_id == tenant_id && error.identity > 0;
        }
        private Err invalid() {
            if (next_error_id == Long.MAX_VALUE)
                throw new IllegalStateException("E_ERROR_ID_EXHAUSTED");
            return new Err(new Error(tenant_id, ++next_error_id, INVALID_INTEGER));
        }

        /**
         * Exactly one signed ASCII decimal, optional leading sign, 1..1024
         * UTF-8 bytes. Non-ASCII chars are rejected; ASCII char count == bytes.
         * No Long.parseLong, host exceptions for invalid input or hidden trim.
         * Negative accumulation safely accepts Long.MIN_VALUE.
         */
        public Result parse(String text) {
            if (text == null || text.isEmpty() || text.length() > MAX_PARSE_BYTES)
                return invalid();
            int index = 0;
            boolean negative = false;
            char first = text.charAt(0);
            if (first == '-' || first == '+') {
                negative = first == '-';
                index = 1;
            }
            if (index == text.length()) return invalid();
            long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
            long cutoff = limit / 10L;
            long result = 0L;
            for (; index < text.length(); ++index) {
                char c = text.charAt(index);
                if (c < '0' || c > '9') return invalid();
                int digit = c - '0';
                if (result < cutoff) return invalid();
                result *= 10L;
                if (result < limit + digit) return invalid();
                result -= digit;
            }
            return new Ok(negative ? result : -result);
        }
    }
    private AttoIntParseRuntime() {}
}
