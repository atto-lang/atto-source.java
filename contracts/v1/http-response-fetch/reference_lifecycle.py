"""Reference state machine only. NOT an Oreslang runtime or backend test."""
class Lease:
    def __init__(self, metadata):
        self.metadata = metadata
        self.state = "NEW"
        self.terminals = 0
    def step(self, action):
        transitions = {
            ("NEW", "handoff"): "HANDED_OFF",
            ("HANDED_OFF", "commit"): "HEADERS_COMMITTED",
            ("HEADERS_COMMITTED", "write"): "BODY_ACTIVE",
            ("BODY_ACTIVE", "write"): "BODY_ACTIVE",
            ("HEADERS_COMMITTED", "eof"): "TRAILERS" if self.metadata.trailers else "COMPLETE",
            ("BODY_ACTIVE", "eof"): "TRAILERS" if self.metadata.trailers else "COMPLETE",
            ("TRAILERS", "trailers"): "COMPLETE",
        }
        if action == "abort" and self.state not in ("ABORTED","COMPLETE"):
            target = "ABORTED"
        else:
            target = transitions.get((self.state, action))
        if target is None:
            raise ValueError("illegal transition")
        if action=="write" and (not self.metadata.has_body or self.metadata.head):
            raise ValueError("body forbidden")
        if action=="trailers" and not self.metadata.trailers:
            raise ValueError("undeclared trailers")
        if action=="eof" and (not self.metadata.has_body or self.metadata.head):
            target = "COMPLETE"
        self.state=target
        if target in ("ABORTED","COMPLETE"):
            self.terminals+=1
        if self.terminals>1:
            raise ValueError("double terminal")
