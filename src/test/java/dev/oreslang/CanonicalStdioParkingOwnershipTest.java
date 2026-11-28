package dev.oreslang;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class CanonicalStdioParkingOwnershipTest {
    @Test void ordinaryUnannotatedDirectCallsAdmitAtOwnershipStage() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(
            "fnc plain(): void { stdio.out.write(\"x\"); process.stdout.write(\"y\"); return; }")));
    }
    @Test void directAndTransitiveBorrowedParametersReject() {
        for(String body : new String[]{"stdio.out.write(\"x\");", "middle();"}) {
            var error=assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(Parser.parse(
                "fnc bad(borrow int x): void { "+body+" return; } "
                +"fnc middle(): void { leaf(); return; } fnc leaf(): void { process.stdout.write(\"x\"); return; }")));
            assertTrue(error.getMessage().contains("borrow"), error.getMessage());
        }
    }
}
