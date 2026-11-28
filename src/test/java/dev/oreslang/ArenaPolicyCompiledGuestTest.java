package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class ArenaPolicyCompiledGuestTest {
    @TempDir Path temp;
    private static String pinned(String path, String expected) throws Exception {
        byte[] bytes=Files.readAllBytes(Path.of(path));
        var digest=MessageDigest.getInstance("SHA-1");
        digest.update(("blob "+bytes.length+"\0").getBytes(StandardCharsets.US_ASCII));
        assertEquals(expected,HexFormat.of().formatHex(digest.digest(bytes)),path);
        return new String(bytes,StandardCharsets.UTF_8);
    }
    private void execute(String name,String source,int expected) throws Exception {
        Path guest=temp.resolve(name+".atto");
        Files.writeString(guest,source);
        LinkedProgramRunner.validate(guest,Map.of());
        var result=LinkedProgramRunner.runWithExitStatus(guest,IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),new ByteArrayOutputStream(),new ByteArrayOutputStream());
        assertEquals(expected,result.exitStatus(),name);
    }
    @Test void exactSharedPolicyAndWrongOracle() throws Exception {
        String source=String.join("\n",
            pinned("stdlib/policy/core/arena_limits.atto","5928e893a2ce1baadb0376a4ccb14f63249f77d4"),
            pinned("stdlib/policy/alloc/arena_lifecycle.atto","e4795c6d4bd561be3d913cdc617d103d0e2828c8"),
            pinned("tests/fixtures/arena-quota-lifecycle-guest.atto","2b1eb28f07b792f2727abe9a5691849f024e304a"));
        String marker="arena_end_offset(0, 0, 1, 0) != 0";
        assertEquals(2,source.split(java.util.regex.Pattern.quote(marker),-1).length);
        execute("policy",source,100);
        execute("wrong",source.replace(marker,"arena_end_offset(0, 0, 1, 0) != 1"),1);
        System.out.println("JAVA_ARENA_POLICY_GUEST_PASS assertions=56 wrong_oracle=1");
    }
}
