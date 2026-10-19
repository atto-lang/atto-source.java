import unittest
from pathlib import Path
import tempfile
import base64
import json
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import private_ci_pinned_admission as g

SRC='a'*40
POL='b'*40
TREE='c'*40
DST='trusted/ci'
ID=779

class Tests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.env={'ORES_PRIVATE_CI_REVIEWED_SOURCE_SHA':SRC,
                  'ORES_PRIVATE_CI_REVIEWED_POLICY_SHA':POL}
        self.policy={'version':1,'repositories':[{'name':DST,'id':ID}], 'source_commits':[SRC]}
        self.src={'id':g.SOURCE_ID,'full_name':g.SOURCE_REPO,
                  'visibility':'private','private':True,'archived':False,'disabled':False}
        self.dst={'id':ID,'full_name':DST,'visibility':'private',
                  'private':True,'archived':False,'disabled':False}
        self.protection={'required_pull_request_reviews':{'require_code_owner_reviews':True,'required_approving_review_count':1},'enforce_admins':{'enabled':True}}
        self.calls=[]
        self.git_dirty=False
    def fetch(self,route):
        self.calls.append(route)
        if route=='repos/'+g.SOURCE_REPO:return self.src
        if route=='repos/'+DST:return self.dst
        if route.endswith('/git/ref/heads/main'):return {'object':{'sha':POL}}
        if route.endswith('/branches/main/protection'):return self.protection
        if '/contents/' in route:
            return {'encoding':'base64','content':base64.b64encode(json.dumps(self.policy).encode()).decode()}
        if route.endswith('/git/commits/'+SRC):return {'tree':{'sha':TREE}}
        self.fail('unexpected route '+route)
    def git(self,root,*args):
        self.assertEqual(Path(root),self.root)
        if args==('rev-parse','HEAD'):return SRC
        if args==('rev-parse','HEAD^{tree}'):return TREE
        if args==('status','--porcelain','--untracked-files=all'):
            return 'M scripts/publisher.py' if self.git_dirty else ''
        self.fail('unexpected Git query')
    def verify(self):return g.verify(DST,self.root,self.fetch,env=self.env,git=self.git)
    def test_valid_pins_private_ids_clean_checkout(self):
        self.assertEqual(self.verify()['destination_id'],ID)
    def test_empty_allowlist_denies_without_export(self):
        self.policy['repositories']=[]
        with self.assertRaisesRegex(g.AdmissionDenied,'not independently approved'):
            self.verify()
    def test_caller_cannot_self_approve_arbitrary_source_sha(self):
        self.policy['source_commits'] = []
        with self.assertRaisesRegex(g.AdmissionDenied,'source commit absent'):
            self.verify()
        self.policy['source_commits'] = ['d' * 40]
        with self.assertRaisesRegex(g.AdmissionDenied,'source commit absent'):
            self.verify()
        self.policy['source_commits'] = [SRC, SRC]
        with self.assertRaisesRegex(g.AdmissionDenied,'source-commit approval'):
            self.verify()

    def test_wrong_or_missing_id_denies(self):
        for invalid in (None,0,True,'779',780):
            self.dst['id']=invalid
            with self.subTest(invalid=invalid),self.assertRaises(g.AdmissionDenied):
                self.verify()
        self.dst['id']=ID
        self.policy['repositories'][0]['id']=True
        with self.assertRaisesRegex(g.AdmissionDenied,'malformed'):
            self.verify()
    def test_public_or_deactivated_source_and_destination_denied(self):
        for doc in (self.src,self.dst):
            for key,value in [('visibility','public'),('private',False),('archived',True),('disabled',True)]:
                before=doc[key];doc[key]=value
                with self.subTest(key=key,value=value),self.assertRaises(g.AdmissionDenied):
                    self.verify()
                doc[key]=before
    def test_policy_commit_must_be_main_and_distinct(self):
        self.env['ORES_PRIVATE_CI_REVIEWED_POLICY_SHA']=SRC
        with self.assertRaisesRegex(g.AdmissionDenied,'conflated'):self.verify()
        self.env['ORES_PRIVATE_CI_REVIEWED_POLICY_SHA']='d'*40
        with self.assertRaisesRegex(g.AdmissionDenied,'not current'):self.verify()
    def test_missing_pins_denies_before_any_api_access(self):
        del self.env['ORES_PRIVATE_CI_REVIEWED_SOURCE_SHA']
        with self.assertRaises(g.AdmissionDenied):self.verify()
        self.assertEqual(self.calls,[])
    def test_requires_enforced_codeowner_protection(self):
        self.protection['required_pull_request_reviews']['require_code_owner_reviews']=False
        with self.assertRaisesRegex(g.AdmissionDenied,'protected'):self.verify()
    def test_source_tree_and_working_tree_must_be_exact(self):
        self.git_dirty=True
        with self.assertRaisesRegex(g.AdmissionDenied,'unreviewed changes'):self.verify()
        self.git_dirty=False
        original=self.git
        def wrong_tree(root,*args):
            if args==('rev-parse','HEAD^{tree}'):return 'f'*40
            return original(root,*args)
        with self.assertRaisesRegex(g.AdmissionDenied,'tree mismatch'):
            g.verify(DST,self.root,self.fetch,env=self.env,git=wrong_tree)
    def test_duplicate_json_keys_cannot_hide_a_shadow_approval(self):
        forged = b'{"version":1,"version":2,"repositories":[{"name":"trusted/ci","id":779}]}'
        def fetch(route):
            if '/contents/' in route:
                return {'encoding':'base64','content':base64.b64encode(forged).decode()}
            return self.fetch(route)
        with self.assertRaisesRegex(g.AdmissionDenied,'policy is invalid'):
            g.verify(DST,self.root,fetch,env=self.env,git=self.git)

    def test_duplicate_names_and_extra_keys_denied(self):
        self.policy['repositories'].append({'name':DST.upper(),'id':ID})
        with self.assertRaisesRegex(g.AdmissionDenied,'duplicate'):self.verify()
        self.policy['repositories'].pop()
        self.policy['unexpected']=True
        with self.assertRaisesRegex(g.AdmissionDenied,'schema'):self.verify()

class SnapshotTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.src=self.root / 'src'
        self.src.mkdir()
        self.data=b'example exact bytes\n'
        import hashlib
        digest=hashlib.sha1(b'blob '+str(len(self.data)).encode()+b'\0'+self.data).hexdigest()
        self.listing=(b'100644 blob '+digest.encode()+b'\tsrc/a.ores\0')

    def fake_run(self,argv,**kwargs):
        if argv[-2:]==['--','src']:
            return type('Proc',(),{'returncode':0,'stdout':self.listing})()
        if argv[-3:]==['rev-parse','--verify',SRC+':src']:
            return type('Proc',(),{'returncode':0,'stdout':('d'*40).encode()})()
        self.fail('unexpected Git command')


    def test_exact_source_blob_admitted(self):
        self.assertEqual(g.verify_snapshot(self.root,self.src,[('a.ores',self.data,'100644')],SRC,run=self.fake_run),'d'*40)

    def test_git_subtree_identity_missing_rejects_after_blob_comparison(self):
        def cannot_resolve(argv, **kwargs):
            if argv[-3:] == ['rev-parse','--verify',SRC+':src']:
                return type('Proc',(),{'returncode':1,'stdout':b''})()
            return self.fake_run(argv, **kwargs)
        with self.assertRaisesRegex(g.AdmissionDenied,'subtree identity unavailable'):
            g.verify_snapshot(self.root,self.src,[('a.ores',self.data,'100644')],SRC,run=cannot_resolve)

    def test_mutated_source_bytes_before_write_denied(self):
        with self.assertRaisesRegex(g.AdmissionDenied,'bytes differ'):
            g.verify_snapshot(self.root,self.src,[('a.ores',b'changed','100644')],SRC,run=self.fake_run)

    def test_different_or_missing_file_or_mode_denied(self):
        for files in ([],[('evil.ores',self.data,'100644')],[('a.ores',self.data,'100755')]):
            with self.subTest(files=files),self.assertRaises(g.AdmissionDenied):
                g.verify_snapshot(self.root,self.src,files,SRC,run=self.fake_run)


class ExactTreeRealGitTests(unittest.TestCase):
    """Validate Git CLI semantics with real trees; no GitHub/network required."""
    def setUp(self):
        import subprocess
        self.git_process = subprocess
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'src' / 'nested').mkdir(parents=True)
        (self.root / 'src' / 'a.ores').write_bytes(b'hello\n')
        (self.root / 'src' / 'nested' / 'b.ores').write_bytes(b'world\n')
        self.git('init', '-q')
        self.git('config', 'user.name', 'Oreslang CI')
        self.git('config', 'user.email', 'ci@invalid.example')
        self.git('add', '.')
        self.git('commit', '-qm', 'test fixtures')
        self.commit = self.git('rev-parse', 'HEAD').decode()
        self.tree = self.git('rev-parse', 'HEAD:src').decode()
        self.files = [('a.ores', b'hello\n', '100644'),
                      ('nested/b.ores', b'world\n', '100644')]

    def git(self, *args):
        return self.git_process.run(['git', '-C', str(self.root), *args],
                                    capture_output=True, check=True).stdout.strip()

    def test_real_git_subtree_matches_pinned_byte_and_mode_ledger(self):
        self.assertEqual(
            g.verify_snapshot(self.root, self.root / 'src', self.files, self.commit),
            self.tree)

    def test_real_git_rejects_mutated_or_missing_source(self):
        with self.assertRaisesRegex(g.AdmissionDenied, 'bytes differ'):
            g.verify_snapshot(self.root, self.root / 'src',
                [('a.ores', b'changed\n', '100644'), self.files[1]], self.commit)
        with self.assertRaisesRegex(g.AdmissionDenied, 'file set'):
            g.verify_snapshot(self.root, self.root / 'src',
                self.files[:1], self.commit)
        with self.assertRaisesRegex(g.AdmissionDenied, 'mode'):
            g.verify_snapshot(self.root, self.root / 'src',
                [('a.ores', b'hello\n', '100755'), self.files[1]], self.commit)


if __name__ == '__main__':
    unittest.main()
