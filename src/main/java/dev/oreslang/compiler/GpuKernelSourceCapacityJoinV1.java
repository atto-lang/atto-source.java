package dev.oreslang.compiler;

import dev.oreslang.compiler.GpuLaunchFootprints.Access;
import dev.oreslang.compiler.GpuLaunchFootprints.Limits;
import dev.oreslang.compiler.GpuLaunchFootprints.QueueScope;
import dev.oreslang.compiler.GpuLaunchFootprints.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * G1d integration preflight on the same Java source tree as the launch
 * source-HIR and the host-only capacity verifier.
 *
 * This pass derives device view access modes from the kernel's typed SOURCE
 * signature, not caller-provided expectedAccess flags; then delegates exact
 * extent, overlap, tenant and quota checks to the host-only view preflight.
 *
 * A source plan, source Link and caller-supplied View are NOT authenticated
 * allocation identities. No physical provider, trusted source buffer type
 * environment, generation-scoped allocation loan or completion fence exists.
 * Every Report returned by this class is NON-EXECUTABLE and must not be used
 * to construct runtime launch authority.
 */
public final class GpuKernelSourceCapacityJoinV1 {
    private GpuKernelSourceCapacityJoinV1() { }

    public enum Evidence { SOURCE_JOINED_CAPACITY_SHAPE_ONLY_NOT_EXECUTABLE }

    public record Report(String kernel, String module, int checkedViews,
                         long auditedMemoryOperations, long logicalItems,
                         long minimumBytesPerView, Evidence evidence) {
        public Report {
            Objects.requireNonNull(kernel, "source kernel");
            Objects.requireNonNull(module, "source module");
            if (checkedViews < 0 || auditedMemoryOperations < 0
                    || logicalItems < 0 || minimumBytesPerView < 0
                    || evidence != Evidence.SOURCE_JOINED_CAPACITY_SHAPE_ONLY_NOT_EXECUTABLE)
                throw invalid("unproven kernel join evidence");
        }
        public boolean executable() { return false; }
    }

    /**
     * Stronger declaration-only composition. The caller's explicit parameter
     * declarations are rechecked alongside the source HIR and view shape.
     * This must not be confused with TypeChecker authority, a runtime loan,
     * or a trusted provider-issued allocation identity.
     */
    public record TypedReport(Report capacity,
                              GpuKernelTypedSourceArgsV1.Report sourceTypes) {
        public TypedReport {
            Objects.requireNonNull(capacity, "source-only capacity");
            Objects.requireNonNull(sourceTypes, "source-only host argument declarations");
            if (capacity.executable() || sourceTypes.executable())
                throw invalid("source proof cannot be promoted to a GPU permit");
        }
        public boolean executable() { return false; }
    }

    public static TypedReport auditTypedSource(dev.oreslang.ast.Ast.FunctionDecl caller,
                                              GpuKernelSourceHirV1.Plan plan,
                                              GpuKernelSourceLinkV1.Link link,
                                              QueueScope scope, long logicalOver,
                                              long canonicalN,
                                              Map<Integer, View> viewsByParameterIndex,
                                              Limits limits) {
        var typed = GpuKernelTypedSourceArgsV1.audit(caller, plan, link);
        var capacity = audit(plan, link, scope, logicalOver,
                canonicalN, viewsByParameterIndex, limits);
        return new TypedReport(capacity, typed);
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("GPU-LAUNCH-005: " + reason);
    }

    private static Access access(GpuKernelSourceHirV1.Type type) {
        return switch (type) {
            case BUFFER_READ_F32 -> Access.READ;
            case BUFFER_WRITE_F32 -> Access.WRITE;
            case BUFFER_READ_WRITE_F32 -> Access.READ_WRITE;
            default -> null;
        };
    }

    private static boolean isOne(GpuLaunchSourceHirV1.I64Expr axis) {
        return axis instanceof GpuLaunchSourceHirV1.Constant c && c.value() == 1;
    }

    /**
     * The tail guard proves only gpu.global_id(0) < n, and the capacity check
     * only views against n. Both are sound only when the launch site itself
     * is the logical 1D form over: [n] with unit secondary axes: any extra
     * logical or group axis runs several lanes per index, racing writes to the
     * same out[i]; an over extent other than n is unrelated to the checked
     * capacity; physical grid/block carries no logical extent at all.
     */
    private static void requireCanonicalOneDimensionalGeometry(
            GpuLaunchSourceHirV1.Geometry geometry, long canonicalN) {
        if (geometry.mode() != GpuLaunchSourceHirV1.Mode.LOGICAL_OVER_GROUP)
            throw invalid("canonical 1D kernel requires logical over: [n], not physical grid/block");
        for (int axis = 1; axis < 3; axis++) {
            if (!isOne(geometry.extent().get(axis)) || !isOne(geometry.group().get(axis)))
                throw invalid("canonical 1D kernel indexes only global_id(0); "
                        + "secondary over/group axes must be 1");
        }
        GpuLaunchSourceHirV1.I64Expr extent = geometry.extent().getFirst();
        boolean namedN = extent instanceof GpuLaunchSourceHirV1.Symbol s && s.name().equals("n");
        boolean literalN = extent instanceof GpuLaunchSourceHirV1.Constant c && c.value() == canonicalN;
        if (!namedN && !literalN)
            throw invalid("launch over extent must be the canonical kernel n");
    }

    /**
     * Views are mapped by declared kernel parameter INDEX, not by a caller
     * supplied expected access flag. Scalar arguments must not have views.
     * The caller still must be replaced by a trusted provider snapshot and
     * verified compiler argument type binding before GPU enqueue exists.
     *
     * The canonical scalar n must appear exactly once and either be passed
     * as a source literal equal to canonicalN, or a lexical name exactly n.
     * Lexical names are NOT value/type proofs; this shape rule is intentional.
     */
    public static Report audit(GpuKernelSourceHirV1.Plan plan,
                               GpuKernelSourceLinkV1.Link link,
                               QueueScope scope, long logicalOver, long canonicalN,
                               Map<Integer, View> viewsByParameterIndex,
                               Limits limits) {
        Objects.requireNonNull(plan, "typed kernel source HIR");
        Objects.requireNonNull(link, "kernel source link");
        Objects.requireNonNull(scope, "GPU queue scope");
        Objects.requireNonNull(viewsByParameterIndex, "source GPU view slots");
        Objects.requireNonNull(limits, "GPU view quotas");
        if (plan.evidence() != GpuKernelSourceHirV1.Evidence.TYPED_SOURCE_ONLY_NOT_EXECUTABLE
                || link.evidence() != GpuKernelSourceLinkV1.Evidence.SOURCE_SIGNATURE_ONLY_NOT_EXECUTABLE
                || !plan.symbol().equals(link.kernel())
                || plan.parameters().size() != link.arguments().size()
                || plan.returnType() != GpuKernelSourceHirV1.Type.VOID)
            throw invalid("kernel source plan and launch link identity/ABI mismatch");

        requireCanonicalOneDimensionalGeometry(link.geometry(), canonicalN);
        var tail = GpuKernelTailGuardV1.audit(plan);
        List<GpuKernelCapacityPreflightV1.Argument> bindings = new ArrayList<>();
        Map<String, View> viewsByLexicalName = new HashMap<>();
        HashSet<String> bufferParams = new HashSet<>();
        int boundN = 0;
        for (int i = 0; i < plan.parameters().size(); i++) {
            var param = plan.parameters().get(i);
            var arg = link.arguments().get(i);
            if (arg.expected() != param.type())
                throw invalid("kernel source argument type differs from signature");
            Access expected = access(param.type());
            View view = viewsByParameterIndex.get(i);
            if (expected != null) {
                if (!bufferParams.add(param.name()))
                    throw invalid("duplicate typed kernel buffer parameter");
                if (arg.kind() != GpuKernelSourceLinkV1.ArgumentKind.LEXICAL_NAME_UNTYPED
                        || arg.name() == null || arg.name().isBlank() || view == null)
                    throw invalid("device buffer source argument requires one view");
                View prior = viewsByLexicalName.putIfAbsent(arg.name(), view);
                if (prior != null && !prior.equals(view))
                    throw invalid("one lexical GPU argument maps to contradictory device views");
                bindings.add(new GpuKernelCapacityPreflightV1.Argument(
                        param.name(), expected, view));
            } else {
                if (view != null)
                    throw invalid("scalar kernel parameter cannot carry a device view");
                if (param.name().equals("n")) {
                    ++boundN;
                    if (param.type() != GpuKernelSourceHirV1.Type.I64)
                        throw invalid("canonical n must be an integer scalar");
                    if (arg.kind() == GpuKernelSourceLinkV1.ArgumentKind.I64_LITERAL) {
                        try {
                            if (Long.parseLong(arg.name()) != canonicalN)
                                throw invalid("literal n differs from supplied kernel extent");
                        } catch (NumberFormatException e) {
                            throw invalid("unparseable source n literal");
                        }
                    } else if (arg.kind() != GpuKernelSourceLinkV1.ArgumentKind.LEXICAL_NAME_UNTYPED
                            || !"n".equals(arg.name())) {
                        throw invalid("canonical n lexical name is unresolved or mismatched");
                    }
                }
            }
        }
        if (boundN != 1)
            throw invalid("exactly one canonical integer kernel n is required");
        if (viewsByParameterIndex.size() != bindings.size())
            throw invalid("extra, missing or scalar-index device views");
        var capacity = GpuKernelCapacityPreflightV1.validateF32(
                scope, logicalOver, canonicalN, bindings, limits);
        return new Report(link.kernel(), link.module(), capacity.checkedViews(),
                tail.inspectedMemoryOps(), capacity.logicalItems(),
                capacity.requiredBytesPerView(),
                Evidence.SOURCE_JOINED_CAPACITY_SHAPE_ONLY_NOT_EXECUTABLE);
    }
}
