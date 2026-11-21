package dev.oreslang;
import dev.oreslang.runtime.TriLayoutMotionStore;
import dev.oreslang.runtime.TriLayoutMotionStore.Layout;
import dev.oreslang.runtime.TriLayoutMotionStore.Motion;
import java.util.EnumSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
          long[] stamps=new long[Layout.values().length];
          for(Layout l:layouts) stamps[l.ordinal()]=s.materializedEpoch(l);
          assertThrows(ArithmeticException.class,()->s.integrate(target,2.0));
          assertEquals(epoch,s.epoch());
          for(Layout l:layouts) assertEquals(stamps[l.ordinal()],
              s.materializedEpoch(l),"rejected integration must not refresh a cache");
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

  @Test void synchronizedMixedLayoutWritesKeepSnapshotsCoherent() throws Exception {
    for (int mask=1; mask<8; mask++) {
      var layouts=EnumSet.noneOf(Layout.class);
      for(Layout l:Layout.values()) if((mask&(1<<l.ordinal()))!=0) layouts.add(l);
      var choices=List.copyOf(layouts);
      try(var s=new TriLayoutMotionStore(3,2,layouts,choices.get(0),Long.MAX_VALUE)) {
        var handles=new ArrayList<TriLayoutMotionStore.Handle>();
        for(int i=0;i<3;i++) handles.add(s.insert(new Motion(0,0,1,0)));
        var gate=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(5);
        try {
          var futures=new ArrayList<Future<?>>();
          for(int worker=0;worker<4;worker++) {
            final Layout chosen=choices.get(worker%choices.size());
            futures.add(pool.submit(()->{
              gate.await();
              for(int i=0;i<100;i++) s.integrate(chosen,1);
              return null;
            }));
          }
          futures.add(pool.submit(()->{
            gate.await();
            for(int i=0;i<300;i++) {
              var rows=s.snapshot(choices.get(i%choices.size()));
              assertEquals(rows.get(0),rows.get(1));
              assertEquals(rows.get(1),rows.get(2));
            }
            return null;
          }));
          gate.countDown();
          for(var future:futures) future.get(45,TimeUnit.SECONDS);
        } finally {
          pool.shutdownNow();
          assertTrue(pool.awaitTermination(45,TimeUnit.SECONDS));
        }
        for(Layout l:layouts) {
          for(var h:handles) assertEquals(400,s.get(h,l).x());
          assertEquals(s.epoch(),s.materializedEpoch(l));
        }
      }
    }
  }
}
