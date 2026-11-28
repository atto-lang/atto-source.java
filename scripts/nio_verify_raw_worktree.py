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


def open_tracked_leaf(path: bytes, nofollow: int, nonblock: int) -> int:
    """Open a tracked leaf beneath cwd without following any path symlink."""
    parts = path.split(b'/')
    if not parts or any(part in (b'', b'.', b'..') for part in parts):
        raise ValueError('invalid tracked path: ' + repr(path[:120]))
    directory_flag = getattr(os, 'O_DIRECTORY', None)
    if (not isinstance(directory_flag, int) or isinstance(directory_flag, bool)
            or directory_flag <= 0
            or (directory_flag & nofollow) != 0
            or (directory_flag & nonblock) != 0):
        raise ValueError('platform cannot safely traverse tracked source paths')
    dir_fd = os.open(b'.', os.O_RDONLY | directory_flag | nofollow)
    try:
        for component in parts[:-1]:
            next_fd = os.open(component,
                              os.O_RDONLY | directory_flag | nofollow,
                              dir_fd=dir_fd)
            os.close(dir_fd)
            dir_fd = next_fd
        return os.open(parts[-1], os.O_RDONLY | nofollow | nonblock,
                       dir_fd=dir_fd)
    finally:
        os.close(dir_fd)


def read_tracked_symlink(path: bytes, nofollow: int) -> bytes:
    """Read a tracked symlink through no-follow parent directory descriptors."""
    parts = path.split(b'/')
    if not parts or any(part in (b'', b'.', b'..') for part in parts):
        raise ValueError('invalid tracked path: ' + repr(path[:120]))
    directory_flag = getattr(os, 'O_DIRECTORY', None)
    if (not isinstance(directory_flag, int) or isinstance(directory_flag, bool)
            or directory_flag <= 0 or (directory_flag & nofollow) != 0):
        raise ValueError('platform cannot safely traverse tracked symlink paths')
    dir_fd = os.open(b'.', os.O_RDONLY | directory_flag | nofollow)
    try:
        for component in parts[:-1]:
            next_fd = os.open(component,
                              os.O_RDONLY | directory_flag | nofollow,
                              dir_fd=dir_fd)
            os.close(dir_fd)
            dir_fd = next_fd
        leaf = parts[-1]
        before = os.stat(leaf, dir_fd=dir_fd, follow_symlinks=False)
        if not stat.S_ISLNK(before.st_mode):
            raise ValueError('tracked symbolic link changed type: ' + repr(path[:120]))
        if before.st_nlink != 1:
            raise ValueError('multiply-linked tracked symbolic link: ' + repr(path[:120]))
        target = os.readlink(leaf, dir_fd=dir_fd)
        if isinstance(target, str):
            target = os.fsencode(target)
        after = os.stat(leaf, dir_fd=dir_fd, follow_symlinks=False)
        if (before.st_size, before.st_mtime_ns, before.st_ctime_ns,
                before.st_ino, before.st_dev, before.st_nlink) != (
                after.st_size, after.st_mtime_ns, after.st_ctime_ns,
                after.st_ino, after.st_dev, after.st_nlink):
            raise ValueError('tracked symbolic link changed during verification: '
                             + repr(path[:120]))
        return target
    finally:
        os.close(dir_fd)


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
            # Production qualification requires non-following, nonblocking leaf
            # opens. A tracked FIFO substitution must fail immediately instead
            # of parking the runner, and a symlink must never redirect the read.
            nofollow = getattr(os, 'O_NOFOLLOW', None)
            nonblock = getattr(os, 'O_NONBLOCK', None)
            if (not isinstance(nofollow, int) or isinstance(nofollow, bool)
                    or nofollow <= 0 or not isinstance(nonblock, int)
                    or isinstance(nonblock, bool) or nonblock <= 0
                    or (nofollow & nonblock) != 0):
                raise ValueError('platform cannot safely inspect tracked source files')
            fd = open_tracked_leaf(path, nofollow, nonblock)
            try:
                st = os.fstat(fd)
                if not stat.S_ISREG(st.st_mode):
                    raise ValueError('tracked input is not regular: ' + repr(path[:120]))
                # A hardlink outside the checkout can mutate this inode after
                # verification while HEAD/index identity remains unchanged.
                if st.st_nlink != 1:
                    raise ValueError('multiply-linked tracked input: ' + repr(path[:120]))
                if bool(st.st_mode & 0o111) != (mode == b'100755'):
                    raise ValueError('tracked mode differs: ' + repr(path[:120]))
                actual_blob = raw_digest(fd, st.st_size)
                after = os.fstat(fd)
                if (st.st_size, st.st_mtime_ns, st.st_ctime_ns,
                        st.st_ino, st.st_dev, st.st_nlink) != (
                        after.st_size, after.st_mtime_ns, after.st_ctime_ns,
                        after.st_ino, after.st_dev, after.st_nlink):
                    raise ValueError('tracked source changed during hashing: ' + repr(path[:120]))
            finally:
                os.close(fd)
        elif mode == b'120000':
            nofollow = getattr(os, 'O_NOFOLLOW', None)
            if (not isinstance(nofollow, int) or isinstance(nofollow, bool)
                    or nofollow <= 0):
                raise ValueError('platform cannot safely inspect tracked symbolic links')
            target = read_tracked_symlink(path, nofollow)
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
