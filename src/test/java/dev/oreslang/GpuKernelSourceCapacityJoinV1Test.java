package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.GpuKernelSourceCapacityJoinV1;
import dev.oreslang.compiler.GpuKernelSourceHirV1;
import dev.oreslang.compiler.GpuKernelSourceLinkV1;
import dev.oreslang.compiler.GpuLaunchFootprints.Access;
import dev.oreslang.compiler.GpuLaunchFootprints.Limits;
import dev.oreslang.compiler.GpuLaunchFootprints.QueueScope;
import dev.oreslang.compiler.GpuLaunchFootprints.View;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Source/HIR/host integration only; no authenticated GPU provider or device execution. */
final class GpuKernelSourceCapacityJoinV1Test {
    private static final String KERNEL = """
            pub gpu fnc saxpy(
                f32 alpha, GpuReadBuffer<f32> x, GpuReadBuffer<f32> y,
                GpuWriteBuffer<f32> out, int n
            ): void {
                const int i = gpu.global_id(0);
                if i < n { out[i] = alpha * x[i] + y[i]; } fi
            }
            """;
    private static final String HOST = """
            pub fnc main(): void {
                val pending = launch saxpy(alpha, x, y, out, n) on gpu(device) {
                    over: [n], group: [256], queue: queue
                };
                return;
            }
            """;
    private static final QueueScope QUEUE = new QueueScope("tenant", "gpu", "stream", 9);
    private static final Limits BUDGET = new Limits(16, 65536);

    private record Subject(GpuKernelSourceHirV1.Plan plan,
                           GpuKernelSourceLinkV1.Link link) { }

    private static Subject subject(String host) {
        Ast.Program parsed = Parser.parse(KERNEL + host);
        var kernel = (Ast.GpuKernelDecl) parsed.modules().getFirst().declarations().getFirst();
        var plan = GpuKernelSourceHirV1.lower(kernel);
        var report = GpuKernelSourceLinkV1.audit(parsed);
        assertEquals(1, report.links().size());
        return new Subject(plan, report.links().getFirst());
    }

    private static View view(String allocation, long bytes, Access access) {
        return new View("tenant", "gpu", allocation, 5, bytes,
                0, bytes, 4, access);
    }

    private static Map<Integer, View> views() {
        return Map.of(1, view("x", 1024, Access.READ),
                      2, view("y", 1024, Access.READ),
                      3, view("out", 1024, Access.WRITE));
    }

    private static GpuKernelSourceCapacityJoinV1.Report audit(
            Subject subject, Map<Integer, View> slots, long over, long n) {
        return GpuKernelSourceCapacityJoinV1.audit(
                subject.plan(), subject.link(), QUEUE, over, n, slots, BUDGET);
    }

    @Test void sourcePlanLinkAndFootprintsAreBoundButNeverExecutable() {
        var report = audit(subject(HOST), views(), 256, 256);
        assertEquals("saxpy", report.kernel());
        assertEquals(3, report.checkedViews());
        assertEquals(3, report.auditedMemoryOperations());
        assertEquals(1024, report.minimumBytesPerView());
        assertEquals(GpuKernelSourceCapacityJoinV1.Evidence
                .SOURCE_JOINED_CAPACITY_SHAPE_ONLY_NOT_EXECUTABLE,
                report.evidence());
        assertFalse(report.executable());
    }

    @Test void sourceExpectedAccessCannotBeOverriddenByCaller() {
        var change = new HashMap<>(views());
        change.put(3, view("out", 1024, Access.READ));
        assertThrows(IllegalArgumentException.class,
                () -> audit(subject(HOST), change, 256, 256));
        // Restore out before testing x so this negative exercises the
        // SOURCE-READ parameter check, not the earlier out-read failure.
        change.put(3, view("out", 1024, Access.WRITE));
        change.put(1, view("x", 1024, Access.WRITE));
        assertThrows(IllegalArgumentException.class,
                () -> audit(subject(HOST), change, 256, 256));
    }

    @Test void aliasingWriteAndReadParameterFailsHostFootprintCheck() {
        var change = new HashMap<>(views());
        change.put(3, view("x", 1024, Access.WRITE));
        assertThrows(IllegalArgumentException.class,
                () -> audit(subject(HOST), change, 256, 256));
    }

    @Test void differentViewsCannotBeInventedForSameLexicalName() {
        var reusedSource = subject(HOST.replace("saxpy(alpha, x, y, out, n)",
                                               "saxpy(alpha, x, x, out, n)"));
        var ex = assertThrows(IllegalArgumentException.class,
                () -> audit(reusedSource, views(), 256, 256));
        assertTrue(ex.getMessage().contains("contradictory"), ex::getMessage);
    }

    @Test void aDifferentLexicalNDoesNotJoinCapacityProof() {
        var altered = subject(HOST.replace("saxpy(alpha, x, y, out, n)",
                                           "saxpy(alpha, x, y, out, m)"));
        assertThrows(IllegalArgumentException.class,
                () -> audit(altered, views(), 256, 256));
    }

    @Test void literalNMustEqualAuditedLogicalExtent() {
        var altered = subject(HOST.replace("saxpy(alpha, x, y, out, n)",
                                           "saxpy(alpha, x, y, out, 255)"));
        assertThrows(IllegalArgumentException.class,
                () -> audit(altered, views(), 256, 256));
    }

    @Test void missingOrExtraViewSlotsAreRejected() {
        var subject = subject(HOST);
        var missing = new HashMap<>(views());
        missing.remove(2);
        assertThrows(IllegalArgumentException.class,
                () -> audit(subject, missing, 256, 256));
        var extra = new HashMap<>(views());
        extra.put(0, view("fake", 1024, Access.READ));
        assertThrows(IllegalArgumentException.class,
                () -> audit(subject, extra, 256, 256));
    }

    @Test void sourceArgumentTypeMismatchFailsBeforeCapacity() {
        var original = subject(HOST);
        var arguments = new java.util.ArrayList<>(original.link().arguments());
        arguments.set(1, new GpuKernelSourceLinkV1.BoundArgument(
                GpuKernelSourceLinkV1.ArgumentKind.LEXICAL_NAME_UNTYPED,
                GpuKernelSourceHirV1.Type.I64, "x"));
        var forged = new GpuKernelSourceLinkV1.Link(
                original.link().module(), original.link().kernel(), arguments,
                GpuKernelSourceLinkV1.Evidence.SOURCE_SIGNATURE_ONLY_NOT_EXECUTABLE);
        var pair = new Subject(original.plan(), forged);
        assertThrows(IllegalArgumentException.class,
                () -> audit(pair, views(), 256, 256));
    }

    @Test void deviceBufferSizeAndOverExtentMustAgreeWithN() {
        var subject = subject(HOST);
        var tooShort = new HashMap<>(views());
        tooShort.put(3, view("out", 1020, Access.WRITE));
        assertThrows(IllegalArgumentException.class,
                () -> audit(subject, tooShort, 256, 256));
        assertThrows(IllegalArgumentException.class,
                () -> audit(subject, views(), 257, 256));
    }

    @Test void foreignTenantAndGenerationRemainUntrusted() {
        var subject = subject(HOST);
        var foreign = new HashMap<>(views());
        foreign.put(3, new View("other-tenant", "gpu", "out", 5,
                1024, 0, 1024, 4, Access.WRITE));
        assertThrows(SecurityException.class,
                () -> audit(subject, foreign, 256, 256));
        var stale = new HashMap<>(views());
        stale.put(3, new View("tenant", "gpu", "x", 3,
                1024, 0, 1024, 4, Access.WRITE));
        assertThrows(IllegalArgumentException.class,
                () -> audit(subject, stale, 256, 256));
    }

    @Test void noSourceReportCreatesAnExecutableLaunchPermit() {
        var subject = subject(HOST);
        assertFalse(subject.plan().executable());
        assertFalse(subject.link().executable());
        assertFalse(audit(subject, views(), 0, 0).executable());
    }

    @Test void sameReadOnlySourceAliasAndSameViewDoNotMintExclusiveLoan() {
        var repeated = subject(HOST.replace("saxpy(alpha, x, y, out, n)",
                                             "saxpy(alpha, x, x, out, n)"));
        var same = new HashMap<>(views());
        same.put(2, same.get(1));
        var report = audit(repeated, same, 256, 256);
        assertEquals(3, report.checkedViews());
        assertFalse(report.executable());
    }

    @Test void readOnlyAliasesWithDifferentAllocationGenerationsAreRejected() {
        // Distinct lexical arguments, same physical allocation identifier.
        // Reaching the host footprint stage (rather than the earlier
        // contradictory-source-name guard) is the test's purpose.
        var mismatched = new HashMap<>(views());
        mismatched.put(2, new View("tenant", "gpu", "x", 3,
                                  1024, 0, 1024, 4, Access.READ));
        var error = assertThrows(IllegalArgumentException.class,
                () -> audit(subject(HOST), mismatched, 256, 256));
        assertTrue(error.getMessage().contains("inconsistent allocation generation"),
                error::getMessage);
    }

    @Test void f32RequiresAlignedByteExtent() {
        var mutated = new HashMap<>(views());
        mutated.put(3, new View("tenant", "gpu", "out", 5,
                                1026, 0, 1026, 4, Access.WRITE));
        assertThrows(IllegalArgumentException.class,
                () -> audit(subject(HOST), mutated, 256, 256));
    }

    @Test void sourceNByteMultiplicationCannotOverflowSignedLong() {
        var original = subject(HOST);
        assertThrows(IllegalArgumentException.class,
                () -> audit(original, views(), Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test void emptyLogicalSourceStillRejectsForeignTenant() {
        var mutated = new HashMap<>(views());
        mutated.put(3, new View("foreign", "gpu", "out", 5,
                                1024, 0, 1024, 4, Access.WRITE));
        assertThrows(SecurityException.class,
                () -> audit(subject(HOST), mutated, 0, 0));
    }

    @Test void emptyLogicalSourceStillRejectsReadWriteOverlap() {
        var mutated = new HashMap<>(views());
        mutated.put(3, new View("tenant", "gpu", "x", 5,
                                1024, 0, 1024, 4, Access.WRITE));
        assertThrows(IllegalArgumentException.class,
                () -> audit(subject(HOST), mutated, 0, 0));
    }

    @Test void foreignDeviceScopeIsRejectedEvenWhenBytesFit() {
        var mutated = new HashMap<>(views());
        mutated.put(3, new View("tenant", "different-gpu", "out", 5,
                                1024, 0, 1024, 4, Access.WRITE));
        assertThrows(SecurityException.class,
                () -> audit(subject(HOST), mutated, 256, 256));
    }
}
