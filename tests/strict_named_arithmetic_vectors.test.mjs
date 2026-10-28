// Independent mathematical fixture check. This does NOT execute Oreslang
// guest source, native image, LLVM codegen, .NET AOT or WASM runtimes.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const fixture = JSON.parse(readFileSync(
  new URL("../contracts/v1/strict-named-integer-arithmetic-v1.json", import.meta.url),
  "utf8"));
const MIN = -(1n << 63n), MAX = (1n << 63n) - 1n;

function oracle(operation, left, right) {
  if ((operation === "rem" || operation === "mod") && right === 0n) {
    return { error: "DIVISOR_ZERO" };
  }
  if (operation === "pow" && right < 0n) {
    return { error: "NEGATIVE_EXPONENT" };
  }
  let result;
  switch (operation) {
    case "rem": result = left % right; break;
    case "mod": {
      const remainder = left % right;
      result = remainder < 0n
        ? remainder + (right < 0n ? -right : right)
        : remainder;
      break;
    }
    case "pow": result = left ** right; break;
    case "bit_xor": result = left ^ right; break;
    default: throw new Error("unknown contract operation: " + operation);
  }
  if (result < MIN || result > MAX) return {error: "OVERFLOW"};
  return {value: result.toString()};
}

test("signed-64 named arithmetic fixture is complete and uniquely identified", () => {
  assert.equal(fixture.contract, "oreslang.strict-named-integer-arithmetic.v1");
  assert.equal(fixture.integer, "signed64");
  assert.equal(fixture.cases.length, 26);
  assert.equal(new Set(fixture.cases.map(c => c.id)).size, 26);
});

test("26 vectors agree with independent exact BigInt mathematical semantics", () => {
  for (const {id, operation, left, right, expect} of fixture.cases) {
    const a = BigInt(left), b = BigInt(right);
    assert.ok(a >= MIN && a <= MAX, id + " left input signed64");
    assert.ok(b >= MIN && b <= MAX, id + " right input signed64");
    assert.deepEqual(oracle(operation, a, b), expect, id);
  }
});

test("11 exact signed64 to binary64 vectors preserve IEEE754 bit patterns", () => {
  const conversion = JSON.parse(readFileSync(
    new URL("../contracts/v1/strict-integer-to-float-exact-v1.json", import.meta.url),
    "utf8"));
  assert.equal(conversion.contract, "oreslang.strict-integer-to-float-exact.v1");
  assert.equal(conversion.cases.length, 11);
  assert.equal(new Set(conversion.cases.map(c => c.id)).size, 11);
  const bits = new DataView(new ArrayBuffer(8));
  for (const {id, operation, operand, expect} of conversion.cases) {
    assert.equal(operation, "toFloatExact", id);
    const original = BigInt(operand);
    assert.ok(original >= MIN && original <= MAX, id);
    const converted = Number(original);
    const inexact = converted >= 2 ** 63 || BigInt(converted) !== original;
    if (inexact) {
      assert.deepEqual(expect, {error: "INEXACT_CONVERSION"}, id);
    } else {
      bits.setFloat64(0, converted, false);
      assert.deepEqual(expect, {
        doubleBitsHex: bits.getBigUint64(0, false).toString(16).padStart(16, "0")
      }, id);
    }
  }
});
