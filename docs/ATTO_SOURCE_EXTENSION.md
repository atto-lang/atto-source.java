# Canonical AttoLang source extension

.atto is the canonical extension for new AttoLang source files, examples, test fixtures and modules.

The Java frontend accepts both .atto and the legacy .ores extension as the same Ores-language source grammar. .java remains the mixed Java host-source extension.

For an extensionless relative import, the resolver now prefers a sibling .atto file, then falls back to .ores, then .java. An explicit extension remains authoritative. This prevents a legacy .ores module from silently shadowing a new .atto module during migration.

This change does not claim that other backends already accept .atto; each backend must implement and test the same source-resolution contract before parity is declared.
