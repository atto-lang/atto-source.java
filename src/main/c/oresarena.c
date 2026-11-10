#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

/* Buffers have no JVM cleaner: only ActorNativeArena owns/free these bytes.
 * No raw buffer escapes the arena, and its monitor serializes access and free. */
JNIEXPORT jobject JNICALL Java_dev_oreslang_runtime_ActorNativeArena_allocateNative(
        JNIEnv *env, jclass cls, jint bytes) {
    (void)cls;
    if (bytes <= 0) return NULL;
    void *memory = calloc((size_t)bytes, 1);
    if (memory == NULL) return NULL;
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
