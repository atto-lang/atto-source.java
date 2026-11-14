package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** Source-local dependency-free supplementary test, not guest compiler parity. */
public final class NdArrayViewAdmissionSmoke {
    static void check(boolean value) { if (!value) throw new AssertionError("NdArray invariant"); }
    static void deny(Runnable action) {
        boolean failed = false;
        try { action.run(); }
        catch (IllegalArgumentException | IndexOutOfBoundsException expected) { failed = true; }
        check(failed);
    }
    static void enumerate(long[] shape, long[] strides, int axis, long at,
                          NdArrayViewAdmission.Range range, Set<Long> found) {
        if (axis == shape.length) {
            check(at >= range.begin() && at + 8 <= range.end());
            check(found.add(at));
            return;
        }
        for (long i = 0; i < shape[axis]; i++)
            enumerate(shape, strides, axis + 1, at + i * strides[axis], range, found);
    }
    public static void main(String[] args) {
        check(NdArrayViewAdmission.check(new long[]{3,2},new long[]{8,24},0,48,8,true)
                .equals(new NdArrayViewAdmission.Range(0,48)));
        check(NdArrayViewAdmission.check(new long[]{3,2},new long[]{-8,24},16,48,8,true)
                .equals(new NdArrayViewAdmission.Range(0,48)));
        check(NdArrayViewAdmission.check(new long[]{3},new long[]{0},0,8,8,false)
                .equals(new NdArrayViewAdmission.Range(0,8)));
        deny(() -> NdArrayViewAdmission.check(new long[]{3},new long[]{0},0,8,8,true));
        deny(() -> NdArrayViewAdmission.check(new long[]{2,2},new long[]{8,8},0,24,8,true));
        deny(() -> NdArrayViewAdmission.check(new long[]{2,2},new long[]{-8,8},0,24,8,true));
        deny(() -> NdArrayViewAdmission.check(new long[]{2},new long[]{Long.MIN_VALUE},0,24,8,false));
        check(NdArrayViewAdmission.check(new long[]{0,3},new long[]{0,8},8,16,8,true)
                .equals(new NdArrayViewAdmission.Range(8,8)));
        check(NdArrayViewAdmission.check(new long[]{},new long[]{},0,8,8,true)
                .equals(new NdArrayViewAdmission.Range(0,8)));
        Random rng = new Random(419);
        int accepted = 0, rejected = 0;
        for (int trial = 0; trial < 6000; trial++) {
            int rank = rng.nextInt(5);
            long[] shape = new long[rank], strides = new long[rank];
            for (int axis = 0; axis < rank; axis++) {
                shape[axis] = rng.nextInt(4);
                strides[axis] = (rng.nextInt(9) - 4) * 8L;
            }
            long offset = rng.nextInt(21) * 8L;
            NdArrayViewAdmission.Range range;
            try { range = NdArrayViewAdmission.check(shape,strides,offset,256,8,true); }
            catch (IllegalArgumentException | IndexOutOfBoundsException exc) { rejected++; continue; }
            accepted++;
            Set<Long> locations = new HashSet<>();
            enumerate(shape,strides,0,offset,range,locations);
        }
        check(accepted > 100 && rejected > 100);
        System.out.println("PASS Java 6000 NdArray strided-view randomized cases");
    }
}
