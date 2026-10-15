#define _POSIX_C_SOURCE 200809L
#include "oresactorturn.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdlib.h>

/* An intentionally small native VM admission kernel. This is not the
 * implementation of the Oreslang scheduling policy; that remains in .ores.
 * All entries, tickets, and leases have non-reusable addresses until VM teardown.
 * The trusted VM layer must never expose these pointers to guest source.
 */
struct ores_turn_lease {
    struct ores_turn_lease *next;
    struct ores_actor_cap *actor;
    struct ores_source_task_cap *source; /* NULL for ordinary user turns */
    uint_fast64_t carrier_token;
    bool released;
};
struct ores_actor_cap {
    struct ores_actor_cap *next;
    uint64_t hi, lo, incarnation;
    struct ores_turn_lease *active;
    size_t pending_tickets, pending_sources;
    bool stopped, revoked, retired;
};
struct ores_source_task_cap {
    struct ores_source_task_cap *next;
    struct ores_actor_cap *actor;
    uint64_t task_id, generation;
    bool retired, ticket_issued, ticket_closed, ticket_claimed;
    bool terminal_injected, cleanup_completed, terminal_aborted;
};
struct ores_cleanup_cap {
    struct ores_cleanup_cap *next;
    struct ores_actor_cap *actor;
    struct ores_source_task_cap *source;
    bool consumed, cancelled;
};
struct ores_turn_vm {
    pthread_mutex_t mutex;
    size_t max_objects, used_objects;
    struct ores_actor_cap *actors;
    struct ores_source_task_cap *sources;
    struct ores_cleanup_cap *tickets;
    struct ores_turn_lease *leases;
};

/* pthread_t identifiers can be recycled after a carrier thread exits.
 * A lease instead records a process-unique, lifetime-monotonic token assigned
 * once to each live native thread. Never wrap; exhaustion fails closed.
 * TLS lifetime prevents a replacement thread from inheriting the old token.
 */
static atomic_uint_fast64_t next_carrier_token = ATOMIC_VAR_INIT(1);
static _Thread_local uint_fast64_t thread_carrier_token = 0;

static uint_fast64_t current_carrier_token(void) {
    if (thread_carrier_token != 0) return thread_carrier_token;
    uint_fast64_t next = atomic_load_explicit(&next_carrier_token,
                                              memory_order_relaxed);
    while (next != UINT_FAST64_MAX) {
        if (atomic_compare_exchange_weak_explicit(&next_carrier_token,
                                                  &next, next + 1,
                                                  memory_order_relaxed,
                                                  memory_order_relaxed)) {
            thread_carrier_token = next;
            return next;
        }
    }
    return 0;
}

static bool known_actor(const ores_turn_vm *vm, const ores_actor_cap *actor) {
    for (const ores_actor_cap *p = vm->actors; p; p = p->next) {
        if (p == actor) return true;
    }
    return false;
}
static bool current_actor(const ores_turn_vm *vm, const ores_actor_cap *actor) {
    /* Only call after known_actor(): actor is now safe to dereference. */
    for (const ores_actor_cap *p = vm->actors; p; p = p->next) {
        if (p->hi == actor->hi && p->lo == actor->lo) return p == actor;
    }
    return false;
}
static ores_turn_status require_current(ores_turn_vm *vm,
                                        ores_actor_cap *actor);
static bool known_source(const ores_turn_vm *vm,
                         const ores_source_task_cap *source) {
    for (const ores_source_task_cap *p = vm->sources; p; p = p->next) {
        if (p == source) return true;
    }
    return false;
}
static ores_turn_status require_live_source(ores_turn_vm *vm,
                                            ores_source_task_cap *source) {
    if (!known_source(vm, source)) return ORES_TURN_INVALID;
    ores_turn_status status = require_current(vm, source->actor);
    if (status != ORES_TURN_OK) return status;
    return source->retired ? ORES_TURN_STALE : ORES_TURN_OK;
}
static bool known_ticket(const ores_turn_vm *vm, const ores_cleanup_cap *ticket) {
    for (const ores_cleanup_cap *p = vm->tickets; p; p = p->next) {
        if (p == ticket) return true;
    }
    return false;
}
static bool known_lease(const ores_turn_vm *vm, const ores_turn_lease *lease) {
    for (const ores_turn_lease *p = vm->leases; p; p = p->next) {
        if (p == lease) return true;
    }
    return false;
}
static ores_turn_status require_current(ores_turn_vm *vm, ores_actor_cap *actor) {
    if (!known_actor(vm, actor)) return ORES_TURN_INVALID;
    return current_actor(vm, actor) ? ORES_TURN_OK : ORES_TURN_STALE;
}
static ores_turn_status allocate_lease(ores_turn_vm *vm,
                                       ores_actor_cap *actor,
                                       ores_turn_lease **out) {
    if (actor->active) return ORES_TURN_BUSY;
    if (vm->used_objects == vm->max_objects) return ORES_TURN_EXHAUSTED;
    const uint_fast64_t carrier_token = current_carrier_token();
    if (!carrier_token) return ORES_TURN_EXHAUSTED;
    ores_turn_lease *lease = calloc(1, sizeof(*lease));
    if (!lease) return ORES_TURN_EXHAUSTED;
    lease->actor = actor;
    lease->carrier_token = carrier_token;
    lease->next = vm->leases;
    vm->leases = lease;
    vm->used_objects++;
    actor->active = lease;
    *out = lease;
    return ORES_TURN_OK;
}

ores_turn_vm *ores_turn_vm_new(size_t max_objects) {
    if (max_objects == 0 || max_objects > 1000000) return NULL;
    ores_turn_vm *vm = calloc(1, sizeof(*vm));
    if (!vm) return NULL;
    if (pthread_mutex_init(&vm->mutex, NULL) != 0) {
        free(vm);
        return NULL;
    }
    vm->max_objects = max_objects;
    return vm;
}

ores_turn_status ores_turn_vm_destroy(ores_turn_vm *vm) {
    if (!vm) return ORES_TURN_INVALID;
    pthread_mutex_lock(&vm->mutex);
    for (const ores_actor_cap *a = vm->actors; a; a = a->next) {
        /* VM teardown must not bypass STOP, unresolved cleanup tickets,
         * physical turn quiescence, or final state retirement. Other host
         * threads must already be fenced before the caller attempts destroy.
         */
        if (!a->retired || a->active || a->pending_tickets || a->pending_sources) {
            pthread_mutex_unlock(&vm->mutex);
            return ORES_TURN_BUSY;
        }
    }
    /* Caller must have fenced all other host threads before teardown. */
    ores_actor_cap *a = vm->actors;
    while (a) { ores_actor_cap *next = a->next; free(a); a = next; }
    ores_source_task_cap *s = vm->sources;
    while (s) { ores_source_task_cap *next = s->next; free(s); s = next; }
    ores_cleanup_cap *t = vm->tickets;
    while (t) { ores_cleanup_cap *next = t->next; free(t); t = next; }
    ores_turn_lease *l = vm->leases;
    while (l) { ores_turn_lease *next = l->next; free(l); l = next; }
    pthread_mutex_unlock(&vm->mutex);
    pthread_mutex_destroy(&vm->mutex);
    free(vm);
    return ORES_TURN_OK;
}

ores_turn_status ores_turn_register(ores_turn_vm *vm,
                                   uint64_t hi, uint64_t lo, uint64_t incarnation,
                                   ores_actor_cap **out) {
    if (out) *out = NULL;
    if (!vm || !out || (!hi && !lo) || incarnation == 0) return ORES_TURN_INVALID;
    pthread_mutex_lock(&vm->mutex);
    for (const ores_actor_cap *a = vm->actors; a; a = a->next) {
        if (a->hi == hi && a->lo == lo) {
            if (incarnation <= a->incarnation) {
                pthread_mutex_unlock(&vm->mutex);
                return ORES_TURN_STALE;
            }
            if (!a->retired) {
                pthread_mutex_unlock(&vm->mutex);
                return ORES_TURN_BUSY;
            }
            break; /* actors are newest-first */
        }
    }
    if (vm->used_objects == vm->max_objects) {
        pthread_mutex_unlock(&vm->mutex);
        return ORES_TURN_EXHAUSTED;
    }
    ores_actor_cap *a = calloc(1, sizeof(*a));
    if (!a) {
        pthread_mutex_unlock(&vm->mutex);
        return ORES_TURN_EXHAUSTED;
    }
    a->hi = hi;
    a->lo = lo;
    a->incarnation = incarnation;
    a->next = vm->actors;
    vm->actors = a;
    vm->used_objects++;
    *out = a;
    pthread_mutex_unlock(&vm->mutex);
    return ORES_TURN_OK;
}

ores_turn_status ores_turn_register_source_task(ores_turn_vm *vm,
                                               ores_actor_cap *actor,
                                               uint64_t task_id,
                                               uint64_t generation,
                                               ores_source_task_cap **out) {
    if (out) *out = NULL;
    if (!vm || !out || task_id == 0 || generation == 0) return ORES_TURN_INVALID;
    pthread_mutex_lock(&vm->mutex);
    ores_turn_status status = require_current(vm, actor);
    if (status == ORES_TURN_OK) {
        if (actor->stopped || actor->revoked || actor->retired)
            status = ORES_TURN_DENIED;
        else {
            for (const ores_source_task_cap *s = vm->sources; s; s = s->next) {
                if (s->actor == actor && s->task_id == task_id) {
                    if (generation <= s->generation) status = ORES_TURN_STALE;
                    else if (!s->retired) status = ORES_TURN_BUSY;
                    if (status != ORES_TURN_OK) break;
                }
            }
            if (status == ORES_TURN_OK) {
                if (vm->used_objects == vm->max_objects)
                    status = ORES_TURN_EXHAUSTED;
                else {
                    ores_source_task_cap *s = calloc(1, sizeof(*s));
                    if (!s) status = ORES_TURN_EXHAUSTED;
                    else {
                        s->actor = actor;
                        s->task_id = task_id;
                        s->generation = generation;
                        s->next = vm->sources;
                        vm->sources = s;
                        vm->used_objects++;
                        actor->pending_sources++;
                        *out = s;
                    }
                }
            }
        }
    }
    pthread_mutex_unlock(&vm->mutex);
    return status;
}

ores_turn_status ores_turn_issue_cleanup(ores_turn_vm *vm,
                                         ores_source_task_cap *source,
                                         ores_cleanup_cap **out) {
    if (out) *out = NULL;
    if (!vm || !out) return ORES_TURN_INVALID;
    pthread_mutex_lock(&vm->mutex);
    ores_turn_status status = require_live_source(vm, source);
    if (status == ORES_TURN_OK) {
        ores_actor_cap *actor = source->actor;
        if (actor->stopped || actor->revoked || actor->retired
                || source->ticket_issued) status = ORES_TURN_DENIED;
        else if (vm->used_objects == vm->max_objects) status = ORES_TURN_EXHAUSTED;
        else {
            ores_cleanup_cap *ticket = calloc(1, sizeof(*ticket));
            if (!ticket) status = ORES_TURN_EXHAUSTED;
            else {
                ticket->actor = actor;
                ticket->source = source;
                ticket->next = vm->tickets;
                vm->tickets = ticket;
                vm->used_objects++;
                actor->pending_tickets++;
                source->ticket_issued = true;
                *out = ticket;
            }
        }
    }
    pthread_mutex_unlock(&vm->mutex);
    return status;
}

ores_turn_status ores_turn_retire_source_task(ores_turn_vm *vm,
                                              ores_source_task_cap *source) {
    if (!vm) return ORES_TURN_INVALID;
    pthread_mutex_lock(&vm->mutex);
    ores_turn_status status = require_live_source(vm, source);
    if (status == ORES_TURN_OK) {
        if (source->actor->active || (source->ticket_issued
                                         && !source->ticket_closed)
                || (source->ticket_claimed
                    && !source->cleanup_completed
                    && !source->terminal_aborted))
            status = ORES_TURN_BUSY;
        else {
            source->retired = true;
            source->actor->pending_sources--;
        }
    }
    pthread_mutex_unlock(&vm->mutex);
    return status;
}

ores_turn_status ores_turn_claim_user(ores_turn_vm *vm, ores_actor_cap *actor,
                                     ores_turn_lease **out) {
    if (out) *out = NULL;
    if (!vm || !out) return ORES_TURN_INVALID;
    pthread_mutex_lock(&vm->mutex);
    ores_turn_status status = require_current(vm, actor);
    if (status == ORES_TURN_OK) {
        if (actor->stopped || actor->revoked || actor->retired) status = ORES_TURN_DENIED;
        else status = allocate_lease(vm, actor, out);
    }
    pthread_mutex_unlock(&vm->mutex);
    return status;
}

ores_turn_status ores_turn_stop(ores_turn_vm *vm, ores_actor_cap *actor) {
    if (!vm) return ORES_TURN_INVALID;
    pthread_mutex_lock(&vm->mutex);
    ores_turn_status status = require_current(vm, actor);
    if (status == ORES_TURN_OK) {
        if (actor->retired) status = ORES_TURN_DENIED;
        else actor->stopped = true;
    }
    pthread_mutex_unlock(&vm->mutex);
    return status;
}

ores_turn_status ores_turn_claim_cleanup(ores_turn_vm *vm,
                                        ores_cleanup_cap *ticket,
                                        ores_turn_lease **out) {
    if (out) *out = NULL;
    if (!vm || !out) return ORES_TURN_INVALID;
    pthread_mutex_lock(&vm->mutex);
    ores_turn_status status = ORES_TURN_INVALID;
    if (known_ticket(vm, ticket)) {
        ores_actor_cap *a = ticket->actor; /* authenticated ticket membership */
        status = require_current(vm, a);
        if (status == ORES_TURN_OK) {
            if (ticket->consumed || ticket->cancelled || a->revoked || a->retired
                    || !a->stopped) status = ORES_TURN_DENIED;
            else {
                status = allocate_lease(vm, a, out);
                if (status == ORES_TURN_OK) {
                    (*out)->source = ticket->source;
                    ticket->consumed = true;
                    ticket->source->ticket_closed = true;
                    ticket->source->ticket_claimed = true;
                    a->pending_tickets--;
                }
            }
        }
    }
    pthread_mutex_unlock(&vm->mutex);
    return status;
}

ores_turn_status ores_turn_cancel_cleanup(ores_turn_vm *vm,
                                          ores_cleanup_cap *ticket) {
    if (!vm) return ORES_TURN_INVALID;
    pthread_mutex_lock(&vm->mutex);
    ores_turn_status status = ORES_TURN_INVALID;
    if (known_ticket(vm, ticket)) {
        status = require_current(vm, ticket->actor);
        if (status == ORES_TURN_OK) {
            if (ticket->cancelled || ticket->consumed) status = ORES_TURN_DENIED;
            else {
                ticket->cancelled = true;
                ticket->source->ticket_closed = true;
                ticket->actor->pending_tickets--;
            }
        }
    }
    pthread_mutex_unlock(&vm->mutex);
    return status;
}

/* A terminal signal may be acknowledged only once, by the exact native
 * carrier holding the cleanup lease for this particular SourceTask.
 * The trusted adapter MUST call this only after actual signal injection.
 */
ores_turn_status ores_turn_note_terminal_injected(ores_turn_vm *vm,
                                                 ores_turn_lease *lease,
                                                 ores_source_task_cap *source) {
    if (!vm) return ORES_TURN_INVALID;
    pthread_mutex_lock(&vm->mutex);
    ores_turn_status status = ORES_TURN_INVALID;
    if (known_lease(vm, lease) && known_source(vm, source)) {
        status = require_live_source(vm, source);
        if (status == ORES_TURN_OK) {
            if (lease->released || lease->actor->active != lease
                    || lease->source != source || !lease->actor->stopped
                    || lease->actor->revoked || source->terminal_injected)
                status = ORES_TURN_DENIED;
            else if (current_carrier_token() != lease->carrier_token)
                status = ORES_TURN_WRONG_CARRIER;
            else source->terminal_injected = true;
        }
    }
    pthread_mutex_unlock(&vm->mutex);
    return status;
}

/* Advisory query to the VM adapter, never a guest-level permission.
 * Physical single-flight ownership, STOP and revocation remain mandatory.
 */
ores_turn_status ores_turn_check_cleanup_effects(ores_turn_vm *vm,
                                                 ores_turn_lease *lease) {
    if (!vm) return ORES_TURN_INVALID;
    pthread_mutex_lock(&vm->mutex);
    ores_turn_status status = ORES_TURN_INVALID;
    if (known_lease(vm, lease)) {
        if (lease->released || lease->actor->active != lease
                || !lease->source || !lease->actor->stopped
                || lease->actor->revoked || !lease->source->terminal_injected
                || lease->source->cleanup_completed
                || lease->source->terminal_aborted)
            status = ORES_TURN_DENIED;
        else if (current_carrier_token() != lease->carrier_token)
            status = ORES_TURN_WRONG_CARRIER;
        else status = ORES_TURN_OK;
    }
    pthread_mutex_unlock(&vm->mutex);
    return status;
}

/* VM-only acknowledgement that the guest's actual cleanup has finished.
 * This call is not made at cancellation-injection time, nor implicitly when
 * a lease is released. The same physical carrier must still own the turn.
 */
ores_turn_status ores_turn_complete_cleanup(ores_turn_vm *vm,
                                             ores_turn_lease *lease,
                                             ores_source_task_cap *source) {
    if (!vm) return ORES_TURN_INVALID;
    pthread_mutex_lock(&vm->mutex);
    ores_turn_status status = ORES_TURN_INVALID;
    if (known_lease(vm, lease) && known_source(vm, source)) {
        status = require_live_source(vm, source);
        if (status == ORES_TURN_OK) {
            if (lease->released || lease->actor->active != lease
                    || lease->source != source || !lease->actor->stopped
                    || lease->actor->revoked || !source->terminal_injected
                    || source->cleanup_completed || source->terminal_aborted)
                status = ORES_TURN_DENIED;
            else if (current_carrier_token() != lease->carrier_token)
                status = ORES_TURN_WRONG_CARRIER;
            else source->cleanup_completed = true;
        }
    }
    pthread_mutex_unlock(&vm->mutex);
    return status;
}

ores_turn_status ores_turn_force_revoke(ores_turn_vm *vm, ores_actor_cap *actor) {
    if (!vm) return ORES_TURN_INVALID;
    pthread_mutex_lock(&vm->mutex);
    ores_turn_status status = require_current(vm, actor);
    if (status == ORES_TURN_OK) {
        if (actor->retired) status = ORES_TURN_DENIED;
        else {
            actor->stopped = true;
            actor->revoked = true;
            /* All claimed but unfinished cleanup is abandoned by FORCE,
             * including tasks whose terminal signal was already injected.
             * It may not publish DONE until the physical carrier exits.
             */
            for (ores_source_task_cap *s = vm->sources; s; s = s->next) {
                if (s->actor == actor && s->ticket_claimed
                        && !s->cleanup_completed)
                    s->terminal_aborted = true;
            }
            for (ores_cleanup_cap *t = vm->tickets; t; t = t->next) {
                if (t->actor == actor && !t->consumed && !t->cancelled) {
                    t->cancelled = true;
                    t->source->ticket_closed = true;
                    actor->pending_tickets--;
                }
            }
        }
    }
    pthread_mutex_unlock(&vm->mutex);
    return status;
}

ores_turn_status ores_turn_release(ores_turn_vm *vm, ores_turn_lease *lease) {
    if (!vm) return ORES_TURN_INVALID;
    pthread_mutex_lock(&vm->mutex);
    ores_turn_status status = ORES_TURN_INVALID;
    if (known_lease(vm, lease)) {
        if (lease->released || lease->actor->active != lease) status = ORES_TURN_DENIED;
        else if (current_carrier_token() != lease->carrier_token)
            status = ORES_TURN_WRONG_CARRIER;
        else {
            lease->released = true;
            lease->actor->active = NULL;
            status = ORES_TURN_OK;
        }
    }
    pthread_mutex_unlock(&vm->mutex);
    return status;
}

ores_turn_status ores_turn_retire(ores_turn_vm *vm, ores_actor_cap *actor) {
    if (!vm) return ORES_TURN_INVALID;
    pthread_mutex_lock(&vm->mutex);
    ores_turn_status status = require_current(vm, actor);
    if (status == ORES_TURN_OK) {
        if (actor->retired || !actor->stopped) status = ORES_TURN_DENIED;
        else if (actor->active || actor->pending_tickets || actor->pending_sources)
            status = ORES_TURN_BUSY;
        else actor->retired = true;
    }
    pthread_mutex_unlock(&vm->mutex);
    return status;
}
