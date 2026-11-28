package dev.oreslang.types;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class RuntimeParkingLoanGraphTest {
    @Test void recursionAndForwardReferencesRetainCallsiteLoan() {
        var g = new RuntimeParkingLoanGraph();
        Object a=new Object(), b=new Object(), sink=new Object(), unrelated=new Object();
        g.edge(a,b,"live loan at b");
        g.edge(b,a,null);
        g.edge(b,sink,null);
        g.edge(a,unrelated,"unrelated");
        assertNull(g.firstViolation());
        g.markParking(sink);
        assertEquals("live loan at b",g.firstViolation());
    }
    @Test void structurallyEqualDeclarationsDoNotAlias() {
        var g = new RuntimeParkingLoanGraph();
        Object a=new String("same"), b=new String("same");
        g.edge(null,a,"must not confuse equal declarations");
        g.markParking(b);
        assertNull(g.firstViolation());
        g.markParking(a);
        assertNotNull(g.firstViolation());
    }
    @Test void deepGraphDoesNotGrowJavaStack() {
        var g = new RuntimeParkingLoanGraph();
        Object first=new Object(), previous=first;
        g.edge(null,first,"root loan");
        for(int i=0;i<10000;i++){
            Object next=new Object(); g.edge(previous,next,null); previous=next;
        }
        g.markParking(previous);
        assertEquals("root loan",g.firstViolation());
    }
}
