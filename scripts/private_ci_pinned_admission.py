"""Additional fail-closed source identity and protected destination-ID gate.

This is necessary, not sufficient, to authorize mirroring: reviewers must also
verify protected-branch controls and approve the intended source/data transfer.
"""
import base64
import binascii
import json
import os
from pathlib import Path
import re
import subprocess

SOURCE_REPO = 'ores-truffle-oreslang/oreslang-source.java'
SOURCE_ID = 1400792274
POLICY_PATH = '.github/private-ci-approved-destinations.json'
SHA = re.compile(r'\A[a-f0-9]{40}\Z')
NAME = re.compile(r'\A[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*\Z')

class AdmissionDenied(RuntimeError):
    pass


def require_sha(value, name):
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise AdmissionDenied(name + ' missing or malformed')
    return value


def check_identity(doc, expected_name, expected_id):
    if (not isinstance(doc, dict) or type(doc.get('id')) is not int
        or doc['id'] != expected_id or not isinstance(doc.get('full_name'), str)
        or doc['full_name'].casefold() != expected_name.casefold()
        or doc.get('visibility') != 'private' or doc.get('private') is not True
        or doc.get('archived') is not False or doc.get('disabled') is not False):
        raise AdmissionDenied('unverified private GitHub repository identity')


def git_value(root, *args, run=subprocess.run):
    try:
        p = run(['git','-C',str(root),*args], capture_output=True, text=True,
                timeout=20, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise AdmissionDenied('local Git provenance is unavailable') from None
    if p.returncode != 0:
        raise AdmissionDenied('local Git provenance check failed')
    return p.stdout.strip()


def verify(repository, trusted_root, fetch, *, env=None, git=git_value):
    env = os.environ if env is None else env
    if not isinstance(repository, str) or not NAME.fullmatch(repository):
        raise AdmissionDenied('invalid destination name')
    expected_source = require_sha(env.get('ORES_PRIVATE_CI_REVIEWED_SOURCE_SHA'), 'reviewed source SHA')
    expected_policy = require_sha(env.get('ORES_PRIVATE_CI_REVIEWED_POLICY_SHA'), 'reviewed policy SHA')
    if expected_source == expected_policy:
        # Source test commit and reviewed policy commit must be independently selected.
        raise AdmissionDenied('source and policy pins may not be conflated')

    check_identity(fetch('repos/' + SOURCE_REPO), SOURCE_REPO, SOURCE_ID)
    main = fetch('repos/' + SOURCE_REPO + '/git/ref/heads/main')
    if not isinstance(main,dict) or main.get('object',{}).get('sha') != expected_policy:
        raise AdmissionDenied('reviewed policy is not current on source main')

    protected = fetch('repos/' + SOURCE_REPO + '/branches/main/protection')
    try:
        reviews = protected['required_pull_request_reviews']
        if (reviews['require_code_owner_reviews'] is not True
            or type(reviews['required_approving_review_count']) is not int
            or reviews['required_approving_review_count'] < 1
            or protected['enforce_admins']['enabled'] is not True):
            raise AdmissionDenied('protected code-owner review rules unavailable')
    except (KeyError, TypeError):
        raise AdmissionDenied('protected code-owner review rules unavailable') from None

    path = 'repos/' + SOURCE_REPO + '/contents/' + POLICY_PATH + '?ref=' + expected_policy
    policy_blob = fetch(path)
    if not isinstance(policy_blob,dict) or policy_blob.get('encoding') != 'base64':
        raise AdmissionDenied('reviewed numeric-ID policy is unavailable')
    try:
        encoded = policy_blob.get('content', '')
        if not isinstance(encoded,str) or len(encoded) > 32768:
            raise ValueError('oversized')
        raw = base64.b64decode(''.join(encoded.split()), validate=True)
        if len(raw) > 16384 or not raw:
            raise ValueError('invalid length')
        policy = json.loads(raw)
    except (ValueError, binascii.Error, UnicodeError, TypeError):
        raise AdmissionDenied('reviewed numeric-ID policy is invalid') from None
    if (not isinstance(policy,dict) or set(policy) != {'version','repositories'}
        or type(policy['version']) is not int or policy['version'] != 1
        or not isinstance(policy['repositories'],list) or len(policy['repositories']) > 64):
        raise AdmissionDenied('invalid numeric-ID policy schema')
    approved = {}
    for entry in policy['repositories']:
        if (not isinstance(entry,dict) or set(entry) != {'name','id'}
            or not isinstance(entry['name'],str) or not NAME.fullmatch(entry['name'])
            or type(entry['id']) is not int or entry['id'] <= 0
            or entry['name'].casefold() in approved):
            raise AdmissionDenied('duplicate or malformed destination approval')
        approved[entry['name'].casefold()] = entry['id']
    dest_id = approved.get(repository.casefold())
    if dest_id is None or dest_id == SOURCE_ID:
        raise AdmissionDenied('destination not independently approved by numeric ID')
    check_identity(fetch('repos/' + repository), repository, dest_id)

    remote = fetch('repos/' + SOURCE_REPO + '/git/commits/' + expected_source)
    try:
        remote_tree = require_sha(remote['tree']['sha'], 'source tree')
    except (KeyError, TypeError):
        raise AdmissionDenied('source commit tree missing') from None
    root = Path(trusted_root)
    if root.is_symlink() or not root.is_dir():
        raise AdmissionDenied('source checkout root is not trustworthy')
    if git(root,'rev-parse','HEAD') != expected_source:
        raise AdmissionDenied('unreviewed source checkout commit')
    if git(root,'rev-parse','HEAD^{tree}') != remote_tree:
        raise AdmissionDenied('source tree mismatch')
    if git(root,'status','--porcelain','--untracked-files=all'):
        raise AdmissionDenied('source checkout contains unreviewed changes')
    return {'source_sha':expected_source, 'source_tree':remote_tree,
            'policy_sha':expected_policy, 'destination_id':dest_id}
