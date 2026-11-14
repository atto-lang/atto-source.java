package dev.oreslang.runtime;

import java.util.concurrent.atomic.AtomicIntegerArray;

/** Actual production selector exercised with plain JDK 21, no JNI/Maven. */
public final class SoftLaneSelectorStandaloneCheck {
    private SoftLaneSelectorStandaloneCheck() { }

    private static void expect(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    private static AtomicIntegerArray lanes(int... values) {
        return new AtomicIntegerArray(values);
    }

    public static void main(String[] args) {
        var two = lanes(2, 0);
        expect(SoftLaneSelector.select(0, 2, two, 0) == 1,
                "overflow should spill to idle lane");
        expect(SoftLaneSelector.select(1, 0, two, 0) == 1,
                "idle preferred lane stays preferred");
        var tied = lanes(2, 2, 2);
        expect(SoftLaneSelector.select(1, 2, tied, 0) == 1,
                "ties must preserve preferred affinity");
        var allHigh = new AtomicIntegerArray(512);
        for (int i = 0; i < allHigh.length(); ++i) allHigh.set(i, 9);
        allHigh.set(511, 0);
        expect(SoftLaneSelector.select(0, 9, allHigh, 0) == 0,
                "a bounded scan cannot pretend to have found an unexamined lane");
        expect(SoftLaneSelector.select(0, 9, allHigh, 480) == 511,
                "rotated window must discover a distant idle lane");
        for (int seed = -128; seed < 128; seed++) {
            int selected = SoftLaneSelector.select(0, 9, allHigh, seed);
            expect(selected >= 0 && selected < allHigh.length(),
                    "sampled lane outside dispatcher range");
        }
        boolean rejected = false;
        try { SoftLaneSelector.select(2, 0, two, 0); }
        catch (IllegalArgumentException expected) { rejected = true; }
        expect(rejected, "invalid preferred lane was accepted");
        rejected = false;
        try { SoftLaneSelector.select(0, -1, two, 0); }
        catch (IllegalArgumentException expected) { rejected = true; }
        expect(rejected, "negative depth was accepted");
        System.out.println("SoftLaneSelectorStandaloneCheck PASS");
    }
}
