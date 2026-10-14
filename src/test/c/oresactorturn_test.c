#define _POSIX_C_SOURCE 200809L
#include "oresactorturn.h"

#include <pthread.h>
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
int main(void) {
    test_one_carrier_stop_and_restart();
    test_forgery_revocation_and_foreign_vm();
    test_one_shot_race();
    test_budget_and_cancellation();
    puts("ORES_NATIVE_ACTOR_TURN_CAPABILITY_OK");
    return 0;
}
