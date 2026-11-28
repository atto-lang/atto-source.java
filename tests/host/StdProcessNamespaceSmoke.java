package dev.oreslang.stdlib;

import java.util.List;

/**
 * Independent JDK-only regression for std/ and atto.std/ import resolution.
 * Compiles the exact source registry, not the AttoLang guest compiler.
 */
public final class StdProcessNamespaceSmoke {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void bad(String importPath) {
        try {
            CoreLibrarySources.unitId(importPath);
            throw new AssertionError("expected rejection: " + importPath);
        } catch (IllegalArgumentException expected) {
            // Library names are closed; no project-file fallback.
        }
    }

    public static void main(String[] args) throws Exception {
        for (String module : List.of("process", "process_policy")) {
            String shortName = "std/" + module;
            String qualified = "atto.std/" + module;
            check(CoreLibrarySources.isStdImport(shortName), "short std import");
            check(CoreLibrarySources.isStdImport(qualified), "qualified std import");
            check(CoreLibrarySources.isReservedOresLibraryImport("atto.std"),
                    "qualified namespace root reserved");
            check(CoreLibrarySources.isReservedOresLibraryImport(qualified),
                    "qualified namespace protected from filesystem shadowing");
            check(!CoreLibrarySources.isReservedOresLibraryImport(shortName),
                    "unqualified namespace remains available to project resolver");
            String shortUnit = CoreLibrarySources.unitId(shortName);
            check(shortUnit.equals(CoreLibrarySources.unitId(qualified)),
                    "two imports must share unit identity");
            check(shortUnit.equals("@std/" + module + ".atto"),
                    "stable std unit identity");
            check(CoreLibrarySources.fileName(shortName).equals(module + ".atto"),
                    "short file identity");
            check(CoreLibrarySources.fileName(qualified).equals(module + ".atto"),
                    "qualified file identity");
            String source = CoreLibrarySources.source(shortName);
            check(source.equals(CoreLibrarySources.source(qualified)),
                    "same packaged source bytes");
            check(source.equals(CoreLibrarySources.source(qualified + ".atto")),
                    "explicit extension alias");
            check(source.contains("define module "), "valid module source");
            if (module.equals("process")) {
                check(source.contains("define module process as"),
                        "process module must use lowercase namespace");
                check(!source.contains("define module Process as"),
                        "legacy capitalized module forbidden");
                check(source.contains("return std.process.spawn("),
                        "host intrinsic must be reached without recursive process.spawn");
            }
            bad("ores.std/" + module);
            bad("ores.std/" + module + ".atto");
            bad(qualified + ".ores");
            bad(qualified + "/..");
            bad(qualified + "//child");
            bad(qualified + "/../child");
            bad(qualified + "\\child");
            bad(qualified + "\0child");
        }
        check(!CoreLibrarySources.isStdImport("atto.stdish/process"),
                "reject prefix smuggling");
        check(!CoreLibrarySources.isStdImport("stdish/process"),
                "reject prefix smuggling");
        check(!CoreLibrarySources.isReservedOresLibraryImport("atto.stdish/process"),
                "only exact qualified std prefix is reserved");
        check(CoreLibrarySources.unitId("ores.std/trace")
                .equals(CoreLibrarySources.unitId("std/trace")),
                "existing hosted library imports keep their compatibility identity");
        check(!CoreLibrarySources.source("ores.std/trace").isEmpty(),
                "old library compatibility remains available");
        System.out.println("STD_PROCESS_NAMESPACE_SMOKE_PASS");
    }
}
