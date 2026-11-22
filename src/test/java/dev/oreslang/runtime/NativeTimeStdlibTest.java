package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compile and execute actual packaged native Atto time source; source-inspection
 * tests below are not substituted for guest execution.
 */
final class NativeTimeStdlibTest {
    @TempDir Path temp;

    @Test
    void nativeTimeIsCompilerOwnedPackagedAttoAndDoesNotAllowLegacyShadowing()
            throws Exception {
        assertTrue(CoreLibrarySources.packagedSourcePaths().contains("time.atto"));
        assertEquals("@std/time.atto", CoreLibrarySources.unitId("std/time"));
        assertEquals(CoreLibrarySources.unitId("std/time"),
                CoreLibrarySources.unitId("ores.std/time.atto"));
        assertEquals("time.atto", CoreLibrarySources.fileName("std/time"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/time.ores"));
        String source = CoreLibrarySources.source("std/time");
        var parsed = MixedSourceUnit.parse(
                CoreLibrarySources.unitId("std/time"),
                CoreLibrarySources.fileName("std/time"), source);
        assertTrue(parsed.hasOresSource());
        assertFalse(parsed.hasJavaSource());
        assertTrue(source.contains("define pub class Duration"));
        assertTrue(source.contains("define pub class Timer"));
        assertFalse(source.contains("java:"));
        assertFalse(source.contains("Thread.sleep"));
        assertFalse(source.contains("java.util"));
    }

    @Test
    void compiledGuestLinksNativeDurationAndChecksOverflowWithoutHostArithmetic()
            throws Exception {
        Path entry = temp.resolve("time_guest.atto");
        Files.writeString(entry, """
                import module time from "std/time";
                pub routine main(): void {
                  val one = time.seconds(1).expect("one");
                  stdio.println(one.as_ns());
                  stdio.println(one.as_ms_ceil());
                  val tiny = time.microseconds(1).expect("tiny");
                  stdio.println(tiny.as_ms_floor());
                  stdio.println(tiny.as_ms_ceil());
                  stdio.println(time.milliseconds(-1).is_err());
                  stdio.println(time.hours(2562048).is_err());
                  stdio.println(one.checked_add(one).expect("sum").as_ns());
                  return;
                }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(entry,
                IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                output, errors);
        assertTrue(linked.units().containsKey("@std/time.atto"),
                "compiler must link trusted packaged .atto guest source");
        assertEquals(String.join("\n",
                "1000000000", "1000", "0", "1", "true", "true",
                "2000000000"),
                output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void compiledGuestExercisesManualClockDeadlineTimerAndInterval() throws Exception {
        Path entry = temp.resolve("time_scheduler_values.atto");
        Files.writeString(entry, """
                import module time from "std/time";
                pub routine main(): void {
                  val half = time.milliseconds(500).expect("half");
                  let mut manual = time.manual_clock(9, 1000000000);
                  stdio.println(manual.now().ticks_ns);
                  stdio.println(manual.advance(half).expect("advance").ticks_ns);
                  stdio.println(manual.advance(time.nanoseconds(0).expect("zero")).expect("still").ticks_ns);
                  val start = time.instant(9, 1000000000);
                  val later = manual.now();
                  val due = time.deadline_after(start, half).expect("due");
                  stdio.println(due.expired(start).expect("not due"));
                  stdio.println(due.expired(later).expect("due"));
                  stdio.println(due.expired(time.instant(10, 1500000000)).is_err());
                  let mut timer = time.one_shot(due);
                  stdio.println(timer.poll(start).expect("early"));
                  stdio.println(timer.poll(later).expect("fire"));
                  stdio.println(timer.poll(later).expect("once"));
                  stdio.println(timer.did_fire());
                  let mut every = time.interval(later, half);
                  stdio.println(every.poll(later).expect("interval fire"));
                  stdio.println(every.poll(later).expect("interval no double fire"));
                  stdio.println(every.next_due().expect("interval next").ticks_ns);
                  stdio.println(every.cancel());
                  stdio.println(every.next_due().is_err());
                  let mut at_limit = time.manual_clock(20, 9223372036854775807);
                  stdio.println(at_limit.advance(time.nanoseconds(1).expect("one ns")).is_err());
                  stdio.println(at_limit.now().ticks_ns);
                  return;
                }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(entry,
                IsolatePolicy.developer(), ExecutionProfile.serverJit(), output, errors);
        assertTrue(linked.units().containsKey("@std/time.atto"),
                "expected compiled compiler-owned Atto module");
        assertEquals(String.join("\n",
                "1000000000", "1500000000", "1500000000",
                "false", "true", "true", "false", "true", "false",
                "true", "true", "false", "2000000000",
                "true", "true", "true", "9223372036854775807"),
                output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void compiledGuestRunsCanonicalFiftyTwoCaseTimeContract() throws Exception {
        Path fixture = Path.of("examples", "atto-time-contract.atto");
        assertTrue(Files.isRegularFile(fixture), "backend-owned Atto guest fixture missing");
        String source = Files.readString(fixture);
        assertTrue(source.contains("import module time from \"std/time\";"));
        assertFalse(source.contains("import class Duration from"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(fixture,
                IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                output, errors);
        assertTrue(linked.units().containsKey("@std/time.atto"),
                "time fixture must link compiler-owned .atto source");
        String[] lines = output.toString(StandardCharsets.UTF_8)
                .replace("\r", "").strip().split("\n");
        assertEquals(53, lines.length, () -> errors.toString(StandardCharsets.UTF_8));
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < 52; ++i) {
            assertTrue(lines[i].startsWith("TIME-PASS|"),
                    "guest failed or emitted unexpected line " + i + ": " + lines[i]);
            assertTrue(names.add(lines[i]), "duplicate guest check: " + lines[i]);
        }
        assertEquals("TIME-CONTRACT-DONE", lines[52]);
        for (String required : java.util.List.of(
                "TIME-PASS|ceil-sub-ms",
                "TIME-PASS|hours-overflow",
                "TIME-PASS|manual-overflow-atomic",
                "TIME-PASS|clock-mismatch",
                "TIME-PASS|timer-no-repeat",
                "TIME-PASS|interval-coalesce",
                "TIME-PASS|deadline-overflow")) {
            assertTrue(names.contains(required), "guest contract case missing: " + required);
        }
    }

    @Test
    void compilerRejectsImmutableReceiversForMutatingTimeMethods() throws Exception {
        // These must be rejected by the real compiler/ownership checker, not
        // merely by a source-text linter. Compilation of a positive mutable
        // receiver is tested separately in the 52-case linked guest.
        String[] negatives = {
                """
                import module time from "std/time";
                pub routine main(): void {
                  val clock = time.manual_clock(7, 0);
                  clock.advance(time.nanoseconds(1).expect("one"));
                  return;
                }
                """,
                """
                import module time from "std/time";
                pub routine main(): void {
                  val now = time.instant(7, 0);
                  val due = time.deadline_after(now, time.nanoseconds(1).expect("one")).expect("due");
                  val timer = time.one_shot(due);
                  timer.poll(now);
                  return;
                }
                """,
                """
                import module time from "std/time";
                pub routine main(): void {
                  val first = time.instant(7, 0);
                  val repeat = time.interval(first, time.nanoseconds(1).expect("one"));
                  repeat.cancel();
                  return;
                }
                """
        };
        String[] names = { "clock", "timer", "interval" };
        for (int i = 0; i < negatives.length; i++) {
            Path source = temp.resolve("immutable_time_" + names[i] + ".atto");
            Files.writeString(source, negatives[i]);
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(source),
                    "compiler accepted immutable receiver: " + names[i]);
            String diagnostic = rejected.toString().toLowerCase(java.util.Locale.ROOT);
            assertTrue(diagnostic.contains("ownership")
                            && (diagnostic.contains("mutab")
                            || diagnostic.contains("borrow")
                            || diagnostic.contains("read-only")),
                    () -> "negative must fail with borrow/mutable-receiver error, not "
                            + "an unrelated parser or import failure: " + diagnostic);
        }
    }

    @Test
    void projectLocalFakeTimeMustNotShadowCompilerOwnedTime()
            throws Exception {
        Path fake = Files.createDirectories(temp.resolve("std"));
        Files.writeString(fake.resolve("time.atto"),
                "define module time as pub fnc seconds(int n): int { return 7; } end");
        Path entry = temp.resolve("time_shadow.atto");
        Files.writeString(entry, """
                import module time from "std/time";
                pub routine main(): void {
                  stdio.println(time.seconds(1).expect("real").as_ns());
                  return;
                }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(entry,
                IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                output, new ByteArrayOutputStream());
        assertTrue(linked.units().containsKey("@std/time.atto"));
        assertEquals("1000000000", output.toString(StandardCharsets.UTF_8).trim());
    }
}
