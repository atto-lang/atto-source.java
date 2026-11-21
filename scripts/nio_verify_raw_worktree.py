#!/usr/bin/env python3
"""Source-local raw worktree identity: bypass Git clean filters and aliases.

Git status is insufficient: .git/info/attributes and filter.*.clean can make
compiler-visible altered bytes appear identical to HEAD. This separately hashes
tracked file bytes against Git's staged blob identities.
"""
from __future__ import annotations

import hashlib
import os
import re
import stat
import subprocess
import sys
from pathlib import Path

SHA1 = re.compile(r'[0-9a-f]{40}\Z', re.ASCII)
OVERRIDES = ('GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR',
             'GIT_CONFIG_PARAMETERS', 'GIT_CONFIG_COUNT')


def git(*args: str) -> bytes:
    return subprocess.check_output(('git', '-c', 'core.fileMode=true',
                                    '-c', 'core.fsmonitor=false', *args),
                                   stderr=subprocess.PIPE)


def raw_digest(fd: int, size: int) -> bytes:
    digest = hashlib.sha1()
    digest.update(b'blob ' + str(size).encode('ascii') + b'\0')
    while True:
        chunk = os.read(fd, 1024 * 1024)
        if not chunk:
            break
        digest.update(chunk)
    return digest.hexdigest().encode('ascii')


def secure_parent_dir(path: bytes) -> tuple[int, bytes]:
    """Open every parent component beneath cwd without following symlinks."""
    nofollow = getattr(os, 'O_NOFOLLOW', 0)
    directory = getattr(os, 'O_DIRECTORY', 0)
    cloexec = getattr(os, 'O_CLOEXEC', 0)
    if not nofollow or not directory:
        raise ValueError('platform lacks O_NOFOLLOW/O_DIRECTORY exact-tree safety')
    if (os.open not in os.supports_dir_fd
            or os.stat not in os.supports_dir_fd
            or os.readlink not in os.supports_dir_fd):
        raise ValueError('platform lacks dir_fd exact-tree safety')
    parts = path.split(b'/')
    if not parts or any(part in (b'', b'.', b'..') for part in parts):
        raise ValueError('noncanonical tracked path: ' + repr(path[:120]))
    flags = os.O_RDONLY | directory | nofollow | cloexec
    fd = os.open(b'.', flags)
    try:
        for part in parts[:-1]:
            try:
                next_fd = os.open(part, flags, dir_fd=fd)
            except OSError as exc:
                raise ValueError(
                    'tracked path has symlinked/non-directory ancestor: '
                    + repr(path[:120])) from exc
            os.close(fd)
            fd = next_fd
        return fd, parts[-1]
    except BaseException:
        os.close(fd)
        raise


def verify(commit: str, tree: str) -> int:
    if not SHA1.fullmatch(commit) or not SHA1.fullmatch(tree):
        raise ValueError('expected commit/tree must be canonical SHA-1')
    present = [name for name in OVERRIDES if name in os.environ]
    if present:
        raise ValueError('Git checkout overrides forbidden: ' + ', '.join(present))
    top = Path(os.fsdecode(git('rev-parse', '--show-toplevel').strip())).resolve()
    if top != Path.cwd().resolve():
        raise ValueError('must verify the root of the Git worktree')
    observed = (git('rev-parse', '--verify', 'HEAD^{commit}').decode().strip(),
                git('rev-parse', '--verify', 'HEAD^{tree}').decode().strip())
    if observed != (commit, tree):
        raise ValueError('checkout commit/tree changed during verification')
    # Compare HEAD tree metadata to the staged index without invoking filters.
    # Otherwise a staged-but-uncommitted tree could be mistaken for HEAD.
    head_entries = {}
    for item in git('ls-tree', '-r', '-z', '--full-tree', 'HEAD').split(b'\0'):
        if not item:
            continue
        metadata, tab, path = item.partition(b'\t')
        fields = metadata.split(b' ')
        if tab != b'\t' or not path or len(fields) != 3:
            raise ValueError('invalid HEAD tree entry')
        mode, kind, oid = fields
        if kind not in (b'blob', b'commit') or path in head_entries:
            raise ValueError('unexpected HEAD tree entry')
        head_entries[path] = (mode, oid)
    total = 0
    for entry in git('ls-files', '--stage', '-z').split(b'\0'):
        if not entry:
            continue
        metadata, tab, path = entry.partition(b'\t')
        fields = metadata.split(b' ')
        if tab != b'\t' or not path or len(fields) != 3 or fields[2] != b'0':
            raise ValueError('invalid or unmerged Git index entry')
        mode, expected_blob, _stage = fields
        if head_entries.pop(path, None) != (mode, expected_blob):
            raise ValueError('staged index differs from HEAD tree: ' + repr(path[:120]))
        if mode in (b'100644', b'100755'):
            parent_fd, leaf = secure_parent_dir(path)
            try:
                fd = os.open(
                    leaf,
                    os.O_RDONLY | os.O_NOFOLLOW | getattr(os, 'O_CLOEXEC', 0),
                    dir_fd=parent_fd,
                )
                try:
                    st = os.fstat(fd)
                    if not stat.S_ISREG(st.st_mode):
                        raise ValueError('tracked input is not regular: ' + repr(path[:120]))
                    # A tracked file hard-linked to a path outside this checkout
                    # can be mutated after identity verification without touching
                    # the admitted pathname. Exact-tree qualification therefore
                    # accepts only a single-link regular inode.
                    if st.st_nlink != 1:
                        raise ValueError(
                            'tracked input has multiple hard links: ' + repr(path[:120]))
                    if bool(st.st_mode & 0o111) != (mode == b'100755'):
                        raise ValueError('tracked mode differs: ' + repr(path[:120]))
                    actual_blob = raw_digest(fd, st.st_size)
                    after = os.fstat(fd)
                    if (st.st_size, st.st_mtime_ns, st.st_ctime_ns, st.st_nlink) != (
                            after.st_size, after.st_mtime_ns, after.st_ctime_ns, after.st_nlink):
                        raise ValueError(
                            'tracked source changed during hashing: ' + repr(path[:120]))
                finally:
                    os.close(fd)
            finally:
                os.close(parent_fd)
        elif mode == b'120000':
            parent_fd, leaf = secure_parent_dir(path)
            try:
                st = os.stat(leaf, dir_fd=parent_fd, follow_symlinks=False)
                if not stat.S_ISLNK(st.st_mode):
                    raise ValueError('tracked symbolic link changed type: ' + repr(path[:120]))
                target = os.readlink(leaf, dir_fd=parent_fd)
            finally:
                os.close(parent_fd)
            if isinstance(target, str):
                target = os.fsencode(target)
            actual_blob = hashlib.sha1(b'blob ' + str(len(target)).encode('ascii')
                                       + b'\0' + target).hexdigest().encode('ascii')
        else:
            raise ValueError('unsupported tracked mode: ' + repr(mode))
        if actual_blob != expected_blob:
            raise ValueError('raw tracked blob mismatch: ' + repr(path[:120]))
        total += 1
    if head_entries:
        raise ValueError('tracked HEAD tree entries missing from index')
    if not total:
        raise ValueError('empty Git index cannot certify a source tree')
    return total


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print('usage: nio_verify_raw_worktree.py COMMIT TREE', file=sys.stderr)
        return 64
    try:
        count = verify(argv[1], argv[2])
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        print('ERROR: NIO raw source identity: ' + str(exc), file=sys.stderr)
        return 77
    print(f'PASS: {count} raw tracked files match staged Git blobs')
    return 0


if __name__ == '__main__':
    raise SystemExit(main(sys.argv))
