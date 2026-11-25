package dev.oreslang.parser;

import java.math.BigInteger;
import java.util.Map;
import java.util.TreeMap;

/** Normalized, file-local quantity type used by closed scalar elaboration. */
record VerifiedQuantityUnit(Map<String, Integer> powers, BigInteger numerator, BigInteger denominator) {
    private static final BigInteger ONE = BigInteger.ONE;
    static final VerifiedQuantityUnit SCALAR = new VerifiedQuantityUnit(Map.of(), ONE, ONE);

    VerifiedQuantityUnit {
        if (numerator.signum() <= 0 || denominator.signum() <= 0) {
            throw new IllegalArgumentException("E_UNIT_SCALE: unit scales must be positive");
        }
        BigInteger gcd = numerator.gcd(denominator);
        numerator = numerator.divide(gcd);
        denominator = denominator.divide(gcd);
        if (powers.size() > 32 || powers.values().stream().anyMatch(power -> Math.abs(power) > 64)
                || numerator.bitLength() > 256 || denominator.bitLength() > 256) {
            throw new IllegalArgumentException("E_UNIT_LIMIT: normalized quantity exceeds supported limits");
        }
        powers = Map.copyOf(powers);
    }

    static VerifiedQuantityUnit base(String dimension) {
        return new VerifiedQuantityUnit(Map.of(dimension, 1), ONE, ONE);
    }

    VerifiedQuantityUnit scale(long factor, boolean divide) {
        BigInteger magnitude = BigInteger.valueOf(factor);
        return divide ? new VerifiedQuantityUnit(powers, numerator, denominator.multiply(magnitude))
                : new VerifiedQuantityUnit(powers, numerator.multiply(magnitude), denominator);
    }

    boolean sameDimension(VerifiedQuantityUnit other) {
        return powers.equals(other.powers);
    }

    void requireCompatible(VerifiedQuantityUnit other) {
        if (!sameDimension(other)) {
            throw new IllegalArgumentException("E_UNIT_DIMENSION: incompatible quantity dimensions");
        }
        if (!equals(other)) {
            throw new IllegalArgumentException("E_UNIT_SCALE: quantities require an explicit scale conversion");
        }
    }

    VerifiedQuantityUnit combine(VerifiedQuantityUnit other, boolean divide) {
        var result = new TreeMap<>(powers);
        other.powers.forEach((dimension, power) -> result.merge(dimension, divide ? -power : power, Integer::sum));
        result.values().removeIf(power -> power == 0);
        return divide
                ? new VerifiedQuantityUnit(result, numerator.multiply(other.denominator), denominator.multiply(other.numerator))
                : new VerifiedQuantityUnit(result, numerator.multiply(other.numerator), denominator.multiply(other.denominator));
    }

    long convertExact(long value, VerifiedQuantityUnit target) {
        if (!sameDimension(target)) {
            throw new IllegalArgumentException("E_UNIT_DIMENSION: conversion requires the same dimension");
        }
        var scaled = BigInteger.valueOf(value).multiply(numerator).multiply(target.denominator)
                .divideAndRemainder(denominator.multiply(target.numerator));
        if (scaled[1].signum() != 0) {
            throw new IllegalArgumentException("E_UNIT_INEXACT: conversion requires an integral result");
        }
        try {
            return scaled[0].longValueExact();
        } catch (ArithmeticException outsideI64) {
            throw new IllegalArgumentException("E_UNIT_RANGE: conversion result is outside signed i64");
        }
    }
}
