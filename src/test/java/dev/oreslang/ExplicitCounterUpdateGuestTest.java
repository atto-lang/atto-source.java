package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class ExplicitCounterUpdateGuestTest {
    @TempDir Path temp;

    @Test @Timeout(30)
    void actualCompiledLoopGuests() throws Exception {
        String[] bodies = {
            "let x=40; set x++; set ++x; return x;",
            "let x=44; set x--; set --x; return x;",
            "let x=20; set x+=2; set x-=1; set x*=2; return x;",
            "let x=32; for let i=0; i<10; set i++ { set x++; } return x;",
            "let x=32; for (let i=10; i>0; set --i) { set x++; } return x;",
            "let x=32; for (let i=0; i<10; set i+=2) { set x+=2; } return x;",
            "let n=0; for (let i=0; i<10; set i++) { if i==2 { continue; } fi if i==5 { break; } fi set n+=10; } return n+2;",
            "let x=42; for (let i=0; i<0; set i++) { set x=0; } return x;",
            "let x=40; set! (x=x+2); return x;"
        };
        for (int i=0; i<bodies.length; i++) {
            Path entry = temp.resolve("positive-"+i+".atto");
            Files.writeString(entry, "pub fnc main(): int { "+bodies[i]+" }");
            assertNotNull(LinkedProgramRunner.validate(entry), bodies[i]);
            var result = LinkedProgramRunner.runWithExitStatus(entry, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), new ByteArrayOutputStream(), new ByteArrayOutputStream());
            assertEquals(42, result.exitStatus(), bodies[i]);
        }
        System.out.println("EXPLICIT_COUNTER_JAVA_GUEST_PASS positives="+bodies.length);
    }

    @Test void rejectsInvalidUpdatesBeforeExecution() throws Exception {
        String[] bodies = {
            "const int x=40; set x++; return x;",
            "set missing++; return 42;",
            "let bool x=true; set x++; return 42;",
            "let x=40; set x+ =2; return x;",
            "let x=40; set ++x++; return x;",
            "for let i=0; i<1; set i++ {} return i;"
        };
        for (int i=0; i<bodies.length; i++) {
            Path entry = temp.resolve("negative-"+i+".atto");
            Files.writeString(entry, "pub fnc main(): int { "+bodies[i]+" }");
            assertThrows(RuntimeException.class, () -> LinkedProgramRunner.validate(entry), bodies[i]);
        }
        System.out.println("EXPLICIT_COUNTER_JAVA_REJECT_PASS negatives="+bodies.length);
    }
}
