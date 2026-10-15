#define _POSIX_C_SOURCE 200809L
#include "oresactorturn.h"

#include <assert.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/wait.h>
#include <unistd.h>

#define OK(call) assert((call) == ORES_TURN_OK)
#define IS(call, status) assert((call) == (status))

/* Native ABI contract fixture, NOT a Java/LLVM SourceTask integration test.
 * A trusted native test driver owns these task handles. It exercises the
 * same registration, STOP, cleanup, physical-release and retirement sequence
 * that a future real VM scheduler adapter must implement.
 */
/* Await/yield may leave the actor's physical slot idle while a SourceTask
 * remains alive. Never retire it merely because no native lease is active.
 * The scheduler must explicitly witness terminal SourceTask quiescence.
 */
static void test_suspended_task_retirement_barrier(void) {
    ores_turn_vm *vm = ores_turn_vm_new(16);
    assert(vm);
    ores_actor_cap *actor = NULL;
    ores_source_task_cap *source = NULL;
    OK(ores_turn_register(vm, 9, 1, 1, &actor));
    OK(ores_turn_register_source_task(vm, actor, 1, 1, &source));
    IS(ores_turn_retire_source_task(vm, source), ORES_TURN_BUSY);
    IS(ores_turn_retire(vm, actor), ORES_TURN_DENIED);
    /* Pretend the real scheduler terminal callback is now complete. */
    OK(ores_turn_note_source_settled(vm, source));
    ores_cleanup_cap *unused_ticket = NULL;
    IS(ores_turn_issue_cleanup(vm, source, &unused_ticket),
       ORES_TURN_DENIED);
    assert(unused_ticket == NULL);
    OK(ores_turn_retire_source_task(vm, source));
    OK(ores_turn_stop(vm, actor));
    OK(ores_turn_retire(vm, actor));
    OK(ores_turn_vm_destroy(vm));
}

/* Same actor, recycled scheduler task ID: a new logical lifetime must
 * advance the generation, while previously retired SourceTask handles
 * remain stale and cannot mint cleanup tickets or become live again.
 */
static void test_settled_task_generation_reuse(void) {
    ores_turn_vm *vm = ores_turn_vm_new(24);
    assert(vm);
    ores_actor_cap *actor = NULL;
    ores_source_task_cap *old = NULL, *fresh = NULL, *probe = NULL;
    ores_cleanup_cap *ticket = NULL;
    OK(ores_turn_register(vm, 8, 1, 1, &actor));
    OK(ores_turn_register_source_task(vm, actor, 19, 9, &old));
    IS(ores_turn_register_source_task(vm, actor, 19, 9, &probe),
       ORES_TURN_STALE);
    assert(probe == NULL);
    IS(ores_turn_register_source_task(vm, actor, 19, 10, &probe),
       ORES_TURN_BUSY);
    assert(probe == NULL);
    IS(ores_turn_retire_source_task(vm, old), ORES_TURN_BUSY);
    OK(ores_turn_note_source_settled(vm, old));
    OK(ores_turn_retire_source_task(vm, old));
    IS(ores_turn_issue_cleanup(vm, old, &ticket), ORES_TURN_STALE);
    assert(ticket == NULL);
    IS(ores_turn_register_source_task(vm, actor, 19, 8, &probe),
       ORES_TURN_STALE);
    OK(ores_turn_register_source_task(vm, actor, 19, 10, &fresh));
    IS(ores_turn_note_source_settled(vm, old), ORES_TURN_STALE);
    IS(ores_turn_retire_source_task(vm, fresh), ORES_TURN_BUSY);
    OK(ores_turn_note_source_settled(vm, fresh));
    OK(ores_turn_retire_source_task(vm, fresh));
    OK(ores_turn_stop(vm, actor));
    OK(ores_turn_retire(vm, actor));
    OK(ores_turn_vm_destroy(vm));
}

static void test_cleanup_completes_after_injection(void) {
    ores_turn_vm *vm = ores_turn_vm_new(32);
    assert(vm);
    ores_actor_cap *actor = NULL;
    ores_source_task_cap *source = NULL;
    ores_cleanup_cap *ticket = NULL;
    ores_turn_lease *lease = NULL;
    OK(ores_turn_register(vm, 10, 1, 1, &actor));
    OK(ores_turn_register_source_task(vm, actor, 100, 1, &source));
    OK(ores_turn_issue_cleanup(vm, source, &ticket));
    IS(ores_turn_claim_cleanup(vm, ticket, &lease), ORES_TURN_DENIED);
    OK(ores_turn_stop(vm, actor));
    IS(ores_turn_claim_user(vm, actor, &lease), ORES_TURN_DENIED);
    OK(ores_turn_claim_cleanup(vm, ticket, &lease));
    IS(ores_turn_complete_cleanup(vm, lease, source), ORES_TURN_DENIED);
    OK(ores_turn_note_terminal_injected(vm, lease, source));
    IS(ores_turn_retire_source_task(vm, source), ORES_TURN_BUSY);
    OK(ores_turn_check_cleanup_effects(vm, lease));
    OK(ores_turn_complete_cleanup(vm, lease, source));
    IS(ores_turn_complete_cleanup(vm, lease, source), ORES_TURN_DENIED);
    IS(ores_turn_check_cleanup_effects(vm, lease), ORES_TURN_DENIED);
    OK(ores_turn_release(vm, lease));
    IS(ores_turn_retire_source_task(vm, source), ORES_TURN_BUSY);
    OK(ores_turn_note_source_settled(vm, source));
    IS(ores_turn_note_source_settled(vm, source), ORES_TURN_DENIED);
    OK(ores_turn_retire_source_task(vm, source));
    OK(ores_turn_retire(vm, actor));
    OK(ores_turn_vm_destroy(vm));
}

static void test_incomplete_cleanup_requires_force_abort(void) {
    ores_turn_vm *vm = ores_turn_vm_new(32);
    assert(vm);
    ores_actor_cap *actor = NULL;
    ores_source_task_cap *source = NULL;
    ores_cleanup_cap *ticket = NULL;
    ores_turn_lease *lease = NULL;
    OK(ores_turn_register(vm, 11, 1, 1, &actor));
    OK(ores_turn_register_source_task(vm, actor, 100, 1, &source));
    OK(ores_turn_issue_cleanup(vm, source, &ticket));
    OK(ores_turn_stop(vm, actor));
    OK(ores_turn_claim_cleanup(vm, ticket, &lease));
    OK(ores_turn_note_terminal_injected(vm, lease, source));
    OK(ores_turn_release(vm, lease));
    IS(ores_turn_retire_source_task(vm, source), ORES_TURN_BUSY);
    IS(ores_turn_retire(vm, actor), ORES_TURN_BUSY);
    OK(ores_turn_force_revoke(vm, actor));
    IS(ores_turn_retire_source_task(vm, source), ORES_TURN_BUSY);
    OK(ores_turn_note_source_settled(vm, source));
    IS(ores_turn_note_source_settled(vm, source), ORES_TURN_DENIED);
    OK(ores_turn_retire_source_task(vm, source));
    OK(ores_turn_retire(vm, actor));
    OK(ores_turn_vm_destroy(vm));
}

struct race {
    ores_turn_vm *vm;
    ores_actor_cap *actor;
    ores_source_task_cap *source;
    ores_cleanup_cap *ticket;
    atomic_int start;
    ores_turn_status claim, injection, completion, release;
};
static void *cleanup_worker(void *opaque) {
    struct race *r = opaque;
    ores_turn_lease *lease = NULL;
    while (!atomic_load_explicit(&r->start, memory_order_acquire)) { }
    r->claim = ores_turn_claim_cleanup(r->vm, r->ticket, &lease);
    if (r->claim == ORES_TURN_OK) {
        r->injection = ores_turn_note_terminal_injected(r->vm, lease, r->source);
        if (r->injection == ORES_TURN_OK) {
            r->completion = ores_turn_complete_cleanup(r->vm, lease, r->source);
        }
        r->release = ores_turn_release(r->vm, lease);
    }
    return NULL;
}
static void *revoke_worker(void *opaque) {
    struct race *r = opaque;
    while (!atomic_load_explicit(&r->start, memory_order_acquire)) { }
    OK(ores_turn_force_revoke(r->vm, r->actor));
    return NULL;
}
static void test_force_vs_cleanup_linearization(void) {
    for (unsigned i = 0; i < 1000; ++i) {
        ores_turn_vm *vm = ores_turn_vm_new(32);
        assert(vm);
        ores_actor_cap *actor = NULL;
        ores_source_task_cap *source = NULL;
        ores_cleanup_cap *ticket = NULL;
        OK(ores_turn_register(vm, 12, i + 1, 1, &actor));
        OK(ores_turn_register_source_task(vm, actor, 100, 1, &source));
        OK(ores_turn_issue_cleanup(vm, source, &ticket));
        OK(ores_turn_stop(vm, actor));
        struct race r = {.vm = vm, .actor = actor, .source = source,
            .ticket = ticket, .start = ATOMIC_VAR_INIT(0),
            .claim = ORES_TURN_INVALID, .injection = ORES_TURN_INVALID,
            .completion = ORES_TURN_INVALID, .release = ORES_TURN_INVALID};
        pthread_t a, b;
        assert(pthread_create(&a, NULL, cleanup_worker, &r) == 0);
        assert(pthread_create(&b, NULL, revoke_worker, &r) == 0);
        atomic_store_explicit(&r.start, 1, memory_order_release);
        assert(pthread_join(a, NULL) == 0);
        assert(pthread_join(b, NULL) == 0);
        assert(r.claim == ORES_TURN_OK || r.claim == ORES_TURN_DENIED);
        if (r.claim == ORES_TURN_OK) {
            assert(r.injection == ORES_TURN_OK || r.injection == ORES_TURN_DENIED);
            assert(r.completion == ORES_TURN_OK || r.completion == ORES_TURN_DENIED
                   || r.completion == ORES_TURN_INVALID);
            assert(r.release == ORES_TURN_OK);
        }
        IS(ores_turn_retire_source_task(vm, source), ORES_TURN_BUSY);
        OK(ores_turn_note_source_settled(vm, source));
        IS(ores_turn_note_source_settled(vm, source), ORES_TURN_DENIED);
        OK(ores_turn_retire_source_task(vm, source));
        OK(ores_turn_retire(vm, actor));
        OK(ores_turn_vm_destroy(vm));
    }
}
static void test_forked_child_cannot_use_parent_vm(void) {
    ores_turn_vm *vm = ores_turn_vm_new(16);
    assert(vm);
    ores_actor_cap *actor = NULL;
    ores_turn_lease *lease = NULL;
    OK(ores_turn_register(vm, 13, 1, 1, &actor));
    OK(ores_turn_claim_user(vm, actor, &lease));
    pid_t pid = fork();
    assert(pid >= 0);
    if (pid == 0) {
        if (ores_turn_release(vm, lease) != ORES_TURN_INVALID) _exit(11);
        if (ores_turn_stop(vm, actor) != ORES_TURN_INVALID) _exit(12);
        if (ores_turn_vm_destroy(vm) != ORES_TURN_INVALID) _exit(13);
        _exit(0);
    }
    int status = 0;
    assert(waitpid(pid, &status, 0) == pid);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    OK(ores_turn_release(vm, lease));
    OK(ores_turn_stop(vm, actor));
    OK(ores_turn_retire(vm, actor));
    OK(ores_turn_vm_destroy(vm));
}

int main(void) {
    test_suspended_task_retirement_barrier();
    test_settled_task_generation_reuse();
    test_cleanup_completes_after_injection();
    test_incomplete_cleanup_requires_force_abort();
    test_force_vs_cleanup_linearization();
    test_forked_child_cannot_use_parent_vm();
    puts("ORES_NATIVE_SCHEDULER_BOUNDARY_CONTRACT_OK");
    return 0;
}
