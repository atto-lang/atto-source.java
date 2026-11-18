// Test the real JDK foreign-memory host adapter, not compiled .atto guest.
public final class ActorRef32ArenaTest {
    private static int checks;
    private static void check(boolean yes, String what) {
        checks++;
        if (!yes) throw new AssertionError(what);
    }
    private static void reject(Runnable action, String what) {
        checks++;
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException |
               IndexOutOfBoundsException expected) { return; }
        throw new AssertionError(what);
    }
    private static void stop_twice(ActorRef32Arena value) {
        value.close();
        value.close();
    }
    public static void main(String[] args) {
        var a = new ActorRef32Arena.Owner(7, 9, 1);
        var b = new ActorRef32Arena.Owner(7, 10, 1);
        var restart = new ActorRef32Arena.Owner(7, 9, 2);
        try (var first = new ActorRef32Arena(a, 2);
             var second = new ActorRef32Arena(b, 2)) {
            check(first.capacity() == 2, "capacity");
            int pa = first.allocate(a, 101);
            int pb = second.allocate(b, 101);
            check(pa != 0 && pa == pb, "colliding numeric handles across actors");
            first.write_i64(pa, a, a, 101, 0, 10);
            first.write_i64(pa, a, a, 101, 8, 20);
            first.write_i64(pa, a, a, 101, 16, 3);
            check(first.read_i64(pa, a, a, 101, 0) == 10, "point x");
            check(first.read_i64(pa, a, a, 101, 8) == 20, "point y");
            check(first.read_i64(pa, a, a, 101, 16) == 3, "velocity");
            reject(() -> first.read_i64(pa, a, a, 101, 32), "OOB accepted");
            reject(() -> first.write_i64(pa, a, a, 101, 1, 9), "misaligned field");
            reject(() -> first.read_i64(pa, a, a, 999, 0), "type substitution");
            reject(() -> first.read_i64(pa, a, b, 101, 0), "foreign caller");
            reject(() -> first.read_i64(pa, a, restart, 101, 0), "restart context");
            reject(() -> first.read_i64(pb, b, a, 101, 0), "wrong-origin handle");
            reject(() -> second.read_i64(pa, a, b, 101, 0), "sibling numeric collision");
            reject(() -> first.release(pa, b, a, 101), "foreign release");
            reject(() -> first.release(pa, a, a, 999), "wrong type release");
            first.release(pa, a, a, 101);
            reject(() -> first.release(pa, a, a, 101), "double free accepted");
            int next = first.allocate(a, 101);
            check(next != 0 && next != pa, "generation not advanced");
            reject(() -> first.read_i64(pa, a, a, 101, 0), "stale handle ABA");
            check(first.read_i64(next, a, a, 101, 0) == 0, "released bytes not scrubbed");
            reject(() -> first.allocate(a, 0), "type zero accepted");
            reject(() -> first.allocate(b, 101), "foreign allocation accepted");
            stop_twice(first);
            check(first.is_closed(), "actor STOP");
            reject(() -> first.read_i64(next, a, a, 101, 0), "post-STOP read accepted");
            reject(() -> first.allocate(a, 101), "post-STOP allocate");
            check(second.read_i64(pb, b, b, 101, 0) == 0, "sibling alive after STOP");
        }
        try (var wraps = new ActorRef32Arena(a, 1)) {
            int prior = 0;
            for (int gen = 1; gen <= ActorRef32Arena.MAX_GENERATION; gen++) {
                int token = wraps.allocate(a, 15);
                check(token != 0 && (token >>> ActorRef32Arena.SLOT_BITS) == gen,
                      "generation sequence");
                if (prior != 0) {
                    final int stale = prior;
                    reject(() -> wraps.read_i64(stale, a, a, 15, 0),
                           "stale generation unexpectedly valid");
                }
                wraps.release(token, a, a, 15);
                prior = token;
            }
            check(wraps.allocate(a, 15) == 0, "generation wrapped");
            final int last = prior;
            reject(() -> wraps.read_i64(last, a, a, 15, 0), "retired slot revived");
        }
        reject(() -> new ActorRef32Arena(a, 0), "zero slot count");
        System.out.println("ATTO_REF32_JAVA_HOST_PASS checks=" + checks +
                           " note=not_compiled_atto_guest");
    }
}
