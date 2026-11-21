package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.AggregateConstructionPlan;
import dev.oreslang.types.Types;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDK-only pure construction-plan regression. Does not imply guest/AOT parity.
 * No Maven, host reflection or runtime side effects.
 */
public final class AggregateConstructionPlanSmoke {
    private AggregateConstructionPlanSmoke() { }

    public static void main(String[] args) {
        Ast.ObjectExpr source = new Ast.ObjectExpr(
                List.of(
                        Ast.ObjectField.spreading(new Ast.NameExpr("base")),
                        Ast.ObjectField.named("foo", new Ast.LiteralExpr(42L)),
                        Ast.ObjectField.spreading(new Ast.NameExpr("other"))),
                Ast.ObjectKind.INFERRED_STRUCT,
                List.of());
        LinkedHashMap<String, Types.Type> first = new LinkedHashMap<>();
        first.put("foo", Types.Primitive.INT);
        first.put("bar", Types.Primitive.STRING);
        LinkedHashMap<String, Types.Type> last = new LinkedHashMap<>();
        last.put("bar", Types.Primitive.BOOL);
        last.put("baz", Types.Primitive.FLOAT);

        AggregateConstructionPlan.Builder builder = new AggregateConstructionPlan.Builder(source);
        builder.addSpread(0, new Types.Record(first, true, Types.AggregateKind.RECORD));
        builder.addNamed(1, "foo", Types.Primitive.INT);
        builder.addSpread(2, Types.Record.struct(last));
        AggregateConstructionPlan.Plan plan = builder.finish();

        check(plan.resultType().kind() == Types.AggregateKind.STRUCT, "result kind");
        check(plan.resultType().members().get("foo") == Types.Primitive.INT, "explicit override");
        check(plan.resultType().members().get("bar") == Types.Primitive.BOOL, "rightmost spread");
        check(plan.resultType().members().get("baz") == Types.Primitive.FLOAT, "appended field");
        check(List.copyOf(plan.resolvedFields().keySet()).equals(List.of("bar", "foo", "baz")),
                "canonicalize projection slots independently of source Map iteration");
        check(plan.orderedSteps().size() == 3, "ordered source steps");
        check(plan.orderedSteps().get(0) instanceof AggregateConstructionPlan.SpreadFields,
                "first spread step");
        check(plan.orderedSteps().get(1) instanceof AggregateConstructionPlan.NamedField,
                "named step");
        check(plan.orderedSteps().get(2) instanceof AggregateConstructionPlan.SpreadFields,
                "second spread step");
        List<AggregateConstructionPlan.FieldWrite> schedule = plan.scheduledWrites();
        check(schedule.size() == 5, "every scalar projection and override is retained");
        check(List.copyOf(schedule.stream().map(AggregateConstructionPlan.FieldWrite::sourceIndex).toList())
                        .equals(List.of(0, 0, 1, 2, 2)),
                "source evaluation index remains monotonic");
        check(schedule.stream().map(AggregateConstructionPlan.FieldWrite::targetSlot).toList()
                        .equals(List.of(0, 1, 1, 0, 2)),
                "rightmost writes reuse existing static result slots");
        check(schedule.stream().map(AggregateConstructionPlan.FieldWrite::fromSpread).toList()
                        .equals(List.of(true, true, false, true, true)),
                "named vs spread field writes stay explicit");
        check(schedule.get(0).fieldName().equals("bar") &&
                schedule.get(4).fieldName().equals("baz"), "projection key order");
        denied(() -> schedule.clear(), "field-write schedule mutation");


        first.put("tampered", Types.Primitive.INT);
        last.put("tampered", Types.Primitive.FLOAT);
        check(!plan.resolvedFields().containsKey("tampered"), "immutable shape snapshot");
        denied(() -> plan.resolvedFields().clear(), "plan shape mutation");
        denied(() -> plan.orderedSteps().clear(), "plan source sequence mutation");
        denied(() -> ((AggregateConstructionPlan.SpreadFields) plan.orderedSteps().get(0))
                        .fields().put("escape", Types.Primitive.INT),
                "spread projection mutation");

        Ast.ObjectExpr readonly = new Ast.ObjectExpr(
                List.of(Ast.ObjectField.spreading(new Ast.NameExpr("base"))),
                Ast.ObjectKind.INFERRED_RECORD, List.of());
        AggregateConstructionPlan.Plan record = new AggregateConstructionPlan.Builder(readonly)
                .addSpread(0, Types.Record.struct(Map.of("foo", Types.Primitive.INT)))
                .finish();
        check(record.resultType().readOnly(), "record remains readonly");
        check(record.resultType().kind() == Types.AggregateKind.RECORD,
                "struct source does not erase record result identity");
        String bmpPrivateUse = Character.toString(0xE000);
        String astral = new String(Character.toChars(0x10000));
        check(bmpPrivateUse.compareTo(astral) > 0,
                "regression setup: Java UTF-16 sort differs from scalar order");
        check(AggregateConstructionPlan.compareStaticFieldNames(bmpPrivateUse, astral) < 0,
                "static key ordering follows Unicode scalar values");
        Ast.ObjectExpr unicodeSource = new Ast.ObjectExpr(
                List.of(Ast.ObjectField.spreading(new Ast.NameExpr("source"))),
                Ast.ObjectKind.RECORD, List.of());
        AggregateConstructionPlan.Plan unicodePlan = new AggregateConstructionPlan.Builder(
                unicodeSource).addSpread(0,
                        Types.Record.struct(Map.of(
                                astral, Types.Primitive.INT,
                                bmpPrivateUse, Types.Primitive.BOOL))).finish();
        check(List.copyOf(unicodePlan.resolvedFields().keySet())
                        .equals(List.of(bmpPrivateUse, astral)),
                "native-compatible UTF-8 lexicographic projection order");


        denied(() -> new AggregateConstructionPlan.Plan(
                        Ast.ObjectKind.RECORD,
                        List.of(new AggregateConstructionPlan.NamedField(
                                3, "foo", Types.Primitive.INT)),
                        Map.of("foo", Types.Primitive.INT),
                        new Types.Record(Map.of("foo", Types.Primitive.INT),
                                true, Types.AggregateKind.RECORD)),
                "forged out-of-order plan step");
        denied(() -> new AggregateConstructionPlan.Plan(
                        Ast.ObjectKind.RECORD,
                        List.of(new AggregateConstructionPlan.NamedField(
                                0, "foo", Types.Primitive.INT)),
                        Map.of("foo", Types.Primitive.STRING),
                        new Types.Record(Map.of("foo", Types.Primitive.STRING),
                                true, Types.AggregateKind.RECORD)),
                "forged result type inconsistent with writes");
        denied(() -> new AggregateConstructionPlan.Plan(
                        Ast.ObjectKind.INFERRED_STRUCT,
                        List.of(new AggregateConstructionPlan.NamedField(
                                0, "foo", Types.Primitive.INT)),
                        Map.of("foo", Types.Primitive.INT),
                        new Types.Record(Map.of("foo", Types.Primitive.INT),
                                true, Types.AggregateKind.RECORD)),
                "forged struct/record result domain");
        denied(() -> new AggregateConstructionPlan.Plan(
                        Ast.ObjectKind.RECORD,
                        List.of(new AggregateConstructionPlan.SpreadFields(
                                0, Types.AggregateKind.RECORD,
                                Map.of("nested", Types.Record.struct(
                                        Map.of("value", Types.Primitive.INT))))),
                        Map.of("nested", Types.Record.struct(
                                Map.of("value", Types.Primitive.INT))),
                        new Types.Record(Map.of("nested", Types.Record.struct(
                                Map.of("value", Types.Primitive.INT))),
                                true, Types.AggregateKind.RECORD)),
                "forged non-copy aggregate projection");
        denied(() -> new AggregateConstructionPlan.Builder(source).finish(),
                "incomplete construction plan");
        denied(() -> new AggregateConstructionPlan.Builder(source)
                        .addNamed(0, "foo", Types.Primitive.INT),
                "named/spread AST mismatch");
        denied(() -> new AggregateConstructionPlan.Builder(source)
                        .addSpread(1, Types.Record.struct(Map.of())),
                "out-of-order source indices");
        denied(() -> new AggregateConstructionPlan.Builder(readonly)
                        .addSpread(0, Types.Record.contract(Map.of("foo", Types.Primitive.INT))),
                "internal structural contract row");
        denied(() -> new AggregateConstructionPlan.Builder(readonly)
                        .addSpread(0, Types.Record.struct(Map.of(
                                "bad" + (char) 0, Types.Primitive.INT))),
                "spread with invalid NUL field name");
        denied(() -> new AggregateConstructionPlan.Builder(readonly)
                        .addSpread(0, Types.Record.struct(Map.of(
                                "x".repeat(1025), Types.Primitive.INT))),
                "spread with oversized static key");
        denied(() -> new AggregateConstructionPlan.SpreadFields(
                        0, Types.AggregateKind.RECORD, Map.of(
                                "bad" + (char) 0xD800, Types.Primitive.INT)),
                "directly forged projection with invalid surrogate");

        denied(() -> new AggregateConstructionPlan.Builder(readonly)
                        .addSpread(0, Types.Record.struct(Map.of(
                                "nested", Types.Record.struct(Map.of("foo", Types.Primitive.INT))))),
                "nested resource requires ownership plan");
        denied(() -> new AggregateConstructionPlan.Builder(readonly)
                        .addSpread(0, Types.Record.struct(Map.of(
                                "decimal", Types.Primitive.DECIMAL))),
                "not-yet-admitted scalar kind");
        Ast.ObjectExpr named = new Ast.ObjectExpr(
                List.of(Ast.ObjectField.spreading(new Ast.NameExpr("base")),
                        Ast.ObjectField.named("value", new Ast.LiteralExpr(3L))),
                Ast.ObjectKind.RECORD, List.of());
        denied(() -> new AggregateConstructionPlan.Builder(named)
                        .addSpread(0, Types.Record.struct(Map.of("foo", Types.Primitive.INT)))
                        .addNamed(1, "value", Types.Record.struct(Map.of("inner", Types.Primitive.INT))),
                "owned intermediate field");
        denied(() -> new AggregateConstructionPlan.Builder(new Ast.ObjectExpr(
                        List.of(Ast.ObjectField.named("foo", new Ast.LiteralExpr(1L))),
                        Ast.ObjectKind.EXPLICIT_STRUCT,
                        List.of(new Ast.StructFieldSpec("foo", Ast.TypeRef.simple("int"))))),
                "explicit struct definite-initialization plan is distinct");

        System.out.println("AGGREGATE_CONSTRUCTION_PLAN_SMOKE_PASS");
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }

    private static void denied(Runnable action, String label) {
        try {
            action.run();
        } catch (IllegalArgumentException | UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("unexpected acceptance: " + label);
    }
}
