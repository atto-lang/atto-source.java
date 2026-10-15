import io
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import ci_private_mirror_preflight as admission


class FakeResponse:
    status = 200

    def __init__(self, url, body):
        self.url = url
        self.body = body

    def __enter__(self):
        return self

    def __exit__(self, *_):
        pass

    def geturl(self):
        return self.url

    def read(self, length):
        return self.body[:length]


def metadata(name, repo_id, **extra):
    return {"private": True, "visibility": "private", "full_name": name, "id": repo_id,
            "archived": False, "disabled": False, **extra}


class MirrorGateTest(unittest.TestCase):
    def test_private_source_and_destination_with_pinned_ids_admitted(self):
        def fetch(name, _token):
            return metadata(name, 21 if name == "private/src" else 34)
        self.assertTrue(admission.verify("private/src", "private/dst", 21, 34, "source", "dest", fetch=fetch))

    def test_public_or_unknown_visibility_rejected_for_both_sides(self):
        for side in ("private/src", "private/dst"):
            for bad in ({"visibility": "public", "private": False},
                        {"visibility": None}, {"private": None}, {"visibility": "internal"}):
                def fetch(name, _token):
                    doc = metadata(name, 21 if name == "private/src" else 34)
                    if name == side:
                        doc.update(bad)
                    return doc
                with self.subTest(side=side, bad=bad):
                    with self.assertRaises(admission.MirrorPreflightError):
                        admission.verify("private/src", "private/dst", 21, 34, "a", "b", fetch=fetch)

    def test_mismatched_names_ids_disabled_and_archived_rejected(self):
        for bad in ({"id": 99}, {"id": True}, {"full_name": "other/dst"},
                    {"archived": True}, {"disabled": True}):
            def fetch(name, _token):
                result = metadata(name, 21 if name == "private/src" else 34)
                if name == "private/dst":
                    result.update(bad)
                return result
            with self.subTest(bad=bad), self.assertRaises(admission.MirrorPreflightError):
                admission.verify("private/src", "private/dst", 21, 34, "a", "b", fetch=fetch)

    def test_missing_or_unrecognized_active_status_rejected_on_both_sides(self):
        for side in ("private/src", "private/dst"):
            for field in ("archived", "disabled"):
                for value in (None, "false", 0, 1, True):
                    with self.subTest(side=side, field=field, value=value):
                        def fetch(name, _token):
                            result = metadata(name, 21 if name == "private/src" else 34)
                            if name == side:
                                result[field] = value
                            return result
                        with self.assertRaisesRegex(admission.MirrorPreflightError, "active status"):
                            admission.verify("private/src", "private/dst", 21, 34, "a", "b", fetch=fetch)
                with self.subTest(side=side, field=field, value="absent"):
                    def fetch(name, _token):
                        result = metadata(name, 21 if name == "private/src" else 34)
                        if name == side:
                            del result[field]
                        return result
                    with self.assertRaisesRegex(admission.MirrorPreflightError, "active status"):
                        admission.verify("private/src", "private/dst", 21, 34, "a", "b", fetch=fetch)

    def test_invalid_pinned_ids_rejected_before_any_network(self):
        for invalid in (None, True, False, 0, -1, 3.5, "21", [], {}):
            for side in ("source", "destination"):
                with self.subTest(invalid=invalid, side=side):
                    calls = []
                    def fetch(name, _token):
                        calls.append(name)
                        return metadata(name, 21 if name == "private/src" else 34)
                    src_id = invalid if side == "source" else 21
                    dst_id = invalid if side == "destination" else 34
                    with self.assertRaisesRegex(admission.MirrorPreflightError, "numeric repository identities"):
                        admission.verify("private/src", "private/dst", src_id, dst_id,
                                         "a", "b", fetch=fetch)
                    self.assertEqual(calls, [])

    def test_missing_token_rejected_before_network(self):
        with self.assertRaisesRegex(admission.MirrorPreflightError, "authenticated"):
            admission.fetch_metadata("private/dst", "")

    def test_malformed_tokens_fail_before_an_http_request(self):
        malformed = (None, "", " ", " has-space", "trailing-space ",
                     "line\\nsecond", "carriage\\rreturn", "tab\\tvalue",
                     "nonascii-雪", "bad:colon", "A" * (admission.MAX_TOKEN_LENGTH + 1),
                     123, False)
        for token in malformed:
            with self.subTest(token_type=type(token).__name__, token_len=len(token) if isinstance(token, str) else 0):
                called = []
                def opener(req, timeout):
                    called.append(req)
                    raise AssertionError("malformed credential reached HTTP")
                with self.assertRaisesRegex(admission.MirrorPreflightError, "authenticated"):
                    admission.fetch_metadata("private/src", token, opener=opener)
                self.assertEqual(called, [])

    def test_both_tokens_required_before_either_metadata_fetch(self):
        for bad_side in ("source", "destination"):
            with self.subTest(bad_side=bad_side):
                calls = []
                def fetch(name, token):
                    calls.append(name)
                    return metadata(name, 21 if name == "private/src" else 34)
                source_token = "a" if bad_side != "source" else "bad\\nheader"
                destination_token = "b" if bad_side != "destination" else None
                with self.assertRaisesRegex(admission.MirrorPreflightError, "authenticated"):
                    admission.verify("private/src", "private/dst", 21, 34,
                                     source_token, destination_token, fetch=fetch)
                self.assertEqual(calls, [])

    def test_noncredential_bearer_error_is_sanitized(self):
        key = "SECRET_CANARY"
        def opener(req, timeout):
            raise ValueError("malformed header " + key)
        with self.assertRaisesRegex(admission.MirrorPreflightError, "metadata unavailable") as caught:
            admission.fetch_metadata("private/src", key, opener=opener)
        self.assertNotIn(key, str(caught.exception))

    def test_path_traversal_and_bad_names_refused(self):
        for name in ("foo", "../dst", "foo/../dst", "foo/dst?x=1", "foo/dst#tag", "/dst", "src/dst/%2F"):
            with self.subTest(name=name), self.assertRaises(admission.MirrorPreflightError):
                admission.validate_repository_name(name)

    def test_http_response_is_bounded_and_credentials_not_reflected(self):
        key = "TEST_SECRET_CANARY"
        def wrong_url(req, timeout):
            self.assertEqual(req.get_header("Authorization"), "Bearer " + key)
            self.assertEqual(timeout, 8)
            return FakeResponse("https://malicious.test/repo", b"{}")
        with self.assertRaises(admission.MirrorPreflightError) as x:
            admission.fetch_metadata("private/dst", key, opener=wrong_url)
        self.assertNotIn(key, str(x.exception))

        def huge(req, timeout):
            return FakeResponse(req.full_url, b"A" * (admission.MAX_RESPONSE_BYTES + 1))
        with self.assertRaisesRegex(admission.MirrorPreflightError, "too large"):
            admission.fetch_metadata("private/dst", key, opener=huge)

    def test_malformed_metadata_rejected(self):
        def bad(req, timeout):
            return FakeResponse(req.full_url, b"invalid JSON")
        with self.assertRaisesRegex(admission.MirrorPreflightError, "invalid repository metadata"):
            admission.fetch_metadata("private/dst", "test", opener=bad)

    def test_cli_missing_tokens_fails_closed_without_echoing_values(self):
        args = ["--source", "private/src", "--destination", "private/dst",
                "--source-id", "21", "--destination-id", "34"]
        stderr = io.StringIO()
        with patch.dict("os.environ", {"ORES_SOURCE_READ_TOKEN": "", "ORES_DEST_READ_TOKEN": ""}), patch("sys.stderr", stderr):
            self.assertEqual(admission.main(args), 1)
        self.assertIn("DENIED", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
