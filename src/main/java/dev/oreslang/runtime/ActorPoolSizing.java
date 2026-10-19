package dev.oreslang.runtime;

/**
 * Optional sizing plan for the three disjoint actor carrier domains.
 * PRIVATE and UNTRUSTED each get their own equally sized physical pool;
 * SHARED receives the remainder. CONTROL/root/ordinary Task pools are
 * separate, so this is not a global CPU or NUMA allocation authority.
 */
final class ActorPoolSizing {
    record Plan(int privateWorkers, int sharedWorkers, int untrustedWorkers,
                int actorWorkerBudget) {
        int total() { return privateWorkers + sharedWorkers + untrustedWorkers; }
    }

    private ActorPoolSizing() { }

    static Plan cpuBudgeted(int availableLogicalCpus, Integer requestedShared) {
        if (availableLogicalCpus < 1) {
            throw new IllegalArgumentException("available CPUs must be positive");
        }
        // Keep capacity for hosted/control/Task work on larger machines.
        // Three isolated actor domains each require at least one carrier, so
        // machines with fewer than 4 logical CPUs cannot avoid oversubscription.
        int budget = Math.max(3, availableLogicalCpus - 1);
        int desiredPrivate = Math.max(1, (budget / 4) + (budget % 4 == 0 ? 0 : 1));
        int shared = requestedShared == null ? budget - 2 * desiredPrivate : requestedShared;
        if (shared < 1 || shared > budget - 2) {
            throw new IllegalArgumentException(
                    "shared actor parallelism exceeds CPU-budgeted actor carrier limit");
        }
        int privateWorkers = requestedShared == null
                ? desiredPrivate
                : Math.max(1, (budget - shared) / 2);
        Plan result = new Plan(privateWorkers, shared, privateWorkers, budget);
        if (result.total() > budget) throw new IllegalStateException("actor pool budget exceeded");
        return result;
    }
}
