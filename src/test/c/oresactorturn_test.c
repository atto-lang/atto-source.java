#define _POSIX_C_SOURCE 200809L
#include "oresactorturn.h"

#include <pthread.h>
#include <stdatomic.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

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
    CHECK(ores_turn_register(vm, 12, 34, 1, &first) == ORES_TURN_OK);
    CHECK(ores_turn_issue_cleanup(vm, first, &ticket) == ORES_TURN_OK);
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
    CHECK(ores_turn_claim_cleanup(vm, ticket, &lease) == ORES_TURN_DENIED);
    CHECK(lease == NULL);
    CHECK(ores_turn_retire(vm, first) == ORES_TURN_BUSY);
    CHECK(ores_turn_release(vm, second) == ORES_TURN_OK);
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
    ores_turn_lease *lease = NULL;
    CHECK(ores_turn_register(a, 99, 1, 1, &actor) == ORES_TURN_OK);
    CHECK(ores_turn_claim_user(b, actor, &lease) == ORES_TURN_INVALID);
    char forged[32] = {0};
    CHECK(ores_turn_claim_user(a, (ores_actor_cap *)(void *)forged, &lease)
          == ORES_TURN_INVALID);
    CHECK(ores_turn_issue_cleanup(a, actor, &ticket) == ORES_TURN_OK);
    CHECK(ores_turn_force_revoke(a, actor) == ORES_TURN_OK);
    CHECK(ores_turn_claim_cleanup(a, ticket, &lease) == ORES_TURN_DENIED);
    CHECK(ores_turn_claim_user(a, actor, &lease) == ORES_TURN_DENIED);
    CHECK(ores_turn_cancel_cleanup(a, ticket) == ORES_TURN_DENIED);
    CHECK(ores_turn_retire(a, actor) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(a) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(b) == ORES_TURN_OK);
}
struct race_args {
    ores_turn_vm *vm;
    ores_cleanup_cap *ticket;
    ores_turn_status claim, release;
};
static void *race_cleanup_claim(void *opaque) {
    struct race_args *a = opaque;
    ores_turn_lease *lease = NULL;
    a->claim = ores_turn_claim_cleanup(a->vm, a->ticket, &lease);
    if (a->claim == ORES_TURN_OK) a->release = ores_turn_release(a->vm, lease);
    return NULL;
}
static void test_one_shot_race(void) {
    ores_turn_vm *vm = ores_turn_vm_new(64);
    CHECK(vm);
    ores_actor_cap *actor = NULL;
    ores_cleanup_cap *ticket = NULL;
    CHECK(ores_turn_register(vm, 44, 55, 11, &actor) == ORES_TURN_OK);
    CHECK(ores_turn_issue_cleanup(vm, actor, &ticket) == ORES_TURN_OK);
    CHECK(ores_turn_stop(vm, actor) == ORES_TURN_OK);
    struct race_args a = {vm, ticket, ORES_TURN_INVALID, ORES_TURN_INVALID};
    struct race_args b = {vm, ticket, ORES_TURN_INVALID, ORES_TURN_INVALID};
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
    if (a.claim == ORES_TURN_OK) CHECK(a.release == ORES_TURN_OK);
    if (b.claim == ORES_TURN_OK) CHECK(b.release == ORES_TURN_OK);
    CHECK(ores_turn_retire(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_OK);
}
static void test_budget_and_cancellation(void) {
    ores_turn_vm *vm = ores_turn_vm_new(2);
    CHECK(vm);
    ores_actor_cap *actor = NULL;
    ores_cleanup_cap *ticket = NULL, *extra = NULL;
    ores_turn_lease *lease = NULL;
    CHECK(ores_turn_register(vm, 7, 8, 1, &actor) == ORES_TURN_OK);
    CHECK(ores_turn_issue_cleanup(vm, actor, &ticket) == ORES_TURN_OK);
    CHECK(ores_turn_issue_cleanup(vm, actor, &extra) == ORES_TURN_EXHAUSTED);
    CHECK(extra == NULL);
    CHECK(ores_turn_claim_user(vm, actor, &lease) == ORES_TURN_EXHAUSTED);
    CHECK(lease == NULL);
    CHECK(ores_turn_stop(vm, actor) == ORES_TURN_OK);
    CHECK(ores_turn_retire(vm, actor) == ORES_TURN_BUSY);
    CHECK(ores_turn_cancel_cleanup(vm, ticket) == ORES_TURN_OK);
    CHECK(ores_turn_cancel_cleanup(vm, ticket) == ORES_TURN_DENIED);
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
        CHECK(ores_turn_register(vm, 600, attempt + 1, 1, &actor) == ORES_TURN_OK);
        CHECK(ores_turn_issue_cleanup(vm, actor, &ticket) == ORES_TURN_OK);
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
        CHECK(ores_turn_retire(vm, actor) == ORES_TURN_OK);
        CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_OK);
    }
}
static void test_foreign_guesses_and_invalid_inputs(void) {
    CHECK(ores_turn_vm_new(0) == NULL);
    CHECK(ores_turn_vm_new(1000001) == NULL);
    ores_turn_vm *vm = ores_turn_vm_new(16);
    CHECK(vm != NULL);
    ores_actor_cap *actor = NULL;
    ores_turn_lease *lease = NULL;
    ores_cleanup_cap *ticket = NULL;
    CHECK(ores_turn_register(vm, 10, 20, 0, &actor) == ORES_TURN_INVALID);
    CHECK(actor == NULL);
    CHECK(ores_turn_register(vm, 10, 20, 2, &actor) == ORES_TURN_OK);
    CHECK(ores_turn_vm_destroy(vm) == ORES_TURN_BUSY);
    CHECK(ores_turn_issue_cleanup(vm, actor, &ticket) == ORES_TURN_OK);
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
    puts("ORES_NATIVE_ACTOR_TURN_CAPABILITY_OK");
    return 0;
}
