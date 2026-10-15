#include "oresactorturn.h"
#include <assert.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

#define PASS(expr) do { ores_turn_status rc = (expr); if (rc != ORES_TURN_OK) { \
    fprintf(stderr, "%s:%d: expected OK for %s, received %d\\n", \
    __FILE__, __LINE__, #expr, (int)rc); assert(rc == ORES_TURN_OK); } } while (0)
#define EXPECT(expr, wanted) do { ores_turn_status rc = (expr); if (rc != (wanted)) { \
    fprintf(stderr, "%s:%d: expected %d for %s, received %d\\n", \
    __FILE__, __LINE__, (int)(wanted), #expr, (int)rc); assert(rc == (wanted)); } } while (0)

static void test_terminal_cleanup_barriers(void) {
    ores_turn_vm *vm = ores_turn_vm_new(128);
    assert(vm);
    ores_actor_cap *actor = NULL;
    ores_source_task_cap *source = NULL;
    ores_cleanup_cap *ticket = NULL;
    ores_turn_lease *lease = NULL;
    PASS(ores_turn_register(vm, 1, 2, 1, &actor));
    PASS(ores_turn_register_source_task(vm, actor, 7, 1, &source));
    PASS(ores_turn_issue_cleanup(vm, source, &ticket));
    PASS(ores_turn_stop(vm, actor));
    EXPECT(ores_turn_claim_source(vm, source, &lease), ORES_TURN_DENIED);
    assert(!lease);
    EXPECT(ores_turn_note_source_settled(vm, source), ORES_TURN_BUSY);
    PASS(ores_turn_claim_cleanup(vm, ticket, &lease));
    assert(lease);
    EXPECT(ores_turn_check_cleanup_effects(vm, lease), ORES_TURN_DENIED);
    EXPECT(ores_turn_complete_cleanup(vm, lease, source), ORES_TURN_DENIED);
    PASS(ores_turn_note_terminal_injected(vm, lease, source));
    EXPECT(ores_turn_note_terminal_injected(vm, lease, source), ORES_TURN_DENIED);
    PASS(ores_turn_check_cleanup_effects(vm, lease));
    PASS(ores_turn_complete_cleanup(vm, lease, source));
    EXPECT(ores_turn_check_cleanup_effects(vm, lease), ORES_TURN_DENIED);
    EXPECT(ores_turn_note_source_settled(vm, source), ORES_TURN_BUSY);
    PASS(ores_turn_release(vm, lease));
    EXPECT(ores_turn_release(vm, lease), ORES_TURN_DENIED);
    PASS(ores_turn_note_source_settled(vm, source));
    PASS(ores_turn_retire_source_task(vm, source));
    PASS(ores_turn_retire(vm, actor));
    PASS(ores_turn_vm_destroy(vm));
}

static void test_force_revocation_cancels_unclaimed_cleanup(void) {
    ores_turn_vm *vm = ores_turn_vm_new(128);
    assert(vm);
    ores_actor_cap *actor = NULL;
    ores_source_task_cap *source = NULL;
    ores_cleanup_cap *ticket = NULL;
    ores_turn_lease *lease = NULL;
    PASS(ores_turn_register(vm, 1, 3, 1, &actor));
    PASS(ores_turn_register_source_task(vm, actor, 8, 1, &source));
    PASS(ores_turn_issue_cleanup(vm, source, &ticket));
    PASS(ores_turn_force_revoke(vm, actor));
    EXPECT(ores_turn_claim_cleanup(vm, ticket, &lease), ORES_TURN_DENIED);
    assert(!lease);
    PASS(ores_turn_note_source_settled(vm, source));
    PASS(ores_turn_retire_source_task(vm, source));
    PASS(ores_turn_retire(vm, actor));
    PASS(ores_turn_vm_destroy(vm));
}

struct foreign_arg {
    ores_turn_vm *vm;
    ores_turn_lease *lease;
    ores_turn_status status;
};
static void *foreign_release(void *raw) {
    struct foreign_arg *a = (struct foreign_arg *)raw;
    a->status = ores_turn_release(a->vm, a->lease);
    return NULL;
}

static void test_wrong_carrier_cannot_release(void) {
    ores_turn_vm *vm = ores_turn_vm_new(64);
    assert(vm);
    ores_actor_cap *actor = NULL;
    ores_turn_lease *lease = NULL;
    PASS(ores_turn_register(vm, 1, 4, 1, &actor));
    PASS(ores_turn_claim_user(vm, actor, &lease));
    struct foreign_arg a = {vm, lease, ORES_TURN_INVALID};
    pthread_t worker;
    assert(pthread_create(&worker, NULL, foreign_release, &a) == 0);
    assert(pthread_join(worker, NULL) == 0);
    EXPECT(a.status, ORES_TURN_WRONG_CARRIER);
    PASS(ores_turn_stop(vm, actor));
    EXPECT(ores_turn_retire(vm, actor), ORES_TURN_BUSY);
    PASS(ores_turn_release(vm, lease));
    PASS(ores_turn_retire(vm, actor));
    PASS(ores_turn_vm_destroy(vm));
}

int main(void) {
    test_terminal_cleanup_barriers();
    test_force_revocation_cancels_unclaimed_cleanup();
    test_wrong_carrier_cannot_release();
    puts("ORES_NATIVE_C_STOP_CLEANUP_DONE_PROBE_OK cases=3");
    return 0;
}
