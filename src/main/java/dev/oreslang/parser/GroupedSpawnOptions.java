package dev.oreslang.parser;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Ordered, non-executable spawn syntax. Expression typing and actor schema resolution are separate. */
final class GroupedSpawnOptions<E> {
    enum Section { SCHEDULE, RESOURCES, INIT }
    enum ScheduleKey { MAX_PARALLELISM, AFFINITY, POOL }
    enum ResourceKey { HEAP_SIZE, STACK_SIZE, MAILBOX_CAPACITY }

    sealed interface Entry<E> permits ScheduleEntry, ResourceEntry, InitEntry {
        E expression();
    }

    record ScheduleEntry<E>(ScheduleKey key, E expression) implements Entry<E> {
        ScheduleEntry { Objects.requireNonNull(key); Objects.requireNonNull(expression); }
    }
    record ResourceEntry<E>(ResourceKey key, E expression) implements Entry<E> {
        ResourceEntry { Objects.requireNonNull(key); Objects.requireNonNull(expression); }
    }
    record InitEntry<E>(String field, E expression) implements Entry<E> {
        InitEntry {
            if (field == null || field.isEmpty()) throw new IllegalArgumentException("E_SPAWN_OPTION_KEY");
            Objects.requireNonNull(expression);
        }
    }
    record Group<E>(Section section, List<Entry<E>> entries) {
        Group {
            Objects.requireNonNull(section);
            entries = List.copyOf(entries);
            if (entries.size() > 256) throw new IllegalArgumentException("E_SPAWN_OPTION_LIMIT");
            Set<Object> keys = new HashSet<>();
            for (Entry<E> entry : entries) {
                Object key = switch (entry) {
                    case ScheduleEntry<E> e when section == Section.SCHEDULE -> e.key();
                    case ResourceEntry<E> e when section == Section.RESOURCES -> e.key();
                    case InitEntry<E> e when section == Section.INIT -> e.field();
                    default -> throw new IllegalArgumentException("E_SPAWN_OPTION_KEY");
                };
                if (!keys.add(key)) throw new IllegalArgumentException("E_SPAWN_OPTION_DUPLICATE");
            }
        }
    }
    private final List<Group<E>> groups;

    GroupedSpawnOptions(List<Group<E>> groups) {
        this.groups = List.copyOf(groups);
        var seen = EnumSet.noneOf(Section.class);
        for (Group<E> group : this.groups)
            if (!seen.add(group.section())) throw new IllegalArgumentException("E_SPAWN_OPTION_DUPLICATE");
    }

    List<Group<E>> groups() { return groups; }

    /** Pure traversal order, not evaluation; the lowering pass owns effects and exactly-once execution. */
    List<E> expressionsInSourceOrder() {
        var result = new ArrayList<E>();
        for (Group<E> group : groups)
            for (Entry<E> entry : group.entries()) result.add(entry.expression());
        return List.copyOf(result);
    }
}
