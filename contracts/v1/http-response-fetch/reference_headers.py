"""Design-only HTTP metadata checks; never evidence of backend conformance."""
import re
from dataclasses import dataclass

class Reject(ValueError):
    pass

TOKEN = re.compile("[!#$%&'*+.^_" + chr(96) + "|~0-9A-Za-z-]+$")
OWNED = {"connection", "content-length", "transfer-encoding", "trailer",
         "te", "upgrade", "keep-alive", "proxy-connection"}
TRAILER_OK = {"content-digest", "repr-digest", "server-timing"}

def name(s):
    if not isinstance(s, str) or not TOKEN.fullmatch(s):
        raise Reject("bad field name")
    return s.lower()

def fields(pairs, *, trailers=False, declared=()):
    out, total = [], 0
    for raw, v in pairs:
        k = name(raw)
        if not isinstance(v, str) or any(ord(x)<32 or ord(x)==127 or ord(x)>255 for x in v):
            raise Reject("bad field value")
        if k in OWNED or (trailers and (k not in declared or k not in TRAILER_OK)):
            raise Reject("forbidden field")
        total += len(raw) + len(v) + 4
        out.append((k, v))
        if len(out)>100 or total>32768:
            raise Reject("field budget")
    return tuple(out)

def names(values):
    out = []
    for value in values:
        k = name(value)
        if k not in TRAILER_OK or k in out:
            raise Reject("forbidden or duplicate trailer")
        out.append(k)
    return tuple(out)

@dataclass(frozen=True)
class Metadata:
    status: int
    headers: tuple
    trailers: tuple
    has_body: bool
    head: bool

def validate(*, status=200, headers=(), has_body=False, method="GET",
             trailer_names=(), trailer_future=False, supports_trailers=False,
             client_te_trailers=False, protocol="h1"):
    if type(status) is not int or status<200 or status>599:
        raise Reject("status")
    if type(has_body) is not bool or type(trailer_future) is not bool:
        raise Reject("body/future type")
    if has_body and status in (204,205,304):
        raise Reject("body on null-body status")
    if method not in {"GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS"}:
        raise Reject("method")
    if protocol not in {"h1","h2","h3"}:
        raise Reject("protocol")
    declared = names(trailer_names)
    if bool(declared) != trailer_future:
        raise Reject("trailer declaration mismatch")
    if declared and (not has_body or method=="HEAD" or not supports_trailers):
        raise Reject("trailer not supported")
    if declared and protocol=="h1" and not client_te_trailers:
        raise Reject("missing HTTP/1.1 TE trailers")
    return Metadata(status, fields(headers), declared, has_body, method=="HEAD")
