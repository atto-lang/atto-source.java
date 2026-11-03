package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The narrow typed-MIR entry is compiler-only. These tests do not execute
 * guest code or establish GraalVM Native Image parity.
 */
final class AotReflectionTypedMirV1Test {
    private static final String CLASSES = """
            pub define class Person as
              pub int visible: 7
              int hidden_secret_marker: 9
            end
            """;

    private static String source(String body) {
        return CLASSES + "\npub fnc main(): TypeDescriptor {\n" + body + "\n}\n";
    }

    private static String readFixture(String resource) throws Exception {
        try (var stream = AotReflectionTypedMirV1Test.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "missing committed .atto test fixture");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void lowersRealParsedGuestSourceToTypedConstantAndReturn() throws Exception {
        String atto = readFixture("/aot-reflection/typed_main_type_of_nominal.atto");
        var plan = OresCompiler.lowerAotReflectionTypedMirV1(atto, "pkg@revision1");
        assertEquals("main", plan.entryPoint());
        assertEquals("__root__.Person", plan.resultType().qualifiedNominal());
        assertEquals(64, plan.resultType().typeIdHex().length());
        assertEquals(List.of(
                new AotReflectionTypedMirV1.LoadTypeDescriptor(0, plan.resultType().typeIdHex()),
                new AotReflectionTypedMirV1.ReturnDescriptor(0)),
                plan.instructions());
        assertTrue(plan.instructions().stream().allMatch(
                op -> op.valueType() == AotReflectionTypedMirV1.ValueType.TYPE_DESCRIPTOR));
        var table = AotReflectionTableV1Reader.decode(plan.descriptorTable());
        assertEquals(1, table.types().size());
        assertEquals(plan.resultType().typeIdHex(), table.types().getFirst().typeIdHex());
        assertEquals("visible", table.types().getFirst().publicFields().getFirst().name());
        assertFalse(new String(plan.descriptorTable(), StandardCharsets.UTF_8)
                .contains("hidden_secret_marker"));
    }

    @Test
    void emissionIsDeterministicAndCannotExposeMutableBytes() {
        var first = AotReflectionTypedMirV1.lowerSource(
                source("  return type_of<Person>();"), "pkg@revision1");
        var second = AotReflectionTypedMirV1.lowerSource(
                source("  return type_of<Person>();"), "pkg@revision1");
        assertArrayEquals(first.descriptorTable(), second.descriptorTable());
        assertEquals(first.resultType(), second.resultType());
        byte[] leaked = first.descriptorTable();
        leaked[0] = 0;
        assertEquals((byte) 'A', first.descriptorTable()[0]);
        assertThrows(UnsupportedOperationException.class, () ->
                first.instructions().add(new AotReflectionTypedMirV1.ReturnDescriptor(0)));
    }

    @Test
    void planIsCompilerIssuedAndInstructionRecordsAreBounded() {
        assertEquals(0, AotReflectionTypedMirV1.Plan.class.getConstructors().length,
                "no public constructor may allow a caller to forge a checked typed MIR plan");
        assertThrows(IllegalArgumentException.class, () ->
                new AotReflectionTypedMirV1.LoadTypeDescriptor(1, "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () ->
                new AotReflectionTypedMirV1.LoadTypeDescriptor(0, "bad-id"));
        assertThrows(IllegalArgumentException.class, () ->
                new AotReflectionTypedMirV1.ReturnDescriptor(1));
        assertThrows(IllegalArgumentException.class, () ->
                new AotReflectionTypedMirV1.TypeDescriptorType("Fake", "bad-id"));
    }

    @Test
    void corruptedCallerCopyCannotModifyIssuedTypedMir() {
        var original = AotReflectionTypedMirV1.lowerSource(
                source("  return type_of<Person>();"), "pkg@revision1");
        byte[] corrupt = original.descriptorTable();
        corrupt[corrupt.length - 1] ^= 1;
        assertArrayEquals(AotReflectionTypedMirV1.lowerSource(
                source("  return type_of<Person>();"), "pkg@revision1").descriptorTable(),
                original.descriptorTable());
        assertNotEquals(corrupt[corrupt.length - 1],
                original.descriptorTable()[corrupt.length - 1]);
    }

    @Test
    void ordinaryCompilerRemainsFailClosedForTheSameGuest() throws Exception {
        String source = readFixture("/aot-reflection/typed_main_type_of_nominal.atto");
        IllegalArgumentException rejection = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(rejection.getMessage().contains(
                "type_of<T>() requires compiler-backed guest AOT lowering"));
    }

    @Test
    void doesNotLowerArbitraryCallsOrValuesInTheReturn() {
        for (String statement : new String[]{
                "return type_of<Person>(7);",
                "return type_of<Person,Other>();",
                "return type_of<Person<int>>();",
                "return namespace.type_of<Person>();",
                "return type_of<Person>() + 1;",
                "return type_of<Person>().fields();",
                "return another();",
                "return 0;",
                "return type_of<Missing>();",
                "return type_of<_>();"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> AotReflectionTypedMirV1.lowerSource(source("  " + statement), "pkg@revision1"),
                    statement);
        }
    }

    @Test
    void noFunctionOrTypeShadowingOfCompilerDescriptor() {
        String spoof = CLASSES + """
                fnc type_of(): int { return 0; }
                pub fnc main(): TypeDescriptor {
                  return type_of<Person>();
                }
                """;
        assertThrows(IllegalArgumentException.class, () ->
                AotReflectionTypedMirV1.lowerSource(spoof, "pkg@revision1"));
        String typeSpoof = """
                pub define class TypeDescriptor as
                  pub int count: 7
                end
                pub fnc main(): TypeDescriptor {
                  return type_of<TypeDescriptor>();
                }
                """;
        assertThrows(IllegalArgumentException.class, () ->
                AotReflectionTypedMirV1.lowerSource(typeSpoof, "pkg@revision1"));
    }

    @Test
    void entryMustBePublicPureClosedAndExactlyOneReturn() {
        String valid = source("  return type_of<Person>();");
        for (String malformed : new String[]{
                valid.replace("pub fnc main", "fnc main"),
                valid.replace("fnc main", "routine main"),
                valid.replace("fnc main()", "fnc main(int n)"),
                valid.replace("fnc main()", "suspends fnc main()"),
                valid.replace("main(): TypeDescriptor", "main(): int"),
                valid.replace("return type_of<Person>();",
                        "return type_of<Person>();\n  return type_of<Person>();"),
                valid.replace("pub fnc main(): TypeDescriptor", "pub fnc other(): TypeDescriptor")
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> AotReflectionTypedMirV1.lowerSource(malformed, "pkg@revision1"));
        }
    }

    @Test
    void privateOrUnsupportedNominalCannotMintPublicDescriptor() {
        String privateClass = """
                define class Secret as
                  pub int visible: 4
                end
                pub fnc main(): TypeDescriptor {
                  return type_of<Secret>();
                }
                """;
        assertThrows(IllegalArgumentException.class, () ->
                AotReflectionTypedMirV1.lowerSource(privateClass, "pkg@revision1"));
        assertThrows(IllegalArgumentException.class, () ->
                AotReflectionTypedMirV1.lowerSource(
                        source("  return type_of<Person>();").replace(
                                "pub int visible: 7", "pub UnknownType visible;"),
                        "pkg@revision1"));
    }

    @Test
    void boundedSourceAndSingleModuleAdmission() {
        assertThrows(IllegalArgumentException.class, () ->
                AotReflectionTypedMirV1.lowerSource("x".repeat(65_537), "pkg@revision1"));
        assertThrows(IllegalArgumentException.class, () ->
                AotReflectionTypedMirV1.lowerSource(null, "pkg@revision1"));
        assertThrows(IllegalArgumentException.class, () ->
                AotReflectionTypedMirV1.lowerSource(
                        source("  return type_of<Person>();"), null));
        assertThrows(IllegalArgumentException.class, () ->
                AotReflectionTypedMirV1.lowerSource(
                        source("  return type_of<Person>();"), ""));
        assertThrows(IllegalArgumentException.class, () ->
                AotReflectionTypedMirV1.lowerSource(
                        source("  return type_of<Person>();"), "x".repeat(513)));
        assertThrows(IllegalArgumentException.class, () ->
                AotReflectionTypedMirV1.lowerSource(
                        source("  return type_of<Person>();") +
                        "pub fnc other(): int { return 1; }", "pkg@revision1"));
    }
}
