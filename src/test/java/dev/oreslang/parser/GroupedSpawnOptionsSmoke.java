package dev.oreslang.parser;

import java.util.ArrayList;
import java.util.List;
import static dev.oreslang.parser.GroupedSpawnOptions.*;

final class GroupedSpawnOptionsSmoke {
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
    private static void deny(String code, Runnable action) {
        try { action.run(); } catch (IllegalArgumentException e) {
            check(code.equals(e.getMessage()));
            return;
        }
        throw new AssertionError("expected " + code);
    }
    public static void main(String[] args) {
        var init = new ArrayList<Entry<String>>();
        init.add(new InitEntry<>("seed", "first"));
        init.add(new InitEntry<>("name", "second"));
        var groups = new ArrayList<Group<String>>();
        groups.add(new Group<>(Section.INIT, init));
        groups.add(new Group<>(Section.SCHEDULE, List.of(new ScheduleEntry<>(ScheduleKey.AFFINITY, "third"))));
        groups.add(new Group<>(Section.RESOURCES, List.of(new ResourceEntry<>(ResourceKey.HEAP_SIZE, "fourth"))));
        var options = new GroupedSpawnOptions<>(groups);
        init.clear(); groups.clear();
        check(options.expressionsInSourceOrder().equals(List.of("first", "second", "third", "fourth")));
        check(options.groups().get(0).entries().size() == 2);
        try { options.groups().clear(); throw new AssertionError(); }
        catch (UnsupportedOperationException expected) { }
        try { options.groups().get(0).entries().clear(); throw new AssertionError(); }
        catch (UnsupportedOperationException expected) { }
        check(new GroupedSpawnOptions<String>(List.of()).expressionsInSourceOrder().isEmpty());
        deny("E_SPAWN_OPTION_DUPLICATE", () -> new GroupedSpawnOptions<>(List.of(
            new Group<String>(Section.INIT, List.of()), new Group<String>(Section.INIT, List.of()))));
        deny("E_SPAWN_OPTION_DUPLICATE", () -> new Group<>(Section.SCHEDULE, List.of(
            new ScheduleEntry<>(ScheduleKey.POOL, "a"), new ScheduleEntry<>(ScheduleKey.POOL, "b"))));
        deny("E_SPAWN_OPTION_DUPLICATE", () -> new Group<>(Section.INIT, List.of(
            new InitEntry<>("seed", "a"), new InitEntry<>("seed", "b"))));
        deny("E_SPAWN_OPTION_KEY", () -> new Group<>(Section.SCHEDULE, List.of(
            new ResourceEntry<>(ResourceKey.STACK_SIZE, "x"))));
        deny("E_SPAWN_OPTION_KEY", () -> new InitEntry<>("", "x"));
        var boundary = new ArrayList<Entry<Integer>>();
        for (int i = 0; i < 256; i++) boundary.add(new InitEntry<>("f" + i, i));
        check(new Group<>(Section.INIT, boundary).entries().size() == 256);
        boundary.add(new InitEntry<>("overflow", 257));
        deny("E_SPAWN_OPTION_LIMIT", () -> new Group<>(Section.INIT, boundary));
        System.out.println("GROUPED_SPAWN_OPTION_SHAPE_PASS order, immutability, duplicates, section kinds, 256/257 bound");
    }
}
