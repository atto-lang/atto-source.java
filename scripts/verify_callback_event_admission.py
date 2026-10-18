#!/usr/bin/env python3
"""Run TLC's safe model and require counterexamples for three broken protocols."""
import argparse
import pathlib
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--tlc-jar', required=True, type=pathlib.Path)
parser.add_argument('--output', required=True, type=pathlib.Path)
args = parser.parse_args()
repo = pathlib.Path(__file__).resolve().parents[1]
args.output.mkdir(parents=True, exist_ok=True)
base = (repo / 'docs/formal/CallbackEventAdmission.cfg').read_text()
for name, mutation, expected in [
    ('safe', None, 'Model checking completed. No error has been found.'),
    ('early-event', 'AllowEarlyEvent', 'Invariant NoZalgo is violated'),
    ('inline-event', 'AllowInlineEvent', 'Invariant NoInlineEvent is violated'),
    ('revoked-carrier', 'RevokeOnStop', 'Invariant LeaseMatchesCarrier is violated'),
]:
    with tempfile.TemporaryDirectory(prefix='ores-callback-tlc-', dir='/tmp') as tmp:
        config = pathlib.Path(tmp) / 'CallbackEventAdmission.cfg'
        (pathlib.Path(tmp) / 'CallbackEventAdmission.tla').write_text(
            (repo / 'docs/formal/CallbackEventAdmission.tla').read_text())
        config.write_text(base if mutation is None else base.replace(
            f'{mutation} = FALSE', f'{mutation} = TRUE'))
        result = subprocess.run([
            'java', '-XX:+UseParallelGC', '-cp', str(args.tlc_jar.resolve()),
            'tlc2.TLC', '-workers', '1', '-metadir', str(pathlib.Path(tmp) / 'states'),
            '-config', config.name, 'CallbackEventAdmission.tla',
        ], cwd=tmp, capture_output=True, text=True, timeout=60)
        output = result.stdout + result.stderr
        (args.output / f'{name}.log').write_text(output)
        if expected not in output or (mutation is None and result.returncode != 0):
            raise SystemExit(f'{name}: unexpected TLC result; see {args.output}')
        print(f'{name}: {expected}')
