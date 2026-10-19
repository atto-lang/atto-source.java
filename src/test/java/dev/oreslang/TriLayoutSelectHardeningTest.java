package dev.oreslang;
import dev.oreslang.runtime.TriLayoutMotionStore;
import dev.oreslang.runtime.TriLayoutMotionStore.Layout;
import dev.oreslang.runtime.TriLayoutMotionStore.Motion;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class TriLayoutSelectHardeningTest {
  @Test void finiteOverflowFailsWithoutCommitting() {
    for (int mask=1; mask<8; mask++) {
      var layouts=EnumSet.noneOf(Layout.class);
      for (Layout l:Layout.values()) if((mask&(1<<l.ordinal()))!=0) layouts.add(l);
      for(Layout authority:layouts) {
        try(var s=new TriLayoutMotionStore(3,2,layouts,authority,Long.MAX_VALUE)) {
          var a=s.insert(new Motion(1,2,1,1));
          var b=s.insert(new Motion(3,4,1,1));
          var c=s.insert(new Motion(5,6,Double.MAX_VALUE,1));
          var target=layouts.iterator().next();
          long epoch=s.epoch();
          assertThrows(ArithmeticException.class,()->s.integrate(target,2.0));
          assertEquals(epoch,s.epoch());
          assertEquals(authority,s.authoritativeLayout());
          for(Layout l:layouts) {
            assertEquals(new Motion(1,2,1,1),s.get(a,l));
            assertEquals(new Motion(3,4,1,1),s.get(b,l));
            assertEquals(new Motion(5,6,Double.MAX_VALUE,1),s.get(c,l));
          }
          s.integrate(target,0.5);
          assertEquals(epoch+1,s.epoch());
        }
      }
    }
  }
  @Test void clientSetMutationCannotChangeResidency() {
    var input=EnumSet.of(Layout.AOS,Layout.SOA);
    try(var s=new TriLayoutMotionStore(2,2,input,Layout.SOA,Long.MAX_VALUE)) {
      input.clear();input.add(Layout.AOSOA);
      assertEquals(Set.of(Layout.AOS,Layout.SOA),s.residentLayouts());
      assertThrows(IllegalArgumentException.class,()->s.sumX(Layout.AOSOA));
      var h=s.insert(new Motion(-0.0,2,3,4));
      long negzero=Double.doubleToRawLongBits(-0.0);
      assertEquals(negzero,Double.doubleToRawLongBits(s.get(h,Layout.SOA).x()));
      assertEquals(negzero,Double.doubleToRawLongBits(s.get(h,Layout.AOS).x()));
    }
  }
}
