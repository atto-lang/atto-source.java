#include "oresactorturn.h"
#include <jni.h>
#include <stdint.h>
#include <limits.h>

/* VM-private JNI boundary: no library entrypoint is exposed to guest values.
 * The package-private Java owner serializes handle lifecycle and prevents
 * native VM destruction while Java operations or carriers are outstanding.
 * JNI pointer casts are exclusively for JVM-owned handles from this library.
 */
#define JCLASS dev_oreslang_runtime_NativeActorTurnKernel
#define METHOD(name) Java_dev_oreslang_runtime_NativeActorTurnKernel_##name
#define PTR(type, value) ((type *)(uintptr_t)(uint64_t)(value))
#define HANDLE(value) ((jlong)(uint64_t)(uintptr_t)(value))

JNIEXPORT jlong JNICALL METHOD(nVmNew)(JNIEnv *env, jclass cls, jlong max) {
    (void)env; (void)cls;
    if (max <= 0 || (uint64_t)max > (uint64_t)SIZE_MAX) return 0;
    return HANDLE(ores_turn_vm_new((size_t)max));
}
JNIEXPORT jint JNICALL METHOD(nVmDestroy)(JNIEnv *env, jclass cls, jlong vm) {
    (void)env; (void)cls;
    if (!vm) return ORES_TURN_INVALID;
    return (jint)ores_turn_vm_destroy(PTR(ores_turn_vm, vm));
}
JNIEXPORT jlong JNICALL METHOD(nRegisterActor)(JNIEnv *env, jclass cls,
        jlong vm, jlong hi, jlong lo, jlong incarnation) {
    (void)env; (void)cls;
    ores_actor_cap *out = NULL;
    if (!vm || incarnation <= 0) return 0;
    if (ores_turn_register(PTR(ores_turn_vm, vm), (uint64_t)hi, (uint64_t)lo,
                (uint64_t)incarnation, &out) != ORES_TURN_OK) return 0;
    return HANDLE(out);
}
JNIEXPORT jlong JNICALL METHOD(nRegisterSource)(JNIEnv *env, jclass cls,
        jlong vm, jlong actor, jlong id, jlong generation) {
    (void)env; (void)cls;
    ores_source_task_cap *out = NULL;
    if (!vm || !actor || id <= 0 || generation <= 0) return 0;
    if (ores_turn_register_source_task(PTR(ores_turn_vm, vm),
                PTR(ores_actor_cap, actor), (uint64_t)id,
                (uint64_t)generation, &out) != ORES_TURN_OK) return 0;
    return HANDLE(out);
}
JNIEXPORT jlong JNICALL METHOD(nClaimSource)(JNIEnv *env, jclass cls,
        jlong vm, jlong source) {
    (void)env; (void)cls;
    ores_turn_lease *out = NULL;
    if (!vm || !source) return 0;
    if (ores_turn_claim_source(PTR(ores_turn_vm, vm),
                PTR(ores_source_task_cap, source), &out) != ORES_TURN_OK) return 0;
    return HANDLE(out);
}
JNIEXPORT jint JNICALL METHOD(nRelease)(JNIEnv *env, jclass cls,
        jlong vm, jlong lease) {
    (void)env; (void)cls;
    if (!vm || !lease) return ORES_TURN_INVALID;
    return (jint)ores_turn_release(PTR(ores_turn_vm, vm), PTR(ores_turn_lease, lease));
}
JNIEXPORT jint JNICALL METHOD(nSourceSettled)(JNIEnv *env, jclass cls,
        jlong vm, jlong source) {
    (void)env; (void)cls;
    if (!vm || !source) return ORES_TURN_INVALID;
    return (jint)ores_turn_note_source_settled(PTR(ores_turn_vm, vm),
            PTR(ores_source_task_cap, source));
}
JNIEXPORT jint JNICALL METHOD(nRetireSource)(JNIEnv *env, jclass cls,
        jlong vm, jlong source) {
    (void)env; (void)cls;
    if (!vm || !source) return ORES_TURN_INVALID;
    return (jint)ores_turn_retire_source_task(PTR(ores_turn_vm, vm),
            PTR(ores_source_task_cap, source));
}
JNIEXPORT jint JNICALL METHOD(nStop)(JNIEnv *env, jclass cls,
        jlong vm, jlong actor) {
    (void)env; (void)cls;
    if (!vm || !actor) return ORES_TURN_INVALID;
    return (jint)ores_turn_stop(PTR(ores_turn_vm, vm), PTR(ores_actor_cap, actor));
}
JNIEXPORT jint JNICALL METHOD(nRetireActor)(JNIEnv *env, jclass cls,
        jlong vm, jlong actor) {
    (void)env; (void)cls;
    if (!vm || !actor) return ORES_TURN_INVALID;
    return (jint)ores_turn_retire(PTR(ores_turn_vm, vm), PTR(ores_actor_cap, actor));
}
