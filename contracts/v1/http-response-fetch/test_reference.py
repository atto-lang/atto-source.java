"""Design-only policy checks; not Oreslang backend conformance."""
import sys
import random
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from reference_headers import Reject, fields, names, validate
from reference_lifecycle import Lease

class PolicyTest(unittest.TestCase):
    def bad(self, fn, *args, **kwargs):
        with self.assertRaises((Reject, ValueError)):
            fn(*args, **kwargs)

    def test_status(self):
        for n in (200, 201, 304, 599):
            self.assertEqual(n,validate(status=n).status)
        for n in (0,199,600,True,200.0):
            self.bad(validate,status=n)
        for n in (204,205,304):
            self.bad(validate,status=n,has_body=True)

    def test_headers(self):
        self.assertEqual(2,len(fields([("Set-Cookie","x=1"),("set-cookie","y=2")])))
        for n in ("Content-Length","TE","Trailer","Connection",":status","Bad Name"):
            self.bad(fields,[(n,"value")])
        for v in ("a\r\nb","\x00","\t","\x7f","\u0100"):
            self.bad(fields,[("X-Test",v)])
        self.bad(fields,[("X","y")]*101)

    def test_trailers(self):
        self.assertEqual(("content-digest",),names(["Content-Digest"]))
        self.bad(names,["Location"])
        self.bad(names,["Content-Digest","content-digest"])
        opts=dict(has_body=True,trailer_names=["Content-Digest"],
                  trailer_future=True,supports_trailers=True,client_te_trailers=True)
        self.assertEqual(("content-digest",),validate(**opts).trailers)
        self.bad(validate,**{**opts,"client_te_trailers":False})
        self.bad(validate,**{**opts,"method":"HEAD"})
        self.bad(fields,[("Location","/")],trailers=True,declared=("location",))

    def test_lifecycle(self):
        obj=Lease(validate(has_body=True))
        for action in ("handoff","commit","write","eof"): obj.step(action)
        self.assertEqual(("COMPLETE",1),(obj.state,obj.terminals))
        self.bad(obj.step,"eof"); self.bad(obj.step,"abort")
        head=Lease(validate(method="HEAD",has_body=True))
        head.step("handoff"); head.step("commit")
        self.bad(head.step,"write"); head.step("eof")
        self.assertEqual("COMPLETE",head.state)

    def test_abort(self):
        for path in ((),("handoff",),("handoff","commit"),
                     ("handoff","commit","write")):
            obj=Lease(validate(has_body=True))
            for event in path: obj.step(event)
            obj.step("abort")
            self.assertEqual(1,obj.terminals)
            self.bad(obj.step,"abort")

    def test_deterministic_fuzz(self):
        random.seed(20261018)
        for _ in range(250):
            self.bad(fields,[("X-Test","a"+random.choice(("\r","\n","\x00","\u0100")))])
            obj=Lease(validate(has_body=True))
            for _ in range(12):
                try: obj.step(random.choice(("handoff","commit","write","eof","abort")))
                except ValueError: pass
                self.assertLessEqual(obj.terminals,1)

if __name__=="__main__":
    unittest.main()
