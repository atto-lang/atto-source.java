package atto.compiler.adt;

import java.util.List;
import static atto.compiler.adt.AttoIntParseRuntime.*;
import static atto.compiler.adt.AttoAdtPatternPlanner.*;

/** HOST_ONLY: Java/Graal runtime contract, not compiled .atto guest evidence. */
public final class AttoIntParseRuntimeProbe {
    private static int checks;
    private static void ok(boolean yes, String why) {
        if (!yes) throw new AssertionError(why);
        checks++;
    }
    private static long numeric(Result result) {
        return ((Ok) result).value();
    }
    public static void main(String[] args) {
        TenantParser alice = new TenantParser(101), bob = new TenantParser(102);
        for (long number = -20000; number <= 20000; ++number) {
            Result result = alice.parse(Long.toString(number));
            ok(result instanceof Ok && numeric(result) == number, "number " + number);
        }
        ok(numeric(alice.parse("+42")) == 42L, "plus sign");
        ok(numeric(alice.parse("-0")) == 0L, "negative zero");
        ok(numeric(alice.parse("-9223372036854775808")) == Long.MIN_VALUE, "min");
        ok(numeric(alice.parse("9223372036854775807")) == Long.MAX_VALUE, "max");
        ok(numeric(alice.parse("00042")) == 42L, "leading zeros");
        for (String invalid : List.of("", "+", "-", " 42", "42 ", "--1", "-+1",
                "42.0", "42e0", "4_2", "42x", "9223372036854775808",
                "-9223372036854775809", "٤٢", "０", "\u0000", "4\u00002")) {
            Result value = alice.parse(invalid);
            ok(value instanceof Err, "invalid must be Err: " + invalid);
            Error error = ((Err)value).error();
            ok(error.message().equals(INVALID_INTEGER), "immutable Error.message");
            ok(alice.can_observe(error), "tenant owns Error");
            ok(!bob.can_observe(error), "other tenant cannot observe Error");
        }
        ok(alice.parse("0".repeat(1024)) instanceof Ok, "1024 bytes admitted");
        ok(alice.parse("0".repeat(1025)) instanceof Err, "1025 bytes rejected");
        ok(alice.parse(null) instanceof Err, "invalid host null rejected");
        Err original = (Err)alice.parse("bad");
        Result propagated = original;
        Err separate = (Err)alice.parse("bad");
        ok(((Err)propagated).error() == original.error(), "same Error reference");
        ok(original.error() != separate.error(), "distinct allocations");
        ok(bob.can_observe(((Err)bob.parse("bad")).error()), "local bob Error");

        // Existing host ADT matcher must allow payload-discard Err() / Ok()
        // without inspecting or copying the Error object into a user binder.
        Plan plan = check(Family.RESULT, List.of(
            new Arm(parse("Ok()", Family.RESULT, Position.MATCH), false, null, "valid"),
            new Arm(parse("Err()", Family.RESULT, Position.MATCH), false, null, "invalid")
        ), true);
        ok(plan.exhaustive(), "both result variants covered");
        ok(plan.select(Tag.ERR, 0).label().equals("invalid")
           && !plan.select(Tag.ERR, 0).bound(), "Err() discards payload");
        ok(plan.select(Tag.OK, 42).label().equals("valid"), "Ok() branch");
        boolean refused = false;
        try { new TenantParser(0); } catch (IllegalArgumentException e) {
            refused = "E_TENANT_ID".equals(e.getMessage());
        }
        ok(refused, "reject unscoped parser");
        System.out.println("ATTO_INT_PARSE_HOST_JVM_PASS checks=" + checks);
        System.out.println("ATTO_INT_PARSE_ATTO_GUEST=NOT_EXECUTED");
    }
}
