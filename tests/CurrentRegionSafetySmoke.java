package dev.oreslang.runtime;

public final class CurrentRegionSafetySmoke {
  private static long checks;
  private static void ok(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
  private static void mustFail(Class<? extends Throwable> expected, Runnable callback) {
    checks++; try { callback.run(); } catch (Throwable found) {
      if (expected.isInstance(found)) return;
      throw new AssertionError("wrong exception", found);
    }
    throw new AssertionError("expected " + expected.getSimpleName());
  }
  public static void main(String[] args) throws Exception {
    for (int iteration=0; iteration<10_000; iteration++) {
      final int original = iteration;
      var region = new ReusableLongStateRegion(2);
      var foreign = new ReusableLongStateRegion(1);
      long value = region.insert(iteration);
      long pin = region.pin();
      mustFail(IllegalStateException.class, () -> foreign.releasePin(pin));
      mustFail(IllegalStateException.class, () -> region.update(value, before -> {
        ok(before == original, "non-atomic prestate");
        region.beginRetire();
        return before + 1;
      }));
      ok(region.size() == 1, "reentrant stop deleted slot");
      ok(region.activePins() == 1, "physical pin lost during abort");
      ok(!region.tryClose(), "reclaimed with physical pin");
      mustFail(IllegalStateException.class, () -> region.read(value));
      mustFail(IllegalStateException.class, region::pin);
      region.releasePin(pin);
      ok(region.tryClose(), "cannot reclaim after all pins physically released");
      ok(region.isClosed(), "not closed");
      mustFail(IllegalStateException.class, () -> region.releasePin(pin));
      foreign.close();
      // Transactional arithmetic under overflow on a fresh unrelated region.
      var fresh = new ReusableLongStateRegion(1);
      long h = fresh.insert(Long.MAX_VALUE);
      mustFail(ArithmeticException.class, () -> fresh.addExact(h, 1));
      ok(fresh.read(h) == Long.MAX_VALUE, "overflow partially committed");
      fresh.close();
    }
    System.out.printf("EXACT_CURRENT_REGION_JDK21_PASS checks=%d iterations=10000%n", checks);
  }
}
