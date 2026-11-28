"""Source-free Git fixture tests; no Java Oreslang compiler is built or exported."""
from __future__ import annotations
import json
import stat
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch
import compiler_bundle as cb

INFO = {'build_mode': 'jvm', 'default_execution_mode': 'jit',
        'guest_execution': 'runtime-selected'}


class WorkflowActionPinTests(unittest.TestCase):
    def test_external_actions_are_immutable_commit_pinned(self):
        import re
        source = (Path(__file__).resolve().parents[2]
                  / '.github/workflows/compiler-bundle-proof.yml').read_text()
        uses = re.findall(r'^\s*(?:-\s*)?uses:\s+([^\s#]+)', source, re.MULTILINE)
        self.assertGreaterEqual(len(uses), 3)
        for value in uses:
            with self.subTest(action=value):
                self.assertRegex(value, r'^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+@[0-9a-f]{40}$')
        self.assertIn('actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02', uses)


class CompilerToolchainProbeTests(unittest.TestCase):
    def test_probe_prefers_configured_graalvm_java_without_host_secrets(self):
        import os
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp) / 'jdk'
            (home / 'bin').mkdir(parents=True)
            (home / 'bin' / 'java').write_bytes(b'fake Java executable')
            response = subprocess.CompletedProcess(
                args=[], returncode=0, stdout=json.dumps(INFO).encode(), stderr=b'')
            with patch.dict(os.environ, {
                'JAVA_HOME': str(home), 'GITHUB_TOKEN': 'canary',
                'JAVA_TOOL_OPTIONS': '-Dsecret=value',
            }):
                with patch.object(cb.subprocess, 'run', return_value=response) as proc:
                    self.assertEqual(cb.probe_info(Path(tmp) / 'run-oreslang', Path(tmp)), INFO)
                    env = proc.call_args.kwargs['env']
            self.assertEqual(env['PATH'], str(home / 'bin') + os.pathsep + os.defpath)
            self.assertNotIn('JAVA_TOOL_OPTIONS', env)
            self.assertNotIn('GITHUB_TOKEN', env)

    def test_invalid_java_home_fails_before_executing_launcher(self):
        import os
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(os.environ, {'JAVA_HOME': tmp}):
                with patch.object(cb.subprocess, 'run', side_effect=AssertionError('ran')):
                    with self.assertRaisesRegex(ValueError, 'JAVA_HOME'):
                        cb.probe_info(Path(tmp) / 'run-oreslang', Path(tmp))

    def test_workflow_positive_negative_checks_cannot_be_optimized_away(self):
        import os
        import re
        import textwrap
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'examples').mkdir()
            (root / 'examples' / 'together.ores').write_text('synthetic test')
            bundle = root / 'oreslang-compiler-bundle'
            bundle.mkdir()
            launcher = bundle / 'run-oreslang'
            (root / 'jdk' / 'bin').mkdir(parents=True)
            (root / 'jdk' / 'bin' / 'java').write_bytes(b'synthetic')
            workflow = (Path(__file__).resolve().parents[2] /
                        '.github/workflows/compiler-bundle-proof.yml').read_text()
            m = re.search(r"          python3 -I - <<'PYCODE'\n(.*?)\n          PYCODE", workflow, re.S)
            self.assertIsNotNone(m)
            script = textwrap.dedent(m.group(1))
            env = {'PATH': os.defpath, 'JAVA_HOME': str(root / 'jdk'),
                   'GITHUB_WORKSPACE': str(root), 'RUNNER_TEMP': str(root),
                   'PYTHONOPTIMIZE': '1'}
            # A launcher that fails every source check must never pass CI,
            # including when Python optimization is enabled.
            launcher.write_text('#!/bin/sh\nexit 1\n')
            launcher.chmod(0o700)
            failed = subprocess.run([sys.executable, '-O', '-I', '-c', script],
                                    env=env, cwd=root, capture_output=True, text=True)
            self.assertNotEqual(failed.returncode, 0)
            self.assertIn('positive .ores compilation failed', failed.stderr)
            # A fake CLI returning success to malformed code must also fail.
            launcher.write_text('#!/bin/sh\nexit 0\n')
            accepted = subprocess.run([sys.executable, '-O', '-I', '-c', script],
                                      env=env, cwd=root, capture_output=True, text=True)
            self.assertNotEqual(accepted.returncode, 0)
            self.assertIn('malformed source was not rejected', accepted.stderr)


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
        with zipfile.ZipFile(self.jar, 'w') as z:
            z.writestr('module-info.class', b'\xca\xfe\xba\xbe synthetic')
            z.writestr('dev/oreslang/launcher/OresMain.class', b'\xca\xfe\xba\xbe synthetic')
        self.deps = self.root / 'runtime-deps'
        self.deps.mkdir()
        with zipfile.ZipFile(self.deps / 'dep-a.jar', 'w') as z:
            z.writestr('com/example/Dependency.class', b'\xca\xfe\xba\xbe synthetic')
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

    def test_verify_does_not_execute_untrusted_launcher(self):
        self.create()
        marker = self.root / 'exec-marker'
        with patch.object(cb, 'probe_info', side_effect=AssertionError('executed untrusted code')):
            self.assertEqual(self.verify()['schema_version'], cb.SCHEMA)
        self.assertFalse(marker.exists())

    def test_optional_launcher_probe_must_be_explicit(self):
        self.create()
        with patch.object(cb, 'probe_info', side_effect=ValueError('untrusted launcher rejected')):
            self.verify()
            with self.assertRaisesRegex(ValueError, 'untrusted launcher rejected'):
                cb.verify_bundle(self.out, self.commit, self.tree, run_untrusted_launcher=True)

    def test_manifest_digest_pin_correct(self):
        self.create()
        expected = cb.digest(self.out / 'manifest.json')
        self.assertEqual(self.commit, cb.verify_bundle(
            self.out, self.commit, self.tree,
            expected_manifest_sha256=expected)['source_commit'])

    def test_manifest_digest_pin_mismatch_denied(self):
        self.create()
        with self.assertRaisesRegex(ValueError, 'pinned manifest SHA-256 mismatch'):
            cb.verify_bundle(self.out, self.commit, self.tree,
                             expected_manifest_sha256='0' * 64)

    def test_malformed_digest_pin_denied(self):
        self.create()
        with self.assertRaisesRegex(ValueError, 'invalid pinned manifest SHA-256'):
            cb.verify_bundle(self.out, self.commit, self.tree,
                             expected_manifest_sha256='invalid')

    def test_parent_path_symlink_bundle_denied(self):
        self.create()
        alias = self.root / 'alias-root'
        alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'symlink in path'):
            cb.verify_bundle(alias / 'bundle', self.commit, self.tree)

    def test_parent_path_symlink_source_denied(self):
        alias = self.root / 'alias-root'
        alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'symlink in path'):
            cb.check_source(alias / 'source', self.commit, self.tree)

    def test_source_skip_worktree_bit_denied(self):
        self.git('update-index', '--skip-worktree', 'pom.xml')
        with self.assertRaisesRegex(ValueError, 'masked Git index entry'):
            cb.check_source(self.source, self.commit, self.tree)

    def test_verify_json_never_probes_even_if_manifest_forged(self):
        self.create()
        self.change_receipt(lambda v: v['build_info'].update(
            build_mode='aot', default_execution_mode='aot',
            guest_execution='interpreter-no-jit'))
        with patch.object(cb, 'probe_info', side_effect=AssertionError('must not execute')):
            with self.assertRaisesRegex(ValueError, 'unsupported launcher build mode'):
                self.verify()

    def test_non_zip_main_jar_denied(self):
        self.jar.write_bytes(b'#!/bin/sh\nexit 0\n')
        with self.assertRaisesRegex(ValueError, 'not a valid JAR'):
            self.create()

    def test_main_jar_without_compiler_entrypoint_denied(self):
        with zipfile.ZipFile(self.jar, 'w') as z:
            z.writestr('module-info.class', b'synthetic')
        with self.assertRaisesRegex(ValueError, 'missing modular OresMain'):
            self.create()

    def test_non_zip_dependency_denied(self):
        (self.deps / 'dep-a.jar').write_bytes(b'not a ZIP archive')
        with self.assertRaisesRegex(ValueError, 'not a valid JAR'):
            self.create()

    def test_unlisted_zip_archive_member_path_denied(self):
        with zipfile.ZipFile(self.jar, 'w') as z:
            z.writestr('module-info.class', b'x')
            z.writestr('dev/oreslang/launcher/OresMain.class', b'x')
            z.writestr('../escape.class', b'bad')
        with self.assertRaisesRegex(ValueError, 'unsafe JAR member path'):
            self.create()

    def test_archive_with_executable_prefix_denied(self):
        self.jar.write_bytes(b'#!/bin/sh\nexit 0\n' + self.jar.read_bytes())
        with self.assertRaisesRegex(ValueError, 'executable prefix'):
            cb.jar_structure(self.jar, main=True)

    def test_jar_member_crc_corruption_denied(self):
        dep = self.deps / 'dep-a.jar'
        with zipfile.ZipFile(dep, 'w', compression=zipfile.ZIP_STORED) as z:
            z.writestr('payload.bin', b'original-payload')
        dep.write_bytes(dep.read_bytes().replace(b'original-payload',
                                                  b'tampered-payload'))
        with self.assertRaisesRegex(ValueError, 'not a valid JAR'):
            cb.jar_structure(dep)

    def test_symlink_in_jar_metadata_denied(self):
        dep = self.deps / 'dep-a.jar'
        with zipfile.ZipFile(dep, 'w') as z:
            info = zipfile.ZipInfo('link-to-external-file')
            info.create_system = 3
            info.external_attr = (stat.S_IFLNK | 0o777) << 16
            z.writestr(info, '/etc/passwd')
        with self.assertRaisesRegex(ValueError, 'non-regular JAR member'):
            cb.jar_structure(dep)

    def test_backslash_in_jar_member_denied(self):
        dep = self.deps / 'dep-a.jar'
        with zipfile.ZipFile(dep, 'w') as z:
            z.writestr(r'evil\path.class', b'x')
        with self.assertRaisesRegex(ValueError, 'unsafe JAR member path'):
            cb.jar_structure(dep)

    def test_individual_expanded_jar_member_limit_denied(self):
        with patch.object(cb, 'MAX_JAR_MEMBER', 8):
            with self.assertRaisesRegex(ValueError, 'oversized JAR member'):
                cb.jar_structure(self.jar, main=True)

    def test_total_expanded_jar_size_limit_denied(self):
        with patch.object(cb, 'MAX_JAR_EXPANDED', 8):
            with self.assertRaisesRegex(ValueError, 'expanded-content bound'):
                cb.jar_structure(self.jar, main=True)

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
        with self.assertRaisesRegex(ValueError, 'dependency/artifact bytes|not a valid JAR'):
            self.verify()

    def test_wrong_launcher_hash_denied(self):
        self.create()
        launcher = self.out / 'run-oreslang'
        launcher.write_text(launcher.read_text() + '# tampered\n')
        with self.assertRaisesRegex(ValueError, 'dependency/artifact bytes|not a valid JAR'):
            self.verify()

    def test_wrong_jar_hash_denied(self):
        self.create()
        (self.out / 'app.jar').write_bytes(b'changed jar')
        with self.assertRaisesRegex(ValueError, 'dependency/artifact bytes|not a valid JAR'):
            self.verify()

    def test_unlisted_dependency_denied(self):
        self.create()
        (self.out / 'deps' / 'unlisted.jar').write_bytes(b'missing from manifest')
        with self.assertRaisesRegex(ValueError, 'dependency/artifact bytes|not a valid JAR'):
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
