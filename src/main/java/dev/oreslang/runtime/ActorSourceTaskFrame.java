package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One actor-owned bootstrap frame for a compiler-generated SourceTask.
 *
 * <p>Installed only by an actor-owned OresScheduler.TaskRunner. Admission,
 * suspension and resumption are performed by the scheduler on the owning
 * actor carrier; the frame remains charged while the task is waiting. The
 * underlying payload now contains a bounded subset of compiler-authoritative
 * scalar parameters and lexical locals, NOT arbitrary source values, return
 * PCs, operand roots, or a native-stack replacement. This capability never
 * escapes into guest code.
 */
final class ActorSourceTaskFrame {
    static final int FRAME_CHARGE_BYTES = 64;
    // Four bytes for the canonical encoded-slot length; all other bytes
    // belong to the exact tagged-scalar format in GuestScalarFrameCodec.
    private static final int SCALAR_HEADER_BYTES = 4;
    static final int MAX_SCALAR_VALUES =
            (FRAME_CHARGE_BYTES - SCALAR_HEADER_BYTES) / GuestScalarFrameCodec.SLOT_BYTES;

    private final ActorFrameArena arena;
    private final ExecutionContext execution;
    private ActorFrameArena.FrameRef frame;
    private boolean waiting;
    private int savedScalarBytes;
    private boolean hasSavedScalars;
    /** An actor task cannot alias compiler parameter slots with host snapshots. */
    private enum ScalarOwner { NONE, HOST_SNAPSHOT, SOURCE_PARAMETERS, SOURCE_LOCALS }
    private ScalarOwner scalarOwner = ScalarOwner.NONE;
    // Local placement is stable for the SourceTask lifetime, including await
    // and carrier migration. Neither foreign tasks nor separate Env scopes
    // may manufacture duplicate offsets.
    private record LexicalKey(long scopeEpoch, String name) { }
    private record LexicalSlot(int offset, byte typeTag) { }
    private final Map<LexicalKey, LexicalSlot> lexicalOffsets = new HashMap<>();
    private int nextLexicalOffset;
    private boolean retired;

    ActorSourceTaskFrame(ActorFrameArena arena, ExecutionContext execution) {
        this.arena = Objects.requireNonNull(arena, "arena");
        this.execution = Objects.requireNonNull(execution, "execution");
        if (!arena.allocationDomain().equals(execution.allocationDomain()))
            throw new SecurityException("source task frame belongs to a foreign actor generation");
    }

    ActorFrameArena arena() { return arena; }

    private void requireOwnerTurn() {
        if (ActorRuntime.currentActorFrameArena() != arena
                || OresScheduler.currentExecutionContext() != execution) {
            throw new SecurityException(
                    "actor source frame accessed outside its owning scheduler turn");
        }
    }

    /**
     * A source task always re-enters under its actor's physical dispatch.
     * Duplicate carrier execution is forbidden even if the scheduler admitted
     * a stale queued ticket.
     */
    synchronized void beforeResume() {
        requireOwnerTurn();
        if (retired) throw new IllegalStateException("retired actor task frame");
        if (frame == null) {
            frame = arena.push(execution, FRAME_CHARGE_BYTES);
            return;
        }
        if (!waiting) throw new IllegalStateException("actor task frame resumed twice");
        arena.resume(execution, frame);
        waiting = false;
    }

    /** Called after the task returns Await or Cooperate, before carrier exit. */
    synchronized void suspend() {
        requireOwnerTurn();
        if (retired || frame == null || waiting)
            throw new IllegalStateException("actor task suspension without a running frame");
        arena.suspend(execution, frame);
        waiting = true;
    }

    /**
     * Trusted compiler bridge: persist a small scalar-only continuation
     * snapshot inside the EXISTING 64-byte task lease. No second frame is
     * allocated or charged. Unsupported references and oversized snapshots
     * are rejected, never converted to pointers or lexical closure captures.
     *
     * This entrypoint is used for trusted host snapshots; compiler lexical
     * scalars are separately bound via bindLexicalScalar and cannot alias it.
     */
    synchronized void saveTypedScalars(List<?> values) {
        requireOwnerTurn();
        requireRunningFrame();
        if (scalarOwner == ScalarOwner.SOURCE_PARAMETERS
                || scalarOwner == ScalarOwner.SOURCE_LOCALS) {
            // Compiler parameter state must never be overwritten by a host
            // callback masquerading as an unrelated source continuation.
            throw new IllegalStateException(
                    "scalar frame region is already compiler-owned");
        }
        byte[] encoded = GuestScalarFrameCodec.encode(values);
        if (encoded == null || encoded.length > FRAME_CHARGE_BYTES - SCALAR_HEADER_BYTES) {
            throw new IllegalArgumentException(
                    "source task snapshot requires bounded pointer-free scalars");
        }
        byte[] contents = new byte[FRAME_CHARGE_BYTES];
        ByteBuffer.wrap(contents).order(ByteOrder.BIG_ENDIAN).putInt(encoded.length);
        System.arraycopy(encoded, 0, contents, SCALAR_HEADER_BYTES, encoded.length);
        arena.write(execution, frame, 0, contents);
        savedScalarBytes = encoded.length;
        hasSavedScalars = true;
        scalarOwner = ScalarOwner.HOST_SNAPSHOT;
    }

    /**
     * Compiler-owned source parameter admission. Only call when constructing
     * the lexical parameter Env on the first SourceTask dispatch. Values for
     * this supported scalar subset are decoded from the ACTOR frame before
     * binding, not taken from the ordinary JVM argument List.
     *
     * Returns null for unsupported/oversized argument lists; this is an
     * explicit legacy interpreter fallback, not a partial encoded snapshot.
     */
    synchronized List<Object> bindSourceParameters(List<?> arguments) {
        requireOwnerTurn();
        requireRunningFrame();
        Objects.requireNonNull(arguments, "arguments");
        if (scalarOwner != ScalarOwner.NONE || hasSavedScalars
                || !lexicalOffsets.isEmpty()) {
            arena.poisonFromGuestGuard();
            throw new IllegalStateException("source parameter frame already initialized");
        }
        if (arguments.isEmpty()) return null; // No actual guest values to lower.
        byte[] encoded = GuestScalarFrameCodec.encode(arguments);
        if (encoded == null || encoded.length > FRAME_CHARGE_BYTES - SCALAR_HEADER_BYTES) {
            return null; // Object roots and wider records need typed compiler lowering.
        }
        byte[] contents = new byte[FRAME_CHARGE_BYTES];
        ByteBuffer.wrap(contents).order(ByteOrder.BIG_ENDIAN).putInt(encoded.length);
        System.arraycopy(encoded, 0, contents, SCALAR_HEADER_BYTES, encoded.length);
        arena.write(execution, frame, 0, contents);
        savedScalarBytes = encoded.length;
        hasSavedScalars = true;
        scalarOwner = ScalarOwner.SOURCE_PARAMETERS;
        nextLexicalOffset = SCALAR_HEADER_BYTES + encoded.length;
        return restoreTypedScalars();
    }

    /**
     * On every SourceTask resume authenticate the original argument layout
     * against the actor-owned payload. Fail-stop if it was altered, evicted,
     * or overwritten; never silently rebind from stale JVM values.
     *
     * The bounded local-slot region follows the parameter slots; return PCs
     * and general reference-valued Env bindings remain unlowered.
     */
    synchronized void verifySourceParameters(List<?> arguments) {
        requireOwnerTurn();
        requireRunningFrame();
        Objects.requireNonNull(arguments, "arguments");
        if (scalarOwner == ScalarOwner.NONE || scalarOwner == ScalarOwner.SOURCE_LOCALS) {
            // No parameter image was stored: the source args were empty,
            // referenced guest objects or exceeded the typed frame capacity.
            // Actor-owned lexical scalar slots may still exist independently.
            return;
        }
        if (scalarOwner != ScalarOwner.SOURCE_PARAMETERS) {
            arena.poisonFromGuestGuard();
            throw new IllegalStateException("source parameter frame has foreign owner");
        }
        byte[] expected = GuestScalarFrameCodec.encode(arguments);
        if (expected == null || expected.length != savedScalarBytes) {
            arena.poisonFromGuestGuard();
            throw new IllegalStateException("source parameter frame type/arity changed");
        }
        byte[] copy = arena.read(execution, frame, SCALAR_HEADER_BYTES, savedScalarBytes);
        if (!Arrays.equals(expected, copy)) {
            arena.poisonFromGuestGuard();
            throw new IllegalStateException("source parameter frame bytes changed across await");
        }
        // Force full tag/length validation in addition to binary identity.
        restoreTypedScalars();
    }

    /**
     * M3.2 source lexical scalar slots in the existing 64-byte actor task
     * lease. This is the real source Env read/write path, not a diagnostic
     * copy. Layout is first-binding order with stable (scope epoch,name)
     * keys, after the already-saved scalar parameters.
     *
     * Unsupported values and full slots return false before writing and
     * keep their existing JVM Env semantics. An already managed slot NEVER
     * silently falls back on mutation or foreign task access.
     */
    synchronized boolean bindLexicalScalar(long scopeEpoch, String name, Object value) {
        requireOwnerTurn();
        requireRunningFrame();
        if (scopeEpoch <= 0 || name == null || name.isBlank()) {
            throw new IllegalArgumentException("invalid lexical slot identity");
        }
        if (scalarOwner == ScalarOwner.HOST_SNAPSHOT) return false;
        LexicalKey key = new LexicalKey(scopeEpoch, name);
        if (lexicalOffsets.containsKey(key)) {
            arena.poisonFromGuestGuard();
            throw new IllegalStateException("duplicate actor lexical slot allocation");
        }
        byte[] encoded = encodeOne(value);
        if (encoded == null) return false;
        int offset = nextLexicalOffset == 0 ? SCALAR_HEADER_BYTES : nextLexicalOffset;
        if (offset + GuestScalarFrameCodec.SLOT_BYTES > FRAME_CHARGE_BYTES) {
            return false; // Existing interpreter fallback; not a partial slot.
        }
        arena.write(execution, frame, offset, encoded);
        lexicalOffsets.put(key, new LexicalSlot(offset, encoded[0]));
        nextLexicalOffset = offset + GuestScalarFrameCodec.SLOT_BYTES;
        if (scalarOwner == ScalarOwner.NONE) scalarOwner = ScalarOwner.SOURCE_LOCALS;
        return true;
    }

    synchronized Object readLexicalScalar(long scopeEpoch, String name) {
        requireOwnerTurn();
        requireRunningFrame();
        LexicalSlot local = lexicalOffsets.get(new LexicalKey(scopeEpoch, name));
        if (local == null) {
            arena.poisonFromGuestGuard();
            throw new IllegalStateException("unbound actor lexical scalar slot");
        }
        byte[] encoded = arena.read(execution, frame, local.offset(),
                GuestScalarFrameCodec.SLOT_BYTES);
        if (encoded[0] != local.typeTag()) {
            arena.poisonFromGuestGuard();
            throw new IllegalStateException("actor lexical scalar slot type tag was changed");
        }
        try {
            return GuestScalarFrameCodec.decode(encoded).getFirst();
        } catch (IllegalArgumentException invalidTag) {
            arena.poisonFromGuestGuard();
            throw invalidTag;
        }
    }

    synchronized void writeLexicalScalar(long scopeEpoch, String name, Object value) {
        requireOwnerTurn();
        requireRunningFrame();
        LexicalSlot local = lexicalOffsets.get(new LexicalKey(scopeEpoch, name));
        if (local == null) {
            arena.poisonFromGuestGuard();
            throw new IllegalStateException("missing actor lexical scalar slot");
        }
        byte[] replacement = encodeOne(value);
        if (replacement == null) {
            arena.poisonFromGuestGuard();
            throw new IllegalStateException("owned scalar slot cannot accept references");
        }
        byte[] existing = arena.read(execution, frame, local.offset(),
                GuestScalarFrameCodec.SLOT_BYTES);
        if (existing[0] != local.typeTag()
                || replacement[0] != local.typeTag()) {
            arena.poisonFromGuestGuard();
            throw new IllegalStateException("typed actor lexical slot changed primitive kind");
        }
        arena.write(execution, frame, local.offset(), replacement);
    }

    synchronized int lexicalScalarCount() {
        requireOwnerTurn();
        requireRunningFrame();
        return lexicalOffsets.size();
    }

    private static byte[] encodeOne(Object value) {
        if (value == null) return null;
        return GuestScalarFrameCodec.encode(List.of(value));
    }

    /** Read only after authenticated resume, never from a waiting producer. */
    synchronized List<Object> restoreTypedScalars() {
        requireOwnerTurn();
        requireRunningFrame();
        if (!hasSavedScalars) throw new IllegalStateException(
                "actor source task has no saved typed scalar state");
        byte[] encoded = arena.read(execution, frame, 0, FRAME_CHARGE_BYTES);
        int length = ByteBuffer.wrap(encoded)
                .order(ByteOrder.BIG_ENDIAN).getInt();
        if (length != savedScalarBytes || length < 0
                || length > FRAME_CHARGE_BYTES - SCALAR_HEADER_BYTES
                || length % GuestScalarFrameCodec.SLOT_BYTES != 0) {
            arena.poisonFromGuestGuard();
            throw new IllegalStateException("corrupt actor source task scalar frame layout");
        }
        try {
            return GuestScalarFrameCodec.decode(
                    Arrays.copyOfRange(encoded, SCALAR_HEADER_BYTES,
                            SCALAR_HEADER_BYTES + length));
        } catch (IllegalArgumentException badSlot) {
            arena.poisonFromGuestGuard();
            throw badSlot;
        }
    }

    private void requireRunningFrame() {
        if (retired || waiting || frame == null) {
            throw new IllegalStateException(
                    "source task scalar state requires an active owned frame");
        }
    }

    /**
     * Retire only after the scheduler confirms physical guest-turn quiescence.
     * Cancellation and terminal completion share this exact-once path.
     */
    synchronized void retireAfterQuiescence() {
        if (retired) return;
        arena.cancel(execution);
        frame = null;
        waiting = false;
        savedScalarBytes = 0;
        hasSavedScalars = false;
        scalarOwner = ScalarOwner.NONE;
        lexicalOffsets.clear();
        nextLexicalOffset = 0;
        retired = true;
    }

    synchronized boolean waiting() { return waiting; }
    synchronized boolean retired() { return retired; }
}
