package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Final compiler-side join of Task ownership provenance and resolved semantic
 * Send admission. This is still not executable lowering authority: ownership
 * release is explicitly tied to physical TaskScope completion.
 */
public final class TaskAdmissionProof {
    public enum ReleaseBarrier {
        PHYSICAL_TASK_SCOPE_DONE
    }

    public record AdmittedTransfer(
            String name,
            TaskTransferProvenance.TransferKind ownership,
            Types.Type resolvedType) {
        public AdmittedTransfer {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(ownership, "ownership");
            Objects.requireNonNull(resolvedType, "resolvedType");
        }
    }

    private final Ast.Program programIdentity;
    private final IdentityHashMap<Object, List<AdmittedTransfer>> byBoundary =
            new IdentityHashMap<>();
    private final ReleaseBarrier releaseBarrier;

    private TaskAdmissionProof(
            Ast.Program programIdentity,
            ReleaseBarrier releaseBarrier) {
        this.programIdentity = Objects.requireNonNull(programIdentity, "programIdentity");
        this.releaseBarrier = Objects.requireNonNull(releaseBarrier, "releaseBarrier");
    }

    public static TaskAdmissionProof combine(
            TaskTransferProvenance ownership,
            TaskSemanticAdmission semantic) {
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(semantic, "semantic");
        if (!ownership.sealed() || !semantic.sealed()) {
            throw new IllegalArgumentException(
                    "Task admission requires sealed compiler proofs");
        }
        if (ownership.programIdentity() == null
                || semantic.programIdentity() == null
                || ownership.programIdentity()
                        != semantic.programIdentity()) {
            throw new IllegalArgumentException(
                    "Task semantic and ownership proofs must come from the exact same program instance");
        }
        if (ownership.boundaryCount() != semantic.boundaryCount()
                || ownership.transferCount() != semantic.entryCount()) {
            throw new IllegalArgumentException(
                    "Task semantic and ownership proof cardinality differs");
        }

        TaskAdmissionProof result =
                new TaskAdmissionProof(
                        ownership.programIdentity(),
                        ReleaseBarrier.PHYSICAL_TASK_SCOPE_DONE);
        ownership.forEachBoundary((boundary, transfers) -> {
            List<TaskSemanticAdmission.Entry> semantics =
                    semantic.entriesForIdentity(boundary);
            if (semantics.size() != transfers.size()) {
                throw new IllegalArgumentException(
                        "Task semantic proof missing exact ownership boundary");
            }
            ArrayList<AdmittedTransfer> admitted =
                    new ArrayList<>(transfers.size());
            for (int i = 0; i < transfers.size(); i++) {
                TaskTransferProvenance.Transfer transfer = transfers.get(i);
                TaskSemanticAdmission.Entry resolved = semantics.get(i);
                if (!transfer.name().equals(resolved.name())) {
                    throw new IllegalArgumentException(
                            "Task semantic/ownership transfer identity mismatch");
                }
                admitted.add(new AdmittedTransfer(
                        transfer.name(),
                        transfer.kind(),
                        resolved.type()));
            }
            result.byBoundary.put(boundary, List.copyOf(admitted));
        });
        if (result.byBoundary.size() != ownership.boundaryCount()) {
            throw new IllegalArgumentException(
                    "Task admission did not join every exact boundary");
        }
        return result;
    }

    private List<AdmittedTransfer> transfersForIdentity(Object boundary) {
        List<AdmittedTransfer> result = byBoundary.get(boundary);
        return result == null ? List.of() : result;
    }

    public List<AdmittedTransfer> requireFor(
            Ast.Program program,
            Ast.Expr boundary) {
        if (program != programIdentity) {
            throw new IllegalStateException(
                    "Task admission proof belongs to a different program instance");
        }
        Objects.requireNonNull(boundary, "boundary");
        List<AdmittedTransfer> result = byBoundary.get(boundary);
        if (result == null) {
            throw new IllegalStateException(
                    "Task lowering requires an exact joined admission proof");
        }
        return result;
    }

    public List<AdmittedTransfer> transfersFor(Ast.Stmt boundary) {
        return transfersForIdentity(boundary);
    }

    public List<AdmittedTransfer> transfersFor(Ast.Expr boundary) {
        return transfersForIdentity(boundary);
    }

    public int boundaryCount() { return byBoundary.size(); }

    public int transferCount() {
        int count = 0;
        for (List<AdmittedTransfer> entries : byBoundary.values()) {
            count += entries.size();
        }
        return count;
    }

    public ReleaseBarrier releaseBarrier() {
        return releaseBarrier;
    }
}
