"""Source-free Git fixture tests; no Java Oreslang compiler is built or exported."""
from __future__ import annotations
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import compiler_bundle as cb

INFO = {'build_mode': 'jvm', 'default_execution_mode': 'jit',
        'guest_execution': 'runtime-selected'}


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root / 'source'
        self.source.mkdir()
        self.git('init', '-q')
        self.git('config', 'user.name', 'Synthetic Test')
        self.git('config', 'user.email', 'test@example.invalid')
        (self.source / 'pom.xml').write_text('<synthetic/>\n')
        self.git('add', 'pom.xml')
        self.git('commit', '-qm', 'synthetic source')
        self.commit = self.git('rev-parse', 'HEAD')
        self.tree = self.git('show', '-s', '--format=%T', 'HEAD')
        self.jar = self.root / 'compiled.jar'
        self.jar.write_bytes(b'fake jar bytes - NOT AN ORESLANG BINARY\n')
        self.deps = self.root / 'runtime-deps'
        self.deps.mkdir()
        (self.deps / 'dep-a.jar').write_bytes(b'fake dependency A\n')
        self.out = self.root / 'bundle'

    def git(self, *args):
        return subprocess.run(['git', '-C', str(self.source), *args], check=True,
                              capture_output=True, text=True).stdout.strip()

    def create(self):
        # Maven is not needed for pure admission fixtures. Only toolchain
        # version reporting is faked; Git checks and byte digests are real.
        real_run = subprocess.run
        def versions(args, *a, **kw):
            if args == ['mvn', '--version']:
                return subprocess.CompletedProcess(args, 0, stdout='Apache Maven synthetic', stderr='')
            return real_run(args, *a, **kw)
        with patch.object(cb, 'probe_info', return_value=dict(INFO)), \
             patch.object(cb.subprocess, 'run', side_effect=versions):
            return cb.create(type('Args', (), {
                'source_dir': str(self.source), 'source_commit': self.commit,
                'source_tree': self.tree, 'main_jar': str(self.jar),
                'dependency_dir': str(self.deps), 'out_dir': str(self.out)
            }))

    def verify(self, commit=None, tree=None):
        with patch.object(cb, 'probe_info', return_value=dict(INFO)):
            return cb.verify_bundle(self.out, commit or self.commit, tree or self.tree)

    def change_receipt(self, fn):
        manifest = self.out / 'manifest.json'
        doc = json.loads(manifest.read_text())
        fn(doc)
        manifest.write_text(json.dumps(doc))

    def test_receipt_and_jar_inventory_verified(self):
        doc = self.create()
        self.assertEqual(len(doc['files']), 3)
        self.assertFalse(self.verify()['provenance_independently_attested'])

    def test_wrong_commit_denied(self):
        with self.assertRaisesRegex(ValueError, 'wrong source commit'):
            with patch.object(cb, 'probe_info', return_value=INFO):
                cb.create(type('Args', (), {'source_dir': str(self.source),
                  'source_commit': '0'*40, 'source_tree': self.tree,
                  'main_jar': str(self.jar), 'dependency_dir': str(self.deps),
                  'out_dir': str(self.out)}))
        self.assertFalse(self.out.exists())

    def test_wrong_source_tree_denied(self):
        self.create()
        with self.assertRaisesRegex(ValueError, 'wrong source commit/tree'):
            self.verify(tree='0'*40)

    def test_wrong_expected_commit_denied(self):
        self.create()
        with self.assertRaisesRegex(ValueError, 'wrong source commit/tree'):
            self.verify(commit='0'*40)

    def test_missing_dependency_denied(self):
        self.create()
        (self.out / 'deps' / 'dep-a.jar').unlink()
        with self.assertRaisesRegex(ValueError, 'empty or invalid dependency set'):
            self.verify()

    def test_wrong_dependency_hash_denied(self):
        self.create()
        (self.out / 'deps' / 'dep-a.jar').write_bytes(b'different dependency')
        with self.assertRaisesRegex(ValueError, 'dependency/artifact bytes'):
            self.verify()

    def test_wrong_launcher_hash_denied(self):
        self.create()
        launcher = self.out / 'run-oreslang'
        launcher.write_text(launcher.read_text() + '# tampered\n')
        with self.assertRaisesRegex(ValueError, 'dependency/artifact bytes'):
            self.verify()

    def test_wrong_jar_hash_denied(self):
        self.create()
        (self.out / 'app.jar').write_bytes(b'changed jar')
        with self.assertRaisesRegex(ValueError, 'dependency/artifact bytes'):
            self.verify()

    def test_unlisted_dependency_denied(self):
        self.create()
        (self.out / 'deps' / 'unlisted.jar').write_bytes(b'missing from manifest')
        with self.assertRaisesRegex(ValueError, 'dependency/artifact bytes'):
            self.verify()

    def test_unlisted_root_file_denied(self):
        self.create()
        (self.out / 'extra').write_bytes(b'not allowed')
        with self.assertRaisesRegex(ValueError, 'unexpected bundle root'):
            self.verify()

    def test_symlink_dependency_denied(self):
        self.create()
        dep = self.out / 'deps' / 'dep-a.jar'
        dep.unlink()
        dep.symlink_to(self.jar)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            self.verify()

    def test_manifest_mode_mismatch_denied(self):
        self.create()
        self.change_receipt(lambda x: x['build_info'].update(build_mode='aot'))
        with self.assertRaisesRegex(ValueError, 'unsupported launcher build mode'):
            self.verify()

    def test_forged_provenance_claim_denied(self):
        self.create()
        self.change_receipt(lambda x: x.update(provenance_independently_attested=True))
        with self.assertRaisesRegex(ValueError, 'forged attestation claim'):
            self.verify()

    def test_noncanonical_manifest_keys_denied(self):
        self.create()
        self.change_receipt(lambda x: x.update(guest_verified=True))
        with self.assertRaisesRegex(ValueError, 'noncanonical receipt schema'):
            self.verify()

    def test_duplicate_manifest_key_denied(self):
        self.create()
        man = self.out / 'manifest.json'
        man.write_text(man.read_text().replace('"schema_version":', '"schema_version":"x", "schema_version":'))
        with self.assertRaisesRegex(ValueError, 'duplicate JSON key'):
            self.verify()

    def test_dirty_source_denied(self):
        (self.source / 'pom.xml').write_text('tampered')
        with self.assertRaisesRegex(ValueError, 'dirty source checkout'):
            cb.check_source(self.source, self.commit, self.tree)

    def test_unsupported_real_cli_build_mode_denied(self):
        fake = self.root / 'fake-oresmain'
        fake.write_text('#!/bin/sh\nprintf \'{"build_mode":"aot","default_execution_mode":"aot","guest_execution":"interpreter-no-jit"}\\n\'\n')
        fake.chmod(0o700)
        with self.assertRaisesRegex(ValueError, 'unsupported launcher build mode'):
            cb.probe_info(fake, self.root)

    def test_invalid_build_info_json_denied(self):
        fake = self.root / 'fake-oresmain'
        fake.write_text('#!/bin/sh\nprintf \'not-json\\n\'\n')
        fake.chmod(0o700)
        with self.assertRaisesRegex(ValueError, 'malformed build-info JSON'):
            cb.probe_info(fake, self.root)

    def test_missing_source_archive_denied(self):
        with self.assertRaisesRegex(ValueError, 'missing, linked, empty or oversized'):
            cb.regular(self.root / 'missing.jar')


if __name__ == '__main__':
    unittest.main()
