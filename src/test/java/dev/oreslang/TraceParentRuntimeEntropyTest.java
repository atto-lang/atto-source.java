package dev.oreslang;
import dev.oreslang.runtime.TraceParent;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;
final class TraceParentRuntimeEntropyTest {
    @Test void independentRuntimeRootsAndChildrenRoundTrip() {
        var traces = new HashSet<String>();
        for (int i=0;i<256;i++) {
            var root=TraceParent.newRoot(true);
            var child=root.newChild();
            assertTrue(traces.add(root.traceId()));
            assertEquals(root.traceId(),child.traceId());
            assertNotEquals(root.spanId(),child.spanId());
            assertEquals(root,TraceParent.parse(root.toHeader()).orElseThrow());
            assertEquals(child,TraceParent.parse(child.toHeader()).orElseThrow());
        }
    }
}
