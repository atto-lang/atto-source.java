#!/usr/bin/env python3
import importlib.util
import inspect
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]

def load(name,path):
    spec=importlib.util.spec_from_file_location(name,ROOT/path)
    mod=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod

oracle=load("authority_oracle",Path("scripts/private_ci_authorization_oracle.py"))
legacy=load("java_private_ci_legacy",Path("scripts/publish-private-ci-source.py"))
v3=load("java_private_ci_v3",Path("scripts/publish-private-ci-source-v3.py"))

class PublisherAuthorityAlignmentTest(unittest.TestCase):
    def test_incidents_and_quarantines_match_canonical_contract(self):
        contract=oracle.load_contract()
        incidents={x["number"]:x["issue_id"] for x in contract["blocking_incidents"]}
        quarantines={x["repository"].casefold():x["repository_id"] for x in contract["quarantined_destinations"]}
        self.assertEqual(legacy.BLOCKING_INCIDENT_IDS,incidents)
        self.assertEqual(set(legacy.BLOCKING_INCIDENTS),set(incidents))
        self.assertEqual({x.casefold() for x in legacy.QUARANTINED_DESTINATIONS},set(quarantines))
        self.assertEqual(set(legacy.QUARANTINED_DESTINATION_IDS),set(quarantines.values()))

    def test_real_publisher_keeps_required_runtime_gates(self):
        self.assertEqual(v3.INCIDENT_DISPOSITION_PATH,".github/private-ci-incident-dispositions.json")
        auth=inspect.getsource(v3.authorize_write)
        for needle in ("legacy.require_resolved_blocking_incidents","legacy.checked_policy_approval","checked_carrier_main","verify_source_pr","revalidate_source_governance"):
            self.assertIn(needle,auth)
        governance=inspect.getsource(v3.revalidate_source_governance)
        for needle in ("authorized_main_sha","checked_control_plane","checked_incident_dispositions","checked_contract"):
            self.assertIn(needle,governance)
        publish=inspect.getsource(v3.publish)
        self.assertGreaterEqual(publish.count("verify_required_source_snapshot"),2)
        self.assertIn("authorize_write",publish)
        protection=inspect.getsource(legacy._require_strict_source_protection)
        for needle in (
            "require_last_push_approval","app_id","bypass_pull_request_allowances",
            "allow_force_pushes","allow_deletions",
        ):
            self.assertIn(needle,protection)

if __name__=="__main__":
    unittest.main(verbosity=2)
