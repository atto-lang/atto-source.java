package dev.oreslang.launcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class StrictAttoCliAdmissionTest {
    @TempDir Path dir;

    @Test void strictProfileIsExplicitAndDoesNotSilentlyUseLinkedCompatibility() throws Exception {
        Path positive = dir.resolve("control.atto");
        Files.writeString(positive, "pub fnc main(): int { return 42; }");
        assertEquals(0, OresMain.exitStatusForArgs(new String[]{"--check-strict-atto",positive.toString()}));
        Path candidate = dir.resolve("checked.atto");
        Files.writeString(candidate, """
            fnc choose(Option<int> x): int {
                return trap! x.unwrap() |> match<int> over
                    on Ok(n): int -> n, on Err(): int -> 42, end;
            }
            pub fnc main(): int { return choose(None); }
            """);
        assertEquals(0, OresMain.exitStatusForArgs(new String[]{"--check",candidate.toString()}));
        assertEquals(1, OresMain.exitStatusForArgs(new String[]{"--check-strict-atto",candidate.toString()}));
        assertEquals(2, OresMain.exitStatusForArgs(new String[]{"--check-strict-atto","--check",candidate.toString()}));
    }
}
