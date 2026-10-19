package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Adversarial guest admission tests for the bounded Region<Motion> V0 quota. */
final class RegionMotionQuotaGuestTest {
    private static String execute(String source) throws Exception {
        TypeChecker.check(Parser.parse(source));
        var output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, source, "region-quota.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String manyRegions(int count, boolean closeFirst) {
        var code = new StringBuilder("pub fnc main(): void {\n");
        for (int i = 0; i < count; i++) {
            if (i == 8 && closeFirst) code.append("r0.close();\n");
            code.append("let r").append(i)
                .append(" = Region.new<Motion>(2, 2, 1);\n");
        }
        code.append("stdio.stdout.write(r").append(count - 1)
            .append(".size());\nreturn;\n}\n");
        return code.toString();
    }

    @Test void ninthUnclosedRegionRejected() {
        assertThrows(RuntimeException.class, () -> execute(manyRegions(9, false)));
    }

    @Test void explicitCloseMakesRoomForAnotherRegion() throws Exception {
        assertEquals("0", execute(manyRegions(9, true)));
    }

    @Test void eachGuestContextStartsWithIndependentQuota() throws Exception {
        assertEquals("0", execute(manyRegions(8, false)));
        assertEquals("0", execute(manyRegions(8, false)));
    }
}
