import test from "node:test";
import assert from "node:assert/strict";
import { scanLegacyOperators, inventoryPaths } from "../scripts/inventory_legacy_operators.mjs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

test("records legacy modulo, bitwise caret and double caret precisely", () => {
  assert.deepEqual(scanLegacyOperators("a % b; a ^ b; a ^^ b;"), [
    {kind: "percent", line: 1, column: 3},
    {kind: "caret", line: 1, column: 10},
    {kind: "double-caret", line: 1, column: 17}
  ]);
});

test("does not count comments or quoted text as code operators", () => {
  const source = [
    "// ^ % here is not source code",
    "/* % and ^ are not active */",
    "val a = '%'; val b = \"^\"; val c = `^^ %`;",
    "val result = 5 % 2;"
  ].join("\n");
  assert.deepEqual(scanLegacyOperators(source), [
    {kind: "percent", line: 4, column: 16}
  ]);
});

test("does not treat escaped string delimiter as code, or double count ^^", () => {
  const source = "val s = \"escaped \\\" ^ and %\";\nfalse ^^ true;";
  assert.deepEqual(scanLegacyOperators(source), [
    {kind: "double-caret", line: 2, column: 7}
  ]);
});

test("scans template interpolation but not inert template text", () => {
  assert.deepEqual(scanLegacyOperators("`x=${a % b}`"), [
    {kind: "percent", line: 1, column: 8}
  ]);
  assert.deepEqual(scanLegacyOperators("`^ outside % ${fn(`nested ${a ^ b}`)}`"), [
    {kind: "caret", line: 1, column: 31}
  ]);
});

test("stdlib and examples cannot reintroduce migrated legacy operators", () => {
  // This is a narrow, enforced repository migration barrier, not proof that
  // the lexer/parser or third-party Oreslang sources have removed operators.
  const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
  const offenses = inventoryPaths([
    resolve(projectRoot, "stdlib"),
    resolve(projectRoot, "examples"),
  ]);
  assert.deepEqual(offenses, [],
    "migrated stdlib/examples source must use explicit named operators");
});
