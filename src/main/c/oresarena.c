#define _POSIX_C_SOURCE 200112L
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* Actor arena storage holds 8-byte tagged slots, 64-bit scalars and
 * compressed32 reference targets at 8-aligned actor offsets. Require NATURAL
 * alignment explicitly instead of trusting calloc: on 32-bit hosts (i386,
 * armv7) alignof(int64_t)/alignof(double) can be 4 and max_align_t varies by
 * libc, so ABI alignment is not a portable guarantee. 16 also keeps 64-bit
 * atomics and future 128-bit lanes naturally aligned. */
#define ORES_ARENA_ALIGNMENT 16
_Static_assert(sizeof(jlong) == 8, "jlong must be 64-bit");
_Static_assert(sizeof(int64_t) == 8, "int64_t must be 64-bit");
_Static_assert((ORES_ARENA_ALIGNMENT % sizeof(int64_t)) == 0, "arena alignment must cover 64-bit fields");

/* Buffers have no JVM cleaner: only ActorNativeArena owns/free these bytes.
 * No raw buffer escapes the arena, and its monitor serializes access and free. */
JNIEXPORT jobject JNICALL Java_dev_oreslang_runtime_ActorNativeArena_allocateNative(
        JNIEnv *env, jclass cls, jint bytes) {
    (void)cls;
    if (bytes <= 0) return NULL;
    void *memory = NULL;
    if (posix_memalign(&memory, ORES_ARENA_ALIGNMENT, (size_t)bytes) != 0 || memory == NULL) {
        return NULL;
    }
    if (((uintptr_t)memory & (ORES_ARENA_ALIGNMENT - 1)) != 0) {
        free(memory);
        return NULL;
    }
    memset(memory, 0, (size_t)bytes); /* zero == NULL slot tag, like calloc */
    jobject buffer = (*env)->NewDirectByteBuffer(env, memory, bytes);
    if (buffer == NULL) free(memory);
    return buffer;
}

JNIEXPORT void JNICALL Java_dev_oreslang_runtime_ActorNativeArena_releaseNative(
        JNIEnv *env, jclass cls, jobject buffer) {
    (void)cls;
    void *memory = (*env)->GetDirectBufferAddress(env, buffer);
    jlong size = (*env)->GetDirectBufferCapacity(env, buffer);
    if (memory == NULL || size <= 0) return;
    /* Volatile writes prevent dead-store elimination before releasing memory. */
    volatile unsigned char *bytes = memory;
    for (jlong i = 0; i < size; i++) bytes[i] = 0;
    free(memory);
}
