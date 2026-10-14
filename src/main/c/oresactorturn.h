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
 * Destruction requires no physically active lease. VM must fence all callers.
 */
ores_turn_vm *ores_turn_vm_new(size_t max_objects);
ores_turn_status ores_turn_vm_destroy(ores_turn_vm *vm);

ores_turn_status ores_turn_register(ores_turn_vm *vm,
                                   uint64_t actor_hi, uint64_t actor_lo,
                                   uint64_t incarnation,
                                   ores_actor_cap **out_actor);

/* These APIs are callable ONLY by the trusted scheduler / VM adapter.
 * A raw Oreslang caller cannot issue a capability or claim a native carrier.
 */
ores_turn_status ores_turn_issue_cleanup(ores_turn_vm *vm,
                                         ores_actor_cap *actor,
                                         ores_cleanup_cap **out_ticket);
ores_turn_status ores_turn_claim_user(ores_turn_vm *vm,
                                     ores_actor_cap *actor,
                                     ores_turn_lease **out_lease);
ores_turn_status ores_turn_stop(ores_turn_vm *vm, ores_actor_cap *actor);
ores_turn_status ores_turn_claim_cleanup(ores_turn_vm *vm,
                                        ores_cleanup_cap *ticket,
                                        ores_turn_lease **out_lease);
ores_turn_status ores_turn_cancel_cleanup(ores_turn_vm *vm,
                                          ores_cleanup_cap *ticket);
ores_turn_status ores_turn_force_revoke(ores_turn_vm *vm,
                                       ores_actor_cap *actor);
ores_turn_status ores_turn_release(ores_turn_vm *vm,
                                   ores_turn_lease *lease);
ores_turn_status ores_turn_retire(ores_turn_vm *vm, ores_actor_cap *actor);

#ifdef __cplusplus
}
#endif
#endif
