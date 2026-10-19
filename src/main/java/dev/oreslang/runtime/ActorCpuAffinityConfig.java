package dev.oreslang.runtime;

import java.util.HashSet;

/** Parses actor CPU-affinity configuration once with duplicate/format checks. */
final class ActorCpuAffinityConfig {
    private ActorCpuAffinityConfig() { }

    static String mode() {
        String mode = System.getProperty("ores.actor.affinity", "lane").trim();
        if (!mode.equals("none") && !mode.equals("lane") && !mode.equals("cpu")) {
            throw new IllegalArgumentException("ores.actor.affinity must be lane, cpu, or none");
        }
        return mode;
    }

    static int[] configuredCpus() {
        if (!mode().equals("cpu")) return new int[0];
        String configured = System.getProperty("ores.actor.cpus", "").trim();
        if (configured.isEmpty()) throw new IllegalArgumentException("CPU affinity requires ores.actor.cpus");
        String[] parts = configured.split(",", -1);
        int[] cpus = new int[parts.length];
        HashSet<Integer> seen = new HashSet<>(parts.length * 2);
        for (int i = 0; i < parts.length; i++) {
            String token = parts[i].trim();
            if (token.isEmpty()) throw new IllegalArgumentException("ores.actor.cpus contains an empty CPU id");
            int cpu;
            try {
                cpu = Integer.parseInt(token);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("invalid CPU id in ores.actor.cpus: " + token, invalid);
            }
            if (cpu < 0) throw new IllegalArgumentException("CPU id must be nonnegative: " + cpu);
            if (!seen.add(cpu)) throw new IllegalArgumentException("duplicate CPU id in ores.actor.cpus: " + cpu);
            cpus[i] = cpu;
        }
        return cpus;
    }

    static int budgetCpuCount(int availableLogicalCpus) {
        if (availableLogicalCpus < 1) throw new IllegalArgumentException("available CPUs must be positive");
        int[] hard = configuredCpus();
        return hard.length == 0 ? availableLogicalCpus : Math.min(availableLogicalCpus, hard.length);
    }
}
