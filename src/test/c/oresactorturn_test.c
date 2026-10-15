#define _POSIX_C_SOURCE 200809L
#include "oresactorturn.h"

#include <pthread.h>
#include <stdatomic.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { \
    fprintf(stderr, "actor turn proof failed at line %d: %s\\n", __LINE__, #x); \
    exit(1); \
} } while (0)

struct wrong_carrier_args {
    ores_turn_vm *vm;
    ores_turn_lease *lease;
    ores_turn_status result;
};
static void *wrong_carrier_release(void *opaque) {
    struct wrong_carrier_args *a = opaque;
    a->result = ores_turn_release(a->vm, a->lease);
    return NULL;
}
static void test_one_carrier_stop_and_restart(void) {
    ores_turn_vm *vm = ores_turn_vm_new(64);
    CHECK(vm != NULL);
    ores_actor_cap *first = NULL;
    ores_turn_lease *lease = NULL, *second = NULL;
    ores_cleanup_cap *ticket = NULL;
    ores_source_task_cap *source = NULL;
    CHECK(ores_turn_register(vm, 12, 34, 1, &first) == ORES_TURN_OK);
    CHECK(ores_turn_register_source_task(vm, first, 1, 1, &source) == ORES_TURN_OK);
    CHECK(ores_turn_issue_cleanup(vm, source, &ticket) == ORES_TURN_OK);
    CHECK(ores_turn_claim_cleanup(vm, ticket, &lease) == ORES_TURN_DENIED);
    CHECK(lease == NULL);
    CHECK(ores_turn_claim_user(vm, first, &lease) == ORES_TURN_OK);
    CHECK(ores_turn_claim_user(vm, first, &second) == ORES_TURN_BUSY);
    CHECK(second == NULL);
    CHECK(ores_turn_stop(vm, first) == ORES_TURN_OK);
    CHECK(ores_turn_stop(vm, first) == ORES_TURN_OK);
    CHECK(ores_turn_claim_user(vm, first, &second) == ORES_TURN_DENIED);
    CHECK(ores_turn_claim_cleanup(vm, ticket, &second) == ORES_TURN_BUSY);
    CHECK(ores_turn_retire(vm, first) == ORES_TURN_BUSY);
    CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_BUSY);

    struct wrong_carrier_args args = { vm, lease, ORES_TURN_OK };
    pthread_t thread;
    CHECK(pthread_create(&thread, NULL, wrong_carrier_release, &args) == 0);
    CHECK(pthread_join(thread, NULL) == 0);
    CHECK(args.result == ORES_TURN_WRONG_CARRIER);
    CHECK(ores_turn_release(vm, lease) == ORES_TURN_OK);
    CHECK(ores_turn_release(vm, lease) == ORES_TURN_DENIED);

    CHECK(ores_turn_claim_cleanup(vm, ticket, &second) == ORES_TURN_OK);
    CHECK(ores_turn_check_cleanup_effects(vm, second) == ORES_TURN_DENIED);
    CHECK(ores_turn_note_terminal_injected(vm, second, source) == ORES_TURN_OK);
    CHECK(ores_turn_check_cleanup_effects(vm, second) == ORES_TURN_OK);
    CHECK(ores_turn_retire_source_task(vm, source) == ORES_TURN_BUSY);
    CHECK(ores_turn_complete_cleanup(vm, second, source) == ORES_TURN_OK);
    CHECK(ores_turn_check_cleanup_effects(vm, second) == ORES_TURN_DENIED);
    CHECK(ores_turn_complete_cleanup(vm, second, source) == ORES_TURN_DENIED);
    CHECK(ores_turn_claim_cleanup(vm, ticket, &lease) == ORES_TURN_DENIED);
    CHECK(lease == NULL);
    CHECK(ores_turn_retire(vm, first) == ORES_TURN_BUSY);
    CHECK(ores_turn_release(vm, second) == ORES_TURN_OK);
    CHECK(ores_turn_retire(vm, first) == ORES_TURN_BUSY);
    CHECK(ores_turn_retire_source_task(vm, source) == ORES_TURN_OK);
    CHECK(ores_turn_retire(vm, first) == ORES_TURN_OK);

    ores_actor_cap *fresh = NULL;
    CHECK(ores_turn_register(vm, 12, 34, 1, &fresh) == ORES_TURN_STALE);
    CHECK(ores_turn_register(vm, 12, 34, 2, &fresh) == ORES_TURN_OK);
    CHECK(ores_turn_claim_user(vm, first, &lease) == ORES_TURN_STALE);
    CHECK(ores_turn_stop(vm, first) == ORES_TURN_STALE);
    CHECK(ores_turn_claim_cleanup(vm, ticket, &lease) == ORES_TURN_STALE);
    CHECK(ores_turn_claim_user(vm, fresh, &lease) == ORES_TURN_OK);
    CHECK(ores_turn_release(vm, lease) == ORES_TURN_OK);
    CHECK(ores_turn_stop(vm, fresh) == ORES_TURN_OK);
    CHECK(ores_turn_retire(vm, fresh) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_OK);
}
static void test_forgery_revocation_and_foreign_vm(void) {
    ores_turn_vm *a = ores_turn_vm_new(64), *b = ores_turn_vm_new(64);
    CHECK(a && b);
    ores_actor_cap *actor = NULL;
    ores_cleanup_cap *ticket = NULL;
    ores_source_task_cap *source = NULL;
    ores_turn_lease *lease = NULL;
    CHECK(ores_turn_register(a, 99, 1, 1, &actor) == ORES_TURN_OK);
    CHECK(ores_turn_claim_user(b, actor, &lease) == ORES_TURN_INVALID);
    char forged[32] = {0};
    CHECK(ores_turn_claim_user(a, (ores_actor_cap *)(void *)forged, &lease)
          == ORES_TURN_INVALID);
    CHECK(ores_turn_register_source_task(a, actor, 1, 1, &source) == ORES_TURN_OK);
    CHECK(ores_turn_issue_cleanup(a, source, &ticket) == ORES_TURN_OK);
    CHECK(ores_turn_force_revoke(a, actor) == ORES_TURN_OK);
    CHECK(ores_turn_claim_cleanup(a, ticket, &lease) == ORES_TURN_DENIED);
    CHECK(ores_turn_claim_user(a, actor, &lease) == ORES_TURN_DENIED);
    CHECK(ores_turn_cancel_cleanup(a, ticket) == ORES_TURN_DENIED);
    CHECK(ores_turn_retire(a, actor) == ORES_TURN_BUSY);
    CHECK(ores_turn_retire_source_task(a, source) == ORES_TURN_OK);
    CHECK(ores_turn_retire(a, actor) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(a) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(b) == ORES_TURN_OK);
}
struct race_args {
    ores_turn_vm *vm;
    ores_cleanup_cap *ticket;
    ores_source_task_cap *source;
    ores_turn_status claim, injection, completion, release;
};
static void *race_cleanup_claim(void *opaque) {
    struct race_args *a = opaque;
    ores_turn_lease *lease = NULL;
    a->claim = ores_turn_claim_cleanup(a->vm, a->ticket, &lease);
    if (a->claim == ORES_TURN_OK) {
        a->injection = ores_turn_note_terminal_injected(a->vm, lease, a->source);
        a->completion = ores_turn_complete_cleanup(a->vm, lease, a->source);
        a->release = ores_turn_release(a->vm, lease);
    }
    return NULL;
}
static void test_one_shot_race(void) {
    ores_turn_vm *vm = ores_turn_vm_new(64);
    CHECK(vm);
    ores_actor_cap *actor = NULL;
    ores_cleanup_cap *ticket = NULL;
    ores_source_task_cap *source = NULL;
    CHECK(ores_turn_register(vm, 44, 55, 11, &actor) == ORES_TURN_OK);
    CHECK(ores_turn_register_source_task(vm, actor, 1, 1, &source) == ORES_TURN_OK);
    CHECK(ores_turn_issue_cleanup(vm, source, &ticket) == ORES_TURN_OK);
    CHECK(ores_turn_stop(vm, actor) == ORES_TURN_OK);
    struct race_args a = {vm, ticket, source,
        ORES_TURN_INVALID, ORES_TURN_INVALID, ORES_TURN_INVALID,
        ORES_TURN_INVALID};
    struct race_args b = {vm, ticket, source,
        ORES_TURN_INVALID, ORES_TURN_INVALID, ORES_TURN_INVALID,
        ORES_TURN_INVALID};
    pthread_t t1, t2;
    CHECK(pthread_create(&t1, NULL, race_cleanup_claim, &a) == 0);
    CHECK(pthread_create(&t2, NULL, race_cleanup_claim, &b) == 0);
    CHECK(pthread_join(t1, NULL) == 0);
    CHECK(pthread_join(t2, NULL) == 0);
    CHECK((a.claim == ORES_TURN_OK) + (b.claim == ORES_TURN_OK) == 1);
    CHECK(a.claim == ORES_TURN_OK || a.claim == ORES_TURN_DENIED
          || a.claim == ORES_TURN_BUSY);
    CHECK(b.claim == ORES_TURN_OK || b.claim == ORES_TURN_DENIED
          || b.claim == ORES_TURN_BUSY);
    if (a.claim == ORES_TURN_OK) {
        CHECK(a.injection == ORES_TURN_OK);
        CHECK(a.completion == ORES_TURN_OK);
        CHECK(a.release == ORES_TURN_OK);
    }
    if (b.claim == ORES_TURN_OK) {
        CHECK(b.injection == ORES_TURN_OK);
        CHECK(b.completion == ORES_TURN_OK);
        CHECK(b.release == ORES_TURN_OK);
    }
    CHECK(ores_turn_retire_source_task(vm, source) == ORES_TURN_OK);
    CHECK(ores_turn_retire(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_OK);
}
static void test_budget_and_cancellation(void) {
    ores_turn_vm *vm = ores_turn_vm_new(3);
    CHECK(vm);
    ores_actor_cap *actor = NULL;
    ores_cleanup_cap *ticket = NULL, *extra = NULL;
    ores_source_task_cap *source = NULL;
    ores_turn_lease *lease = NULL;
    CHECK(ores_turn_register(vm, 7, 8, 1, &actor) == ORES_TURN_OK);
    CHECK(ores_turn_register_source_task(vm, actor, 1, 1, &source) == ORES_TURN_OK);
    CHECK(ores_turn_issue_cleanup(vm, source, &ticket) == ORES_TURN_OK);
    CHECK(ores_turn_issue_cleanup(vm, source, &extra) == ORES_TURN_DENIED);
    CHECK(extra == NULL);
    CHECK(ores_turn_claim_user(vm, actor, &lease) == ORES_TURN_EXHAUSTED);
    CHECK(lease == NULL);
    CHECK(ores_turn_stop(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_retire(vm, actor) == ORES_TURN_BUSY);
    CHECK(ores_turn_cancel_cleanup(vm, ticket) == ORES_TURN_OK);
    CHECK(ores_turn_cancel_cleanup(vm, ticket) == ORES_TURN_DENIED);
    CHECK(ores_turn_retire_source_task(vm, source) == ORES_TURN_OK);
    CHECK(ores_turn_retire(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_OK);
}

/* Two scheduler threads race a user admission and STOP under one VM mutex.
 * Either the claim linearizes first and must safely leave its own carrier,
 * or STOP wins and forbids the claim. No post-STOP second user claim works.
 */
struct race_stop_args {
    ores_turn_vm *vm;
    ores_actor_cap *actor;
    atomic_int *start;
    ores_turn_status status;
    ores_turn_status release;
};
static void *stop_racer(void *opaque) {
    struct race_stop_args *a = opaque;
    while (!atomic_load_explicit(a->start, memory_order_acquire)) sched_yield();
    a->status = ores_turn_stop(a->vm, a->actor);
    return NULL;
}
static void *user_racer(void *opaque) {
    struct race_stop_args *a = opaque;
    while (!atomic_load_explicit(a->start, memory_order_acquire)) sched_yield();
    ores_turn_lease *lease = NULL;
    a->status = ores_turn_claim_user(a->vm, a->actor, &lease);
    if (a->status == ORES_TURN_OK)
        a->release = ores_turn_release(a->vm, lease);
    return NULL;
}
static void test_stop_vs_claim_stress(void) {
    for (unsigned int attempt = 0; attempt < 200; ++attempt) {
        ores_turn_vm *vm = ores_turn_vm_new(16);
        CHECK(vm != NULL);
        ores_actor_cap *actor = NULL;
        CHECK(ores_turn_register(vm, 500, attempt + 1, 1, &actor) == ORES_TURN_OK);
        atomic_int go = ATOMIC_VAR_INIT(0);
        struct race_stop_args stopping = {vm, actor, &go, ORES_TURN_INVALID, ORES_TURN_INVALID};
        struct race_stop_args claiming = {vm, actor, &go, ORES_TURN_INVALID, ORES_TURN_INVALID};
        pthread_t stop_thread, claim_thread;
        CHECK(pthread_create(&stop_thread, NULL, stop_racer, &stopping) == 0);
        CHECK(pthread_create(&claim_thread, NULL, user_racer, &claiming) == 0);
        atomic_store_explicit(&go, 1, memory_order_release);
        CHECK(pthread_join(stop_thread, NULL) == 0);
        CHECK(pthread_join(claim_thread, NULL) == 0);
        CHECK(stopping.status == ORES_TURN_OK);
        CHECK(claiming.status == ORES_TURN_OK || claiming.status == ORES_TURN_DENIED);
        if (claiming.status == ORES_TURN_OK)
            CHECK(claiming.release == ORES_TURN_OK);
        ores_turn_lease *extra = NULL;
        CHECK(ores_turn_claim_user(vm, actor, &extra) == ORES_TURN_DENIED);
        CHECK(extra == NULL);
        CHECK(ores_turn_retire(vm, actor) == ORES_TURN_OK);
        CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_OK);
    }
}

struct race_revoke_args {
    ores_turn_vm *vm;
    ores_actor_cap *actor;
    ores_cleanup_cap *ticket;
    atomic_int *start;
    ores_turn_status status;
    ores_turn_status release;
};
static void *revoke_racer(void *opaque) {
    struct race_revoke_args *a = opaque;
    while (!atomic_load_explicit(a->start, memory_order_acquire)) sched_yield();
    a->status = ores_turn_force_revoke(a->vm, a->actor);
    return NULL;
}
static void *cleanup_racer(void *opaque) {
    struct race_revoke_args *a = opaque;
    while (!atomic_load_explicit(a->start, memory_order_acquire)) sched_yield();
    ores_turn_lease *lease = NULL;
    a->status = ores_turn_claim_cleanup(a->vm, a->ticket, &lease);
    if (a->status == ORES_TURN_OK)
        a->release = ores_turn_release(a->vm, lease);
    return NULL;
}
static void test_revoke_vs_cleanup_stress(void) {
    for (unsigned int attempt = 0; attempt < 200; ++attempt) {
        ores_turn_vm *vm = ores_turn_vm_new(16);
        CHECK(vm != NULL);
        ores_actor_cap *actor = NULL;
        ores_cleanup_cap *ticket = NULL;
        ores_source_task_cap *source = NULL;
        CHECK(ores_turn_register(vm, 600, attempt + 1, 1, &actor) == ORES_TURN_OK);
        CHECK(ores_turn_register_source_task(vm, actor, 1, 1, &source) == ORES_TURN_OK);
        CHECK(ores_turn_issue_cleanup(vm, source, &ticket) == ORES_TURN_OK);
        CHECK(ores_turn_stop(vm, actor) == ORES_TURN_OK);
        atomic_int go = ATOMIC_VAR_INIT(0);
        struct race_revoke_args revoking = {vm, actor, ticket, &go, ORES_TURN_INVALID, ORES_TURN_INVALID};
        struct race_revoke_args claiming = {vm, actor, ticket, &go, ORES_TURN_INVALID, ORES_TURN_INVALID};
        pthread_t revoke_thread, claim_thread;
        CHECK(pthread_create(&revoke_thread, NULL, revoke_racer, &revoking) == 0);
        CHECK(pthread_create(&claim_thread, NULL, cleanup_racer, &claiming) == 0);
        atomic_store_explicit(&go, 1, memory_order_release);
        CHECK(pthread_join(revoke_thread, NULL) == 0);
        CHECK(pthread_join(claim_thread, NULL) == 0);
        CHECK(revoking.status == ORES_TURN_OK);
        CHECK(claiming.status == ORES_TURN_OK || claiming.status == ORES_TURN_DENIED);
        if (claiming.status == ORES_TURN_OK)
            CHECK(claiming.release == ORES_TURN_OK);
        ores_turn_lease *extra = NULL;
        CHECK(ores_turn_claim_cleanup(vm, ticket, &extra) == ORES_TURN_DENIED);
        CHECK(extra == NULL);
        CHECK(ores_turn_retire_source_task(vm, source) == ORES_TURN_OK);
        CHECK(ores_turn_retire(vm, actor) == ORES_TURN_OK);
        CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_OK);
    }
}
/* Rapidly create/join fresh native carriers. Each worker must be able to
 * claim/release its own turn even if the OS recycles pthread_t identifiers.
 * An idle actor may be handed to a new carrier, never simultaneously held
 * by two of them. This also stresses bounded lease lifetime accounting.
 */
struct churn_args {
    ores_turn_vm *vm;
    ores_actor_cap *actor;
    ores_turn_status claim, release;
};
static void *carrier_churn(void *opaque) {
    struct churn_args *a = opaque;
    ores_turn_lease *lease = NULL;
    a->claim = ores_turn_claim_user(a->vm, a->actor, &lease);
    if (a->claim == ORES_TURN_OK)
        a->release = ores_turn_release(a->vm, lease);
    return NULL;
}
static void test_carrier_identity_churn(void) {
    ores_turn_vm *vm = ores_turn_vm_new(600);
    CHECK(vm != NULL);
    ores_actor_cap *actor = NULL;
    CHECK(ores_turn_register(vm, 900, 1, 1, &actor) == ORES_TURN_OK);
    for (unsigned int i = 0; i < 512; ++i) {
        struct churn_args a = {vm, actor, ORES_TURN_INVALID, ORES_TURN_INVALID};
        pthread_t thread;
        CHECK(pthread_create(&thread, NULL, carrier_churn, &a) == 0);
        CHECK(pthread_join(thread, NULL) == 0);
        CHECK(a.claim == ORES_TURN_OK);
        CHECK(a.release == ORES_TURN_OK);
    }
    CHECK(ores_turn_stop(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_retire(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_OK);
}

/* An exited carrier's outstanding lease must NOT become releasable by a
 * replacement OS thread, even when pthread_t storage is recycled. Run in a
 * child because the production API deliberately has no unsafe abandonment
 * shortcut: after the original carrier exits its outstanding lease cannot be
 * retired; the host must instead keep ownership/cleanup within a live carrier.
 * _exit avoids treating that deliberately stranded child VM as an ASan leak.
 */
struct orphan_args {
    ores_turn_vm *vm;
    ores_actor_cap *actor;
    ores_turn_lease *lease;
    ores_turn_status claim, release;
};
static void *orphan_claim(void *opaque) {
    struct orphan_args *a = opaque;
    a->claim = ores_turn_claim_user(a->vm, a->actor, &a->lease);
    return NULL;
}
static void *orphan_reclaim_attempt(void *opaque) {
    struct orphan_args *a = opaque;
    a->release = ores_turn_release(a->vm, a->lease);
    return NULL;
}
static void test_exited_carrier_cannot_be_impersonated(void) {
    pid_t pid = fork();
    CHECK(pid >= 0);
    if (pid == 0) {
        ores_turn_vm *vm = ores_turn_vm_new(8);
        if (!vm) _exit(10);
        ores_actor_cap *actor = NULL;
        if (ores_turn_register(vm, 901, 1, 1, &actor) != ORES_TURN_OK)
            _exit(11);
        struct orphan_args a = {vm, actor, NULL, ORES_TURN_INVALID,
                                ORES_TURN_INVALID};
        pthread_t thread;
        if (pthread_create(&thread, NULL, orphan_claim, &a) != 0)
            _exit(12);
        if (pthread_join(thread, NULL) != 0 || a.claim != ORES_TURN_OK)
            _exit(13);
        if (ores_turn_stop(vm, actor) != ORES_TURN_OK
                || ores_turn_retire(vm, actor) != ORES_TURN_BUSY)
            _exit(14);
        for (unsigned int i = 0; i < 256; ++i) {
            a.release = ORES_TURN_INVALID;
            if (pthread_create(&thread, NULL, orphan_reclaim_attempt, &a) != 0)
                _exit(15);
            if (pthread_join(thread, NULL) != 0
                    || a.release != ORES_TURN_WRONG_CARRIER)
                _exit(16);
        }
        if (ores_turn_retire(vm, actor) != ORES_TURN_BUSY
                || ores_turn_vm_destroy(vm) != ORES_TURN_BUSY)
            _exit(17);
        _exit(0); /* Deliberately stranded lease exists only in this child. */
    }
    int status = 0;
    CHECK(waitpid(pid, &status, 0) == pid);
    CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
}

/* A cleanup ticket may be minted only for a VM-registered, currently live
 * SourceTask identity. The authenticated task handle belongs to one actor
 * incarnation and generation; it cannot cross VMs or be replayed.
 * Registration itself is trusted scheduler-only, not a guest-facing FFI.
 */
struct terminal_identity_args {
    ores_turn_vm *vm;
    ores_turn_lease *lease;
    ores_source_task_cap *source;
    ores_turn_status injection, effects, completion;
};
static void *wrong_carrier_terminal_probe(void *opaque) {
    struct terminal_identity_args *a = opaque;
    a->injection = ores_turn_note_terminal_injected(a->vm, a->lease, a->source);
    a->effects = ores_turn_check_cleanup_effects(a->vm, a->lease);
    a->completion = ores_turn_complete_cleanup(a->vm, a->lease, a->source);
    return NULL;
}
static void test_terminal_signal_requires_real_lease_and_one_shot_ack(void) {
    ores_turn_vm *vm = ores_turn_vm_new(16);
    CHECK(vm);
    ores_actor_cap *actor = NULL;
    ores_source_task_cap *source = NULL, *foreign_source = NULL;
    ores_cleanup_cap *ticket = NULL;
    ores_turn_lease *lease = NULL;
    CHECK(ores_turn_register(vm, 112, 1, 1, &actor) == ORES_TURN_OK);
    CHECK(ores_turn_register_source_task(vm, actor, 1, 1, &source)
          == ORES_TURN_OK);
    CHECK(ores_turn_register_source_task(vm, actor, 2, 1, &foreign_source)
          == ORES_TURN_OK);
    CHECK(ores_turn_issue_cleanup(vm, source, &ticket) == ORES_TURN_OK);
    CHECK(ores_turn_stop(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_claim_cleanup(vm, ticket, &lease) == ORES_TURN_OK);
    CHECK(ores_turn_check_cleanup_effects(vm, lease) == ORES_TURN_DENIED);
    CHECK(ores_turn_complete_cleanup(vm, lease, source) == ORES_TURN_DENIED);
    CHECK(ores_turn_note_terminal_injected(vm, lease, foreign_source)
          == ORES_TURN_DENIED);
    struct terminal_identity_args probe = {vm, lease, source,
            ORES_TURN_INVALID, ORES_TURN_INVALID, ORES_TURN_INVALID};
    pthread_t thread;
    CHECK(pthread_create(&thread, NULL, wrong_carrier_terminal_probe,
                         &probe) == 0);
    CHECK(pthread_join(thread, NULL) == 0);
    CHECK(probe.injection == ORES_TURN_WRONG_CARRIER);
    CHECK(probe.effects == ORES_TURN_DENIED);
    CHECK(probe.completion == ORES_TURN_DENIED);
    CHECK(ores_turn_note_terminal_injected(vm, lease, source)
          == ORES_TURN_OK);
    CHECK(ores_turn_check_cleanup_effects(vm, lease) == ORES_TURN_OK);
    CHECK(ores_turn_complete_cleanup(vm, lease, foreign_source)
          == ORES_TURN_DENIED);
    CHECK(ores_turn_note_terminal_injected(vm, lease, source)
          == ORES_TURN_DENIED);
    CHECK(pthread_create(&thread, NULL, wrong_carrier_terminal_probe,
                         &probe) == 0);
    CHECK(pthread_join(thread, NULL) == 0);
    CHECK(probe.effects == ORES_TURN_WRONG_CARRIER);
    CHECK(probe.completion == ORES_TURN_WRONG_CARRIER);
    CHECK(ores_turn_force_revoke(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_complete_cleanup(vm, lease, source) == ORES_TURN_DENIED);
    CHECK(ores_turn_check_cleanup_effects(vm, lease) == ORES_TURN_DENIED);
    CHECK(ores_turn_release(vm, lease) == ORES_TURN_OK);
    CHECK(ores_turn_check_cleanup_effects(vm, lease) == ORES_TURN_DENIED);
    CHECK(ores_turn_retire_source_task(vm, source) == ORES_TURN_OK);
    CHECK(ores_turn_retire_source_task(vm, foreign_source) == ORES_TURN_OK);
    CHECK(ores_turn_retire(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_OK);
}

/* Consuming a cleanup ticket is not completion. If its physical lease exits
 * without an injected cancellation signal, source/actor retirement must
 * remain blocked until the VM explicitly force-aborts that unfinished task.
 */
/* A successful terminal signal injection is NOT proof cleanup completed.
 * Even after releasing the physical lease, the source task and actor must
 * remain live until an explicit force-abort or successful completion.
 */
/* Interleave FORCE and the physical carrier's cleanup-completion ack.
 * Linearization under the VM mutex ensures completion either commits first
 * or loses to FORCE; in both cases retirement still waits for carrier exit.
 */
struct completion_revoke_args {
    ores_turn_vm *vm;
    ores_cleanup_cap *ticket;
    ores_source_task_cap *source;
    atomic_int ready, go;
    ores_turn_status claim, injected, completion, release;
};
static void *completion_vs_revoke_worker(void *opaque) {
    struct completion_revoke_args *a = opaque;
    ores_turn_lease *lease = NULL;
    a->claim = ores_turn_claim_cleanup(a->vm, a->ticket, &lease);
    if (a->claim != ORES_TURN_OK) {
        atomic_store_explicit(&a->ready, 2, memory_order_release);
        return NULL;
    }
    a->injected = ores_turn_note_terminal_injected(a->vm, lease, a->source);
    atomic_store_explicit(&a->ready, 1, memory_order_release);
    while (!atomic_load_explicit(&a->go, memory_order_acquire)) sched_yield();
    a->completion = ores_turn_complete_cleanup(a->vm, lease, a->source);
    a->release = ores_turn_release(a->vm, lease);
    return NULL;
}
static void test_completion_races_force_revoke(void) {
    for (unsigned int i = 0; i < 200; ++i) {
        ores_turn_vm *vm = ores_turn_vm_new(16);
        CHECK(vm);
        ores_actor_cap *actor = NULL;
        ores_source_task_cap *source = NULL;
        ores_cleanup_cap *ticket = NULL;
        CHECK(ores_turn_register(vm, 116, i + 1, 1, &actor) == ORES_TURN_OK);
        CHECK(ores_turn_register_source_task(vm, actor, 1, 1, &source)
              == ORES_TURN_OK);
        CHECK(ores_turn_issue_cleanup(vm, source, &ticket) == ORES_TURN_OK);
        CHECK(ores_turn_stop(vm, actor) == ORES_TURN_OK);
        struct completion_revoke_args a = {
            .vm = vm, .ticket = ticket, .source = source,
            .ready = ATOMIC_VAR_INIT(0), .go = ATOMIC_VAR_INIT(0),
            .claim = ORES_TURN_INVALID, .injected = ORES_TURN_INVALID,
            .completion = ORES_TURN_INVALID, .release = ORES_TURN_INVALID
        };
        pthread_t thread;
        CHECK(pthread_create(&thread, NULL, completion_vs_revoke_worker, &a) == 0);
        while (!atomic_load_explicit(&a.ready, memory_order_acquire)) sched_yield();
        CHECK(atomic_load_explicit(&a.ready, memory_order_acquire) == 1);
        CHECK(a.claim == ORES_TURN_OK && a.injected == ORES_TURN_OK);
        atomic_store_explicit(&a.go, 1, memory_order_release);
        CHECK(ores_turn_force_revoke(vm, actor) == ORES_TURN_OK);
        CHECK(pthread_join(thread, NULL) == 0);
        CHECK(a.completion == ORES_TURN_OK || a.completion == ORES_TURN_DENIED);
        CHECK(a.release == ORES_TURN_OK);
        CHECK(ores_turn_retire_source_task(vm, source) == ORES_TURN_OK);
        CHECK(ores_turn_retire(vm, actor) == ORES_TURN_OK);
        CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_OK);
    }
}

static void test_injected_but_unfinished_cleanup_blocks_retirement(void) {
    ores_turn_vm *vm = ores_turn_vm_new(16);
    CHECK(vm);
    ores_actor_cap *actor = NULL;
    ores_source_task_cap *source = NULL;
    ores_cleanup_cap *ticket = NULL;
    ores_turn_lease *lease = NULL;
    CHECK(ores_turn_register(vm, 115, 1, 1, &actor) == ORES_TURN_OK);
    CHECK(ores_turn_register_source_task(vm, actor, 1, 1, &source)
          == ORES_TURN_OK);
    CHECK(ores_turn_issue_cleanup(vm, source, &ticket) == ORES_TURN_OK);
    CHECK(ores_turn_stop(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_claim_cleanup(vm, ticket, &lease) == ORES_TURN_OK);
    CHECK(ores_turn_complete_cleanup(vm, lease, source) == ORES_TURN_DENIED);
    CHECK(ores_turn_note_terminal_injected(vm, lease, source) == ORES_TURN_OK);
    CHECK(ores_turn_retire_source_task(vm, source) == ORES_TURN_BUSY);
    CHECK(ores_turn_release(vm, lease) == ORES_TURN_OK);
    CHECK(ores_turn_complete_cleanup(vm, lease, source) == ORES_TURN_DENIED);
    CHECK(ores_turn_retire_source_task(vm, source) == ORES_TURN_BUSY);
    CHECK(ores_turn_retire(vm, actor) == ORES_TURN_BUSY);
    CHECK(ores_turn_force_revoke(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_retire_source_task(vm, source) == ORES_TURN_OK);
    CHECK(ores_turn_retire(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_OK);
}

static void test_undelivered_terminal_blocks_retirement_until_force(void) {
    ores_turn_vm *vm = ores_turn_vm_new(16);
    CHECK(vm);
    ores_actor_cap *actor = NULL;
    ores_source_task_cap *source = NULL;
    ores_cleanup_cap *ticket = NULL;
    ores_turn_lease *lease = NULL;
    CHECK(ores_turn_register(vm, 114, 1, 1, &actor) == ORES_TURN_OK);
    CHECK(ores_turn_register_source_task(vm, actor, 9, 1, &source)
          == ORES_TURN_OK);
    CHECK(ores_turn_issue_cleanup(vm, source, &ticket) == ORES_TURN_OK);
    CHECK(ores_turn_stop(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_claim_cleanup(vm, ticket, &lease) == ORES_TURN_OK);
    CHECK(ores_turn_release(vm, lease) == ORES_TURN_OK);
    CHECK(ores_turn_note_terminal_injected(vm, lease, source)
          == ORES_TURN_DENIED);
    CHECK(ores_turn_retire_source_task(vm, source) == ORES_TURN_BUSY);
    CHECK(ores_turn_retire(vm, actor) == ORES_TURN_BUSY);
    CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_BUSY);
    CHECK(ores_turn_force_revoke(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_retire_source_task(vm, source) == ORES_TURN_OK);
    CHECK(ores_turn_retire(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_OK);
}

static void test_source_task_provenance_and_retirement(void) {
    ores_turn_vm *vm = ores_turn_vm_new(64), *foreign = ores_turn_vm_new(16);
    CHECK(vm && foreign);
    ores_actor_cap *actor = NULL, *other = NULL, *fresh = NULL;
    ores_source_task_cap *source = NULL, *again = NULL, *other_source = NULL;
    ores_cleanup_cap *ticket = NULL, *duplicate = NULL;
    ores_turn_lease *lease = NULL;
    CHECK(ores_turn_register(vm, 777, 10, 1, &actor) == ORES_TURN_OK);
    CHECK(ores_turn_register(vm, 777, 11, 1, &other) == ORES_TURN_OK);
    CHECK(ores_turn_register_source_task(vm, actor, 0, 1, &source)
          == ORES_TURN_INVALID);
    CHECK(ores_turn_register_source_task(vm, actor, 42, 0, &source)
          == ORES_TURN_INVALID);
    CHECK(ores_turn_register_source_task(vm, actor, 42, 7, &source)
          == ORES_TURN_OK);
    CHECK(ores_turn_issue_cleanup(foreign, source, &ticket) == ORES_TURN_INVALID);
    CHECK(ticket == NULL);
    char fake[32] = {0};
    CHECK(ores_turn_issue_cleanup(vm, (ores_source_task_cap *)(void *)fake,
                                  &ticket) == ORES_TURN_INVALID);
    CHECK(ticket == NULL);
    CHECK(ores_turn_register_source_task(vm, actor, 42, 7, &again)
          == ORES_TURN_STALE);
    CHECK(again == NULL);
    CHECK(ores_turn_register_source_task(vm, actor, 42, 8, &again)
          == ORES_TURN_BUSY);
    CHECK(again == NULL);
    CHECK(ores_turn_register_source_task(vm, other, 42, 7, &other_source)
          == ORES_TURN_OK);
    CHECK(ores_turn_issue_cleanup(vm, source, &ticket) == ORES_TURN_OK);
    CHECK(ores_turn_issue_cleanup(vm, source, &duplicate) == ORES_TURN_DENIED);
    CHECK(duplicate == NULL);
    CHECK(ores_turn_retire_source_task(vm, source) == ORES_TURN_BUSY);
    CHECK(ores_turn_claim_user(vm, actor, &lease) == ORES_TURN_OK);
    CHECK(ores_turn_retire_source_task(vm, source) == ORES_TURN_BUSY);
    CHECK(ores_turn_release(vm, lease) == ORES_TURN_OK);
    CHECK(ores_turn_stop(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_register_source_task(vm, actor, 43, 1, &again)
          == ORES_TURN_DENIED);
    CHECK(ores_turn_retire(vm, actor) == ORES_TURN_BUSY);
    CHECK(ores_turn_cancel_cleanup(vm, ticket) == ORES_TURN_OK);
    CHECK(ores_turn_retire_source_task(vm, source) == ORES_TURN_OK);
    CHECK(ores_turn_retire_source_task(vm, source) == ORES_TURN_STALE);
    CHECK(ores_turn_issue_cleanup(vm, source, &duplicate) == ORES_TURN_STALE);
    CHECK(ores_turn_retire(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_register(vm, 777, 10, 2, &fresh) == ORES_TURN_OK);
    CHECK(ores_turn_register_source_task(vm, fresh, 42, 1, &again)
          == ORES_TURN_OK);
    CHECK(ores_turn_issue_cleanup(vm, source, &duplicate) == ORES_TURN_STALE);
    CHECK(ores_turn_issue_cleanup(vm, again, &duplicate) == ORES_TURN_OK);
    CHECK(ores_turn_stop(vm, fresh) == ORES_TURN_OK);
    CHECK(ores_turn_cancel_cleanup(vm, duplicate) == ORES_TURN_OK);
    CHECK(ores_turn_retire_source_task(vm, again) == ORES_TURN_OK);
    CHECK(ores_turn_retire(vm, fresh) == ORES_TURN_OK);
    CHECK(ores_turn_retire(vm, other) == ORES_TURN_DENIED);
    CHECK(ores_turn_stop(vm, other) == ORES_TURN_OK);
    CHECK(ores_turn_retire(vm, other) == ORES_TURN_BUSY);
    CHECK(ores_turn_retire_source_task(vm, other_source) == ORES_TURN_OK);
    CHECK(ores_turn_retire(vm, other) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(foreign) == ORES_TURN_OK);

    ores_turn_vm *small = ores_turn_vm_new(1);
    CHECK(small);
    CHECK(ores_turn_register(small, 2, 2, 1, &actor) == ORES_TURN_OK);
    CHECK(ores_turn_register_source_task(small, actor, 1, 1, &source)
          == ORES_TURN_EXHAUSTED);
    CHECK(source == NULL);
    CHECK(ores_turn_stop(small, actor) == ORES_TURN_OK);
    CHECK(ores_turn_retire(small, actor) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(small) == ORES_TURN_OK);
}

/* A child process inherits raw VM memory and the forking thread's TLS
 * token. It must not be able to release the copied parent's physical lease,
 * mutate its lifecycle, or lock/destroy its copied VM mutex.
 */
static void test_forked_process_rejects_inherited_vm(void) {
    ores_turn_vm *vm = ores_turn_vm_new(16);
    CHECK(vm);
    ores_actor_cap *actor = NULL;
    ores_source_task_cap *source = NULL;
    ores_cleanup_cap *ticket = NULL;
    ores_turn_lease *lease = NULL;
    CHECK(ores_turn_register(vm, 140, 1, 1, &actor) == ORES_TURN_OK);
    CHECK(ores_turn_register_source_task(vm, actor, 1, 1, &source)
          == ORES_TURN_OK);
    CHECK(ores_turn_issue_cleanup(vm, source, &ticket) == ORES_TURN_OK);
    CHECK(ores_turn_claim_user(vm, actor, &lease) == ORES_TURN_OK);
    pid_t pid = fork();
    CHECK(pid >= 0);
    if (pid == 0) {
        ores_turn_lease *second = NULL;
        ores_cleanup_cap *other_ticket = NULL;
        if (ores_turn_release(vm, lease) != ORES_TURN_INVALID) _exit(11);
        if (ores_turn_stop(vm, actor) != ORES_TURN_INVALID) _exit(12);
        if (ores_turn_claim_user(vm, actor, &second) != ORES_TURN_INVALID)
            _exit(13);
        if (second != NULL) _exit(14);
        if (ores_turn_issue_cleanup(vm, source, &other_ticket)
                != ORES_TURN_INVALID) _exit(15);
        if (other_ticket != NULL) _exit(16);
        if (ores_turn_retire_source_task(vm, source) != ORES_TURN_INVALID)
            _exit(17);
        if (ores_turn_vm_destroy(vm) != ORES_TURN_INVALID) _exit(18);
        _exit(0);
    }
    int status = 0;
    CHECK(waitpid(pid, &status, 0) == pid);
    CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    /* Parent's original VM/lease are unchanged by the forked child. */
    CHECK(ores_turn_release(vm, lease) == ORES_TURN_OK);
    CHECK(ores_turn_stop(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_cancel_cleanup(vm, ticket) == ORES_TURN_OK);
    CHECK(ores_turn_retire_source_task(vm, source) == ORES_TURN_OK);
    CHECK(ores_turn_retire(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_OK);
}

static void test_foreign_guesses_and_invalid_inputs(void) {
    CHECK(ores_turn_vm_new(0) == NULL);
    CHECK(ores_turn_vm_new(1000001) == NULL);
    ores_turn_vm *vm = ores_turn_vm_new(16);
    CHECK(vm != NULL);
    ores_actor_cap *actor = NULL;
    ores_turn_lease *lease = NULL;
    ores_cleanup_cap *ticket = NULL;
    ores_source_task_cap *source = NULL;
    CHECK(ores_turn_register(vm, 10, 20, 0, &actor) == ORES_TURN_INVALID);
    CHECK(actor == NULL);
    CHECK(ores_turn_register(vm, 10, 20, 2, &actor) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_BUSY);
    CHECK(ores_turn_register_source_task(vm, actor, 1, 1, &source) == ORES_TURN_OK);
    CHECK(ores_turn_issue_cleanup(vm, source, &ticket) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_BUSY);
    for (unsigned int i = 0; i < 128; ++i) {
        char fake[8] = {0};
        CHECK(ores_turn_claim_user(vm, (ores_actor_cap *)(void *)(fake + (i % 8)), &lease)
              == ORES_TURN_INVALID);
        CHECK(ores_turn_claim_cleanup(vm, (ores_cleanup_cap *)(void *)(fake + (i % 8)), &lease)
              == ORES_TURN_INVALID);
        CHECK(ores_turn_release(vm, (ores_turn_lease *)(void *)(fake + (i % 8)))
              == ORES_TURN_INVALID);
        CHECK(lease == NULL);
    }
    CHECK(ores_turn_cancel_cleanup(vm, ticket) == ORES_TURN_OK);
    CHECK(ores_turn_stop(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_retire_source_task(vm, source) == ORES_TURN_OK);
    CHECK(ores_turn_retire(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_OK);
}

int main(void) {
    test_one_carrier_stop_and_restart();
    test_forgery_revocation_and_foreign_vm();
    test_one_shot_race();
    test_budget_and_cancellation();
    test_stop_vs_claim_stress();
    test_revoke_vs_cleanup_stress();
    test_foreign_guesses_and_invalid_inputs();
    test_carrier_identity_churn();
    test_exited_carrier_cannot_be_impersonated();
    test_source_task_provenance_and_retirement();
    test_terminal_signal_requires_real_lease_and_one_shot_ack();
    test_undelivered_terminal_blocks_retirement_until_force();
    test_injected_but_unfinished_cleanup_blocks_retirement();
    test_completion_races_force_revoke();
    test_forked_process_rejects_inherited_vm();
    puts("ORES_NATIVE_ACTOR_TURN_CAPABILITY_OK");
    return 0;
}
