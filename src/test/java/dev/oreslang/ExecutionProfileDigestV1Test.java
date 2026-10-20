package dev.oreslang;

import dev.oreslang.compiler.ExecutionProfileDigestV1;
import dev.oreslang.compiler.ExecutionProfileDigestV1.Profile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ExecutionProfileDigestV1Test {
    private static Profile base() {
        return new Profile(1, "reference", "model-ref-1", "ores-semantic-1",
                "portable", "portable", "core-model-v1", "strict", true,
                "preallocated", true, null, List.of());
    }

    @Test
    void matchesSharedProfileVectors() {
        Profile p=base();
        assertEquals(127, ExecutionProfileDigestV1.canonicalBytes(p).length);
        assertEquals("8440c7b53174d2d0dc6a6ff6e1fda662e409f4e21f6ce647eea38b87b3ff9695",
                ExecutionProfileDigestV1.digest(p));

        Profile ieee=new Profile(1,p.backendId(),p.backendSemanticVersion(),p.compilerSemanticVersion(),
                p.targetTriple(),p.dataLayout(),p.runtimeAbi(),"ieee",true,
                p.allocation(),p.freestanding(),null,List.of());
        assertEquals("f157ad0ec51a93c6f89e12512af934acea2d179f07c577aa0399a51dffc4275e",
                ExecutionProfileDigestV1.digest(ieee));

        Profile feature=new Profile(1,p.backendId(),p.backendSemanticVersion(),p.compilerSemanticVersion(),
                p.targetTriple(),p.dataLayout(),p.runtimeAbi(),p.numericMode(),true,
                p.allocation(),p.freestanding(),null,List.of("fma"));
        assertEquals("dad02ababd944847f5fac379b1c9e8f924233df789a46f436b70e48f3881d55b",
                ExecutionProfileDigestV1.digest(feature));
    }

    @Test
    void noncanonicalFeatureOrderFailsClosed() {
        Profile p=base();
        Profile bad=new Profile(1,p.backendId(),p.backendSemanticVersion(),p.compilerSemanticVersion(),
                p.targetTriple(),p.dataLayout(),p.runtimeAbi(),p.numericMode(),true,
                p.allocation(),p.freestanding(),null,List.of("sse4.2","fma"));
        assertEquals("MODEL_NONCANONICAL_EXECUTION_PROFILE",
                assertThrows(IllegalArgumentException.class,
                        () -> ExecutionProfileDigestV1.digest(bad)).getMessage());
    }
}
