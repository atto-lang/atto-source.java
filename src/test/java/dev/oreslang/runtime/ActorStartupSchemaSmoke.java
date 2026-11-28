package dev.oreslang.runtime;

import java.util.*;
import static dev.oreslang.runtime.ActorStartupSchema.*;

final class ActorStartupSchemaSmoke {
    private static int checks;
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); checks++; }
    private static void deny(Runnable r) {
        try { r.run(); } catch (IllegalArgumentException e) { check("E_SPAWN_INIT_SCHEMA".equals(e.getMessage())); return; }
        throw new AssertionError("expected schema rejection");
    }
    public static void main(String[] args) {
        var fields = new ArrayList<>(List.of(new Field("seed", Kind.INT64), new Field("ready", Kind.BOOL)));
        var schema = new ActorStartupSchema(fields);
        var foreign = new ActorStartupSchema(fields);
        fields.clear();
        var supplied = new ArrayList<>(List.of(new Supplied("ready", true), new Supplied("seed", 42L)));
        var payload = schema.validate(supplied);
        supplied.clear();
        check(schema.valuesFor(payload).equals(List.of(42L, true)));
        try { schema.valuesFor(payload).clear(); throw new AssertionError(); }
        catch (UnsupportedOperationException expected) { checks++; }
        deny(() -> foreign.valuesFor(payload));
        deny(() -> schema.valuesFor(null));
        deny(() -> schema.validate(List.of(new Supplied("seed", 42L))));
        deny(() -> schema.validate(List.of(new Supplied("seed", 42L), new Supplied("seed", 43L))));
        deny(() -> schema.validate(List.of(new Supplied("seed", 42L), new Supplied("other", true))));
        for (Object value : Arrays.asList(42, 42.0, "42", null, new long[]{42}))
            deny(() -> schema.validate(List.of(new Supplied("seed", value), new Supplied("ready", true))));
        deny(() -> schema.validate(List.of(new Supplied("seed", 42L), new Supplied("ready", 1L))));
        deny(() -> new ActorStartupSchema(List.of(new Field("seed", Kind.INT64), new Field("seed", Kind.BOOL))));
        var empty = new ActorStartupSchema(List.of());
        check(empty.valuesFor(empty.validate(List.of())).isEmpty());
        deny(() -> empty.validate(List.of(new Supplied("seed", 1L))));
        var many = new ArrayList<Field>();
        for (int i = 0; i < 256; i++) many.add(new Field("f" + i, Kind.INT64));
        check(new ActorStartupSchema(many) != null);
        many.add(new Field("overflow", Kind.INT64));
        deny(() -> new ActorStartupSchema(many));
        System.out.println("ACTOR_STARTUP_SCHEMA_PASS " + checks + " checks; scalar host shape only");
    }
}
