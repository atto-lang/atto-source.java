package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Checked, 32-bit actor-local byte-region access for the Graal/JVM direct
 * backing. Uses the same 8-byte-quantized encoding as LLVM CompressedActorRef.
 *
 * <p>A bare 32-bit word is neither an actor capability nor a live-object
 * proof. The block itself enforces actor-turn ownership and retirement on
 * every access. Compiler lowering must additionally prove the referenced
 * object's lifetime/generation and region identity before calling this
 * trusted host bridge. Never pass the word as a Java oop, native pointer,
 * ActorRef, FFI pointer, or cross-actor message reference.</p>
 *
 * <p>The existing Graal backing is a collection of individually owned direct
 * ByteBuffers, not yet one contiguous source-object arena. An offset is
 * meaningful only relative to the specific block passed here.</p>
 */
public final class ActorArenaRef32 {
    private ActorArenaRef32() { }

    public static int at(ActorRuntime.PrivateMemoryBlock block, int byteOffset) {
        Objects.requireNonNull(block, "actor block");
        // An encoded location is not a right to access the block. Its owner
        // checks still execute on every read/write below.
        int encoded = ManagedReferencePolicy.encodeArenaOffset32(byteOffset, block.capacity());
        block.readByte(byteOffset); // enforce actor identity and live-region admission at creation
        return encoded;
    }

    private static int checkedOffset(
            ActorRuntime.PrivateMemoryBlock block, int compressed, int accessBytes) {
        long offset = ManagedReferencePolicy.decodeArenaOffset32(
                compressed, block.capacity()).orElseThrow(
                        () -> new IllegalArgumentException("null actor reference cannot be dereferenced"));
        if (offset > (long) block.capacity() - accessBytes) {
            throw new IndexOutOfBoundsException("actor reference payload exceeds block");
        }
        return Math.toIntExact(offset);
    }

    public static int readInt(ActorRuntime.PrivateMemoryBlock block, int compressed) {
        Objects.requireNonNull(block, "actor block");
        int offset = checkedOffset(block, compressed, Integer.BYTES);
        return block.readInt(offset); // owner, liveness, retirement checks
    }

    public static void writeInt(
            ActorRuntime.PrivateMemoryBlock block, int compressed, int value) {
        Objects.requireNonNull(block, "actor block");
        int offset = checkedOffset(block, compressed, Integer.BYTES);
        block.writeInt(offset, value); // owner, liveness, retirement checks
    }

    public static long readLong(ActorRuntime.PrivateMemoryBlock block, int compressed) {
        Objects.requireNonNull(block, "actor block");
        int offset = checkedOffset(block, compressed, Long.BYTES);
        return block.readLong(offset);
    }

    public static void writeLong(
            ActorRuntime.PrivateMemoryBlock block, int compressed, long value) {
        Objects.requireNonNull(block, "actor block");
        int offset = checkedOffset(block, compressed, Long.BYTES);
        block.writeLong(offset, value);
    }
}
