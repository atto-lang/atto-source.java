package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.OresEntry;
import dev.oreslang.runtime.OresTuple;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests tuple nominal kind at runtime, not just its static AST type. */
final class TupleRuntimeKindTest {
    private record GuestRuntime(Object evaluator, Method equality, Method matches, Method shape) {
        boolean equal(Object a, Object b) throws Exception {
            return (boolean) equality.invoke(evaluator, a, b);
        }

        boolean matches(Object value, Ast.TypeRef type) throws Exception {
            return (boolean) matches.invoke(evaluator, value, type);
        }

        IllegalArgumentException rejectsReturn(Ast.TypeRef type, Object value) {
            InvocationTargetException error = assertThrows(
                    InvocationTargetException.class,
                    () -> shape.invoke(evaluator, type, value, "runtime tuple boundary"));
            return assertInstanceOf(IllegalArgumentException.class, error.getCause());
        }

        Object shaped(Ast.TypeRef type, Object value) throws Exception {
            return shape.invoke(evaluator, type, value, "runtime tuple boundary");
        }
    }

    private static GuestRuntime runtime() throws Exception {
        Class<?> evaluator = Class.forName("dev.oreslang.nodes.OresEvalRootNode$Evaluator");
        Constructor<?> ctor = evaluator.getDeclaredConstructor(
                Ast.Program.class, OresContext.class, String.class);
        ctor.setAccessible(true);
        Object instance = ctor.newInstance(
                Parser.parse("fnc main(): void { return; }"), null, "tuple-kind-runtime-test");
        Method equality = evaluator.getDeclaredMethod("valueEquals", Object.class, Object.class);
        Method matches = evaluator.getDeclaredMethod(
                "oresTypeMatches", Object.class, Ast.TypeRef.class);
        Method shape = evaluator.getDeclaredMethod(
                "shapeReturnedValue", Ast.TypeRef.class, Object.class, String.class);
        equality.setAccessible(true);
        matches.setAccessible(true);
        shape.setAccessible(true);
        return new GuestRuntime(instance, equality, matches, shape);
    }

    private static Ast.TypeRef pairType() {
        return Ast.TypeRef.tupleType(List.of(
                Ast.TypeRef.simple("int"), Ast.TypeRef.simple("int")));
    }

    private static Ast.TypeRef arrayType() {
        return new Ast.TypeRef("Array", List.of(Ast.TypeRef.simple("int")), false);
    }

    @Test
    void nominalTupleAndArrayAreNotRuntimeEqualDespiteIdenticalElements() throws Exception {
        GuestRuntime guest = runtime();
        OresTuple tuple = new OresTuple(List.of(1L, 2L));
        ArrayList<Long> array = new ArrayList<>(List.of(1L, 2L));
        assertTrue(guest.equal(tuple, new OresTuple(List.of(1, 2))));
        assertFalse(guest.equal(tuple, array));
        assertFalse(guest.equal(array, tuple));
        assertTrue(guest.matches(tuple, pairType()));
        assertFalse(guest.matches(array, pairType()));
        assertFalse(guest.matches(tuple, arrayType()));
        assertTrue(guest.matches(array, arrayType()));
    }

    @Test
    void emptyAndSingletonTuplesRemainNominal() throws Exception {
        GuestRuntime guest = runtime();
        for (List<Long> values : List.of(List.<Long>of(), List.of(5L))) {
            OresTuple tuple = new OresTuple(values);
            assertFalse(guest.equal(tuple, new ArrayList<>(values)));
            assertFalse(guest.equal(new ArrayList<>(values), tuple));
            assertEquals(values.size(), tuple.size());
        }
    }

    @Test
    void returnedTupleCannotBeForgedFromAnArrayOrConversely() throws Exception {
        GuestRuntime guest = runtime();
        OresTuple tuple = new OresTuple(List.of(1, 2));
        assertInstanceOf(OresTuple.class, guest.shaped(pairType(), tuple));
        assertTrue(guest.rejectsReturn(pairType(), new ArrayList<>(List.of(1, 2)))
                .getMessage().contains("returned"));
        assertTrue(guest.rejectsReturn(arrayType(), tuple)
                .getMessage().contains("returned"));
    }

    @Test
    void frozenEntriesPreserveNestedTupleIdentityAndImmutability() throws Exception {
        GuestRuntime guest = runtime();
        OresTuple tuple = new OresTuple(List.of(1L, 2L));
        OresEntry frozen = assertInstanceOf(OresEntry.class,
                ActorRuntime.freeze(new OresEntry(tuple, "v")));
        OresTuple frozenTuple = assertInstanceOf(OresTuple.class, frozen.key());
        assertNotSame(tuple, frozenTuple);
        assertTrue(guest.equal(tuple, frozenTuple));
        assertFalse(guest.equal(frozenTuple, new ArrayList<>(List.of(1L, 2L))));
        assertThrows(UnsupportedOperationException.class, () -> frozenTuple.add(3L));
    }

    @Test
    void tuplePatternNeverAcceptsArrayEvenIfElementsMatch() throws Exception {
        GuestRuntime guest = runtime();
        Method patterns = guest.evaluator().getClass().getDeclaredMethod(
                "patternMatches",
                Ast.Pattern.class, Object.class, java.util.Map.class);
        patterns.setAccessible(true);
        Ast.TuplePattern shape = new Ast.TuplePattern(List.of(
                new Ast.LiteralPattern(1L),
                new Ast.LiteralPattern(2L)));

        assertTrue((boolean) patterns.invoke(guest.evaluator(), shape,
                new OresTuple(List.of(1L, 2L)),
                new java.util.LinkedHashMap<String, Object>()));
        assertFalse((boolean) patterns.invoke(guest.evaluator(), shape,
                new ArrayList<>(List.of(1L, 2L)),
                new java.util.LinkedHashMap<String, Object>()));
    }

    @Test
    void privateIsolationCopyRetainsTupleTagNestedInEntry() throws Exception {
        Method isolate = ActorRuntime.class.getDeclaredMethod(
                "isolateCopy", Object.class);
        isolate.setAccessible(true);
        OresEntry value = new OresEntry(new OresTuple(List.of(3L, 4L)), "v");
        OresEntry copy = assertInstanceOf(OresEntry.class, isolate.invoke(null, value));
        OresTuple tuple = assertInstanceOf(OresTuple.class, copy.key());
        assertNotSame(value.key(), tuple);
        assertTrue(runtime().equal(tuple, value.key()));
    }

    @Test
    void hostOperationsCannotTraverseCyclicTuplePayloads() {
        ArrayList<Object> cycle = new ArrayList<>();
        OresTuple tuple = new OresTuple(List.of(cycle));
        cycle.add(tuple);
        assertSame(tuple, tuple);
        assertNotEquals(tuple, new OresTuple(List.of(cycle)));
        assertEquals(System.identityHashCode(tuple), tuple.hashCode());
        assertEquals("OresTuple<opaque>", tuple.toString());
    }

    @Test
    void asyncTupleReturnRemainsAValidRuntimeTuple() throws Exception {
        String code = """
                async fnc loadLeft(): int {
                  return 4;
                }

                async fnc pair(): (int, int) {
                  return tuple {await loadLeft(), 2};
                }

                pub fnc main(): void {
                  const p = await pair();
                  stdio.println(p[0] + p[1]);
                  return;
                }
                """;
        Ast.Program typed = TypeChecker.check(Parser.parse(code));
        OwnershipChecker.check(typed);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(
                            OresLanguage.ID, code, "tuple-kind-async.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build());
        }
        assertEquals("6", output.toString(StandardCharsets.UTF_8)
                .replace("\r\n", "\n").strip());
    }

    @Test
    void uncheckedArrayReturnCannotBeRetaggedAsTuple() throws Exception {
        String code = """
                fnc forged(): (int, int) {
                  return [1, 2];
                }

                pub fnc main(): void {
                  const result = forged();
                  stdio.println(result[0]);
                  return;
                }
                """;
        Ast.Program unchecked = Parser.parse(code);
        try (OresLanguage.PrecheckedProgramScope ignored =
                     OresLanguage.openPrecheckedProgram(
                             "tuple-return-forge.ores", code, unchecked);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(false)
                     .build()) {
            PolyglotException failure = assertThrows(PolyglotException.class,
                    () -> context.eval(Source.newBuilder(
                                    OresLanguage.ID, code, "tuple-return-forge.ores")
                            .mimeType(OresLanguage.MIME_TYPE)
                            .build()));
            assertTrue(failure.getMessage().contains("returned"),
                    failure.getMessage());
        }
    }
}
