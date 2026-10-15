#ifndef ORES_ACTOR_TURN_H
#define ORES_ACTOR_TURN_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* EXPERIMENTAL trusted-VM ABI, NOT a guest FFI and NOT a finalized wire ABI.
 * These opaque pointers must never be materialized as guest Oreslang values.
 * The VM authenticates each handle by pointer membership WITHOUT dereferencing
 * a caller-provided handle, then fences actor ID and incarnation.
 * Objects remain allocated until the VM is safely destroyed to prevent ABA.
 */
typedef struct ores_turn_vm ores_turn_vm;
typedef struct ores_actor_cap ores_actor_cap;
typedef struct ores_source_task_cap ores_source_task_cap;
typedef struct ores_cleanup_cap ores_cleanup_cap;
typedef struct ores_turn_lease ores_turn_lease;

typedef enum ores_turn_status {
    ORES_TURN_OK = 0,
    ORES_TURN_DENIED = 1,
    ORES_TURN_STALE = 2,
    ORES_TURN_BUSY = 3,
    ORES_TURN_INVALID = 4,
    ORES_TURN_EXHAUSTED = 5,
    ORES_TURN_WRONG_CARRIER = 6
} ores_turn_status;

/* max_objects bounds lifetime allocations of caps, tickets and leases.
 * Destruction requires every actor to be stopped, physically quiescent and
 * retired with no outstanding cleanup tickets. VM must fence ALL other
 * callers before destruction; pointers from a destroyed VM may be recycled
 * by the allocator and are never valid against a later VM instance.
 * Physical turn leases use per-thread monotonic tokens, not reusable pthread_t
 * values. Abandoned leases from exited carriers must NOT be claimed/released
 * by a replacement thread; the VM must quiesce actual guest frames before
 * final retirement.
 */
ores_turn_vm *ores_turn_vm_new(size_t max_objects);
ores_turn_status ores_turn_vm_destroy(ores_turn_vm *vm);

ores_turn_status ores_turn_register(ores_turn_vm *vm,
                                   uint64_t actor_hi, uint64_t actor_lo,
                                   uint64_t incarnation,
                                   ores_actor_cap **out_actor);

/* These APIs are callable ONLY by the trusted scheduler / VM adapter.
 * A guest cannot register/forge a SourceTask or issue a cleanup capability.
 * The caller MUST register an actual scheduler-owned SourceTask when it is
 * created, not a guest-supplied task ID. Task generation must be monotonic.
 * A SourceTask cannot be retired before its cleanup ticket settles and the
 * actor's physical lease exits. An actor cannot retire with live SourceTasks.
 */
ores_turn_status ores_turn_register_source_task(ores_turn_vm *vm,
                                               ores_actor_cap *actor,
                                               uint64_t task_id,
                                               uint64_t task_generation,
                                               ores_source_task_cap **out_source);
ores_turn_status ores_turn_issue_cleanup(ores_turn_vm *vm,
                                         ores_source_task_cap *source,
                                         ores_cleanup_cap **out_ticket);
ores_turn_status ores_turn_retire_source_task(ores_turn_vm *vm,
                                             ores_source_task_cap *source);
ores_turn_status ores_turn_claim_user(ores_turn_vm *vm,
                                     ores_actor_cap *actor,
                                     ores_turn_lease **out_lease);
ores_turn_status ores_turn_stop(ores_turn_vm *vm, ores_actor_cap *actor);
ores_turn_status ores_turn_claim_cleanup(ores_turn_vm *vm,
                                        ores_cleanup_cap *ticket,
                                        ores_turn_lease **out_lease);
ores_turn_status ores_turn_cancel_cleanup(ores_turn_vm *vm,
                                          ores_cleanup_cap *ticket);
/* Only the trusted VM adapter may acknowledge that a terminal signal has
 * physically been injected into the claimed SourceTask's continuation.
 * Cancellation requested is NOT equivalent to signal injection. Acknowledging
 * an injection is one-shot and must occur on the original lease carrier.
 * Cleanup effects are denied until the VM has acknowledged actual injection.
 */
ores_turn_status ores_turn_note_terminal_injected(ores_turn_vm *vm,
                                                 ores_turn_lease *lease,
                                                 ores_source_task_cap *source);
ores_turn_status ores_turn_check_cleanup_effects(ores_turn_vm *vm,
                                                 ores_turn_lease *lease);
ores_turn_status ores_turn_force_revoke(ores_turn_vm *vm,
                                       ores_actor_cap *actor);
ores_turn_status ores_turn_release(ores_turn_vm *vm,
                                   ores_turn_lease *lease);
ores_turn_status ores_turn_retire(ores_turn_vm *vm, ores_actor_cap *actor);

#ifdef __cplusplus
}
#endif
#endif
