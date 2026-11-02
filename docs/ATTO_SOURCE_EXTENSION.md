# Canonical AttoLang source extension

.atto is the canonical extension for new AttoLang source files, examples, test fixtures and modules.

The Java frontend accepts both .atto and the legacy .ores extension as the same Ores-language source grammar. .java remains the mixed Java host-source extension.

For an extensionless relative import, the resolver prefers a sibling `.atto` entry, then falls back to `.ores`, then `.java`. An explicit extension remains authoritative. A canonical `.atto` directory entry is considered present even when it is a directory or symlink: the resolver selects it and the bounded no-follow reader rejects it if it is not a regular source file. It must never silently fall through to a shadowing `.ores` sibling. Inaccessible metadata also fails closed.

This change does not claim that other backends already accept .atto; each backend must implement and test the same source-resolution contract before parity is declared.
