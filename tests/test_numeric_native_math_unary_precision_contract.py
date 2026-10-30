import json
import math
import struct
import unittest
from decimal import Decimal
from pathlib import Path

CONTRACT = Path(__file__).resolve().parents[1] / "contracts/v1/native-math-unary-precision-v1.json"
I64_MIN = -(1 << 63)
I64_MAX = (1 << 63) - 1


def eval_i64(operation, operand):
    value = int(operand)
    if not I64_MIN <= value <= I64_MAX:
        raise ValueError("fixture operand outside i64")
    if operation == "Math.neg":
        return {"error": "OVERFLOW"} if value == I64_MIN else {"value": str(-value)}
    if operation == "Math.abs":
        return {"error": "OVERFLOW"} if value == I64_MIN else {"value": str(abs(value))}
    raise ValueError("unknown operation")


def bits_to_f64(hex_value):
    return struct.unpack(">d", bytes.fromhex(hex_value))[0]


def f64_to_bits(value):
    return struct.pack(">d", value).hex()


class NativeMathUnaryPrecisionContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.contract = json.loads(CONTRACT.read_text(encoding="utf-8"))

    def test_identity_and_unique_ids(self):
        self.assertEqual("1.0.0", self.contract["schema_version"])
        self.assertEqual("oreslang.native-math-unary-precision.v1", self.contract["contract"])
        groups = ("signed_i64_unary", "bigint_unary", "decimal_unary",
                  "binary64_unary", "precision_cases")
        ids = [item["id"] for group in groups for item in self.contract[group]]
        self.assertEqual(len(ids), len(set(ids)))

    def test_signed_i64_checked_unary(self):
        self.assertEqual(10, len(self.contract["signed_i64_unary"]))
        for item in self.contract["signed_i64_unary"]:
            with self.subTest(item=item["id"]):
                self.assertEqual(item["expect"], eval_i64(item["operation"], item["operand"]))

    def test_bigint_unary_exact(self):
        for item in self.contract["bigint_unary"]:
            value = int(item["operand"])
            actual = -value if item["operation"] == "Math.neg" else abs(value)
            self.assertEqual(item["expect"], str(actual), item["id"])

    def test_decimal_unary_preserves_scale(self):
        for item in self.contract["decimal_unary"]:
            value = Decimal(item["operand"])
            actual = -value if item["operation"] == "Math.neg" else abs(value)
            self.assertEqual(Decimal(item["expect"]), actual, item["id"])
            self.assertEqual(item["scale"], -actual.as_tuple().exponent, item["id"])

    def test_binary64_sign_vectors(self):
        for item in self.contract["binary64_unary"]:
            value = bits_to_f64(item["operand_bits"])
            actual = abs(value) if item["operation"] == "Math.abs" else -value
            self.assertEqual(item["expect_bits"], f64_to_bits(actual), item["id"])

    def test_precision_domains_are_separate(self):
        binary = next(x for x in self.contract["precision_cases"]
                      if x["id"] == "binary64-point-one-plus-point-two")
        self.assertEqual(binary["must_equal_decimal_string"],
                         format(float(binary["lhs"]) + float(binary["rhs"]), ".17g"))
        decimal = next(x for x in self.contract["precision_cases"]
                       if x["id"] == "decimal-point-one-plus-point-two")
        self.assertEqual(Decimal(decimal["expect"]),
                         Decimal(decimal["lhs"]) + Decimal(decimal["rhs"]))
        bigint = next(x for x in self.contract["precision_cases"]
                      if x["id"] == "bigint-above-2pow53")
        self.assertNotEqual(int(bigint["value"]), int(float(bigint["value"])))
        third = next(x for x in self.contract["precision_cases"]
                     if x["id"] == "decimal-one-third")
        self.assertEqual("NONTERMINATING_REQUIRES_CONTEXT", third["without_context"])



class BigIntExactContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = Path(__file__).resolve().parents[1] / "contracts/v1/bigint-exact-v1.json"
        cls.contract = json.loads(path.read_text(encoding="utf-8"))

    def test_exact_bigint_division_and_conversion_vectors(self):
        self.assertEqual("oreslang.bigint-exact.v1", self.contract["contract"])
        for item in self.contract["division_cases"]:
            a, b = int(item["left"]), int(item["right"])
            if item.get("error"):
                self.assertEqual(0, b, item["id"])
                continue
            q = abs(a) // abs(b)
            if (a < 0) != (b < 0):
                q = -q
            r = a - q * b
            self.assertEqual(item["quot"], str(q), item["id"])
            self.assertEqual(item["rem"], str(r), item["id"])
            self.assertEqual(a, q * b + r, item["id"])

        for item in self.contract["conversion_cases"]:
            value = int(item["operand"])
            if item["operation"] == "to_i64_checked":
                overflow = value < -(1 << 63) or value > (1 << 63) - 1
                self.assertEqual(bool(item.get("error")), overflow, item["id"])

if __name__ == "__main__":
    unittest.main()
