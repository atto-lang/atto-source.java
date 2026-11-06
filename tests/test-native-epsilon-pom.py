#!/usr/bin/env python3
"""Contract-only validation of GraalVM Native Image Epsilon profile wiring.

This is NOT evidence that Native Image built, that the guest ran or that
Epsilon allows bounded long-running Java heap growth.
"""
import copy
import pathlib
import unittest
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
ROOT = pathlib.Path(__file__).resolve().parents[1]
POM = ROOT / "pom.xml"


def validate(root):
    def one(parent, query):
        found = parent.findall(query, NS)
        if len(found) != 1:
            raise AssertionError(f"expected exactly one {query}, got {len(found)}")
        return found[0]
    props = one(root, "m:properties")
    assert one(props, "m:native.image.gc.args").text == "--gc=serial", "default must be explicit Serial GC"
    profiles = one(root, "m:profiles")
    profs = profiles.findall("m:profile", NS)
    names = [one(p, "m:id").text for p in profs]
    assert len(names) == len(set(names)), "duplicate Maven profile ids"
    def profile(n):
        assert names.count(n) == 1, f"missing or duplicate profile {n}"
        return profs[names.index(n)]
    aot = profile("native-aot")
    eps = profile("native-epsilon")
    hybrid = profile("native-hybrid")
    aot_args = one(aot, ".//m:commandlineArgs").text or ""
    hybrid_args = one(hybrid, ".//m:commandlineArgs").text or ""
    assert aot_args.count("${native.image.gc.args}") == 1, "AOT profile must carry exact GC arg"
    assert "--no-fallback" in aot_args and "-Dores.native.build-mode=aot" in aot_args, "full native AOT only"
    assert "${native.image.gc.args}" not in hybrid_args, "hybrid must not inherit epsilon config"
    assert "--gc=epsilon" not in hybrid_args, "hybrid Epsilon incorrectly permitted"
    assert eps.find("m:activation", NS) is None, "epsilon must not be activated implicitly"
    eps_props = one(eps, "m:properties")
    assert one(eps_props, "m:native.image.gc.args").text == "--gc=epsilon", "explicit opt-in missing"
    return True


class EpsilonPomTests(unittest.TestCase):
    def setUp(self):
        self.root = ET.parse(POM).getroot()

    def test_profile_is_opt_in_and_aot_only(self):
        self.assertTrue(validate(self.root))

    def check_rejection(self, mutate):
        root = copy.deepcopy(self.root)
        mutate(root)
        with self.assertRaises((AssertionError, ValueError)):
            validate(root)

    def test_fails_if_default_collector_becomes_epsilon(self):
        self.check_rejection(lambda r: r.find("m:properties/m:native.image.gc.args", NS).__setattr__("text", "--gc=epsilon"))

    def test_fails_if_gc_arg_removed_from_native_image_invocation(self):
        def mutate(r):
            node = r.find(".//m:profile[m:id='native-aot']//m:commandlineArgs", NS)
            node.text = node.text.replace("${native.image.gc.args}", "")
        self.check_rejection(mutate)

    def test_fails_if_epsilon_profile_is_enabled_implicitly(self):
        def mutate(r):
            p = r.find(".//m:profile[m:id='native-epsilon']", NS)
            ET.SubElement(p, "{%s}activation" % NS["m"])
        self.check_rejection(mutate)

    def test_fails_if_epsilon_is_not_selected(self):
        def mutate(r):
            e = r.find(".//m:profile[m:id='native-epsilon']/m:properties/m:native.image.gc.args", NS)
            e.text = "--gc=serial"
        self.check_rejection(mutate)

    def test_fails_if_duplicate_gc_property_is_passed(self):
        def mutate(r):
            node = r.find(".//m:profile[m:id='native-aot']//m:commandlineArgs", NS)
            node.text += " ${native.image.gc.args}"
        self.check_rejection(mutate)

    def test_fails_if_hybrid_inherits_epsilon(self):
        def mutate(r):
            node = r.find(".//m:profile[m:id='native-hybrid']//m:commandlineArgs", NS)
            node.text += " ${native.image.gc.args}"
        self.check_rejection(mutate)


if __name__ == "__main__":
    unittest.main()
