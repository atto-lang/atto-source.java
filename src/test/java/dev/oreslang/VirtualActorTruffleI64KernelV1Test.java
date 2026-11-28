package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.VirtualActorProtectedOpsMirV1;
import dev.oreslang.compiler.VirtualActorTruffleI64KernelV1;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This executes the pure Truffle scalar call target, NOT a guest virtual
 * actor or a physical state transaction. CI must provide actual JUnit XML.
 */
final class VirtualActorTruffleI64KernelV1Test {
    private static String source() throws Exception {
        Path p = Path.of("contracts", "virtual-actor-mir-v1",
                "positive", "cart_checked_contract.atto").toAbsolutePath().normalize();
        assertFalse(Files.isSymbolicLink(p));
        assertTrue(Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS));
        byte[] bytes = Files.readAllBytes(p);
        assertTrue(bytes.length > 0 && bytes.length <= 8192);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static VirtualActorTruffleI64KernelV1.CompiledProgram kernel() throws Exception {
        Ast.VirtualActorDecl actor = assertInstanceOf(Ast.VirtualActorDecl.class,
                Parser.parse(source()).modules().getFirst().declarations().getFirst());
        return VirtualActorTruffleI64KernelV1.lower(VirtualActorProtectedOpsMirV1.lower(actor));
    }

    @Test
    void createsRealTruffleCallTargetFromParsedCartMir() throws Exception {
        var compiled = kernel();
        assertEquals("Cart", compiled.declaration());
        assertEquals(1, compiled.methods().size());
        var method = compiled.requireCandidate("add", List.of(Ast.TypeRef.simple("int")));
        assertSame(compiled.methods().getFirst(), method);
        assertEquals("add", method.name());
        assertEquals(0, method.localOrdinal());
        assertEquals(2L, method.computeCandidate(0L, 2L));
        assertEquals(7L, method.computeCandidate(2L, 5L));
        assertEquals(-5L, method.computeCandidate(0L, -5L));
    }

    @Test
    void signedI64BoundaryTrapsBeforeAnyStateWrite() throws Exception {
        var method = kernel().requireCandidate("add", List.of(Ast.TypeRef.simple("int")));
        assertEquals(Long.MAX_VALUE, method.computeCandidate(Long.MAX_VALUE - 1, 1));
        assertEquals(Long.MIN_VALUE, method.computeCandidate(Long.MIN_VALUE + 1, -1));
        assertThrows(ArithmeticException.class,
                () -> method.computeCandidate(Long.MAX_VALUE, 1L));
        assertThrows(ArithmeticException.class,
                () -> method.computeCandidate(Long.MIN_VALUE, -1L));
        assertThrows(ArithmeticException.class,
                () -> method.computeCandidate(Long.MAX_VALUE, Long.MAX_VALUE));
        // Reusing the very same compiled call target after an overflow must
        // not retain a partial numeric result or hidden per-call state.
        assertThrows(ArithmeticException.class,
                () -> method.computeCandidate(Long.MAX_VALUE, 1L));
        assertEquals(17L, method.computeCandidate(10L, 7L));
        assertEquals(0L, method.computeCandidate(7L, -7L));
    }

    @Test
    void deterministicSignedI64OracleRejectsWraparound() throws Exception {
        var method = kernel().requireCandidate("add", List.of(Ast.TypeRef.simple("int")));
        var random = new Random(0x5C01A2026L);
        BigInteger minimum = BigInteger.valueOf(Long.MIN_VALUE);
        BigInteger maximum = BigInteger.valueOf(Long.MAX_VALUE);
        for (int i = 0; i < 512; i++) {
            long left = random.nextLong();
            long right = random.nextLong();
            BigInteger expected = BigInteger.valueOf(left).add(BigInteger.valueOf(right));
            if (expected.compareTo(minimum) < 0 || expected.compareTo(maximum) > 0) {
                assertThrows(ArithmeticException.class,
                        () -> method.computeCandidate(left, right),
                        "checked kernel must reject overflow vector " + i);
            } else {
                assertEquals(expected.longValueExact(), method.computeCandidate(left, right),
                        "checked kernel mismatch for vector " + i);
            }
        }
    }

    @Test
    void typedMethodSelectionRejectsWrongNameArityAndType() throws Exception {
        var compiled = kernel();
        assertThrows(IllegalArgumentException.class,
                () -> compiled.requireCandidate("unknown", List.of(Ast.TypeRef.simple("int"))));
        assertThrows(IllegalArgumentException.class,
                () -> compiled.requireCandidate("add", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> compiled.requireCandidate("add", List.of(Ast.TypeRef.simple("string"))));
        assertThrows(IllegalArgumentException.class,
                () -> compiled.requireCandidate("add", List.of(
                        Ast.TypeRef.simple("int"), Ast.TypeRef.simple("int"))));
        assertThrows(UnsupportedOperationException.class, () -> compiled.methods().clear());
    }

    @Test
    void backendDoesNotExposeConstructorsOrGrantActorAuthority() throws Exception {
        for (var ctor : VirtualActorTruffleI64KernelV1.CompiledProgram.class.getDeclaredConstructors())
            assertTrue(Modifier.isPrivate(ctor.getModifiers()));
        for (var ctor : VirtualActorTruffleI64KernelV1.CompiledMethod.class.getDeclaredConstructors())
            assertTrue(Modifier.isPrivate(ctor.getModifiers()));
        for (var field : VirtualActorTruffleI64KernelV1.CompiledMethod.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().contains("ActorRef"));
            assertFalse(field.getType().getName().contains("ActorRuntime"));
        }
    }

    @Test
    void productionTypeCheckingStillRejectsExecutableVirtualActor() throws Exception {
        assertNotNull(kernel());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source())));
        assertTrue(failure.getMessage().contains("VIRTUAL_ACTOR_MIR_UNSUPPORTED"));
    }
}
