# Trusted-base AttoLang implementation-policy review

The AttoLang-first checker is a PR *source-placement disclosure* guard, not a
proof that a feature is correctly implemented in guest `.atto` code.

## Security boundary

The policy must not execute the workflow definition or test scripts supplied
by a candidate PR. A normal `pull_request` workflow can be modified on the
PR branch, including changing its own policy gate. Therefore the follow-up
review workflow uses **`pull_request_target`** to select the trusted base
workflow definition, with `permissions: contents: read`.

The workflow checks out the exact **base SHA**, obtains the candidate
`refs/pull/<number>/head` through the source repository's GitHub-owned
remote, and confirms the fetched commit is the exact event-reported PR head
SHA. That PR head is **data only**: it is never checked out or used as a shell
script source. The reviewed checker and selftests execute from the base worktree
only. The checker compares `base...PR-head` and reads the event PR body from
`GITHUB_EVENT_PATH`, never interpolating PR prose into shell commands.

Use no `pull_request_target` step that compiles, runs, sources, builds,
executes hooks from, or installs dependencies declared by PR-head content.
Never make write-scoped credentials or repository secrets available to
untrusted code. The workflow must fail closed if the expected SHA, fetched
pull ref, source scripts or common merge base cannot be verified.

## Enforcement limitations

- Pull-request metadata can falsely claim an exception; codeowners must
  review whether the `.java`/host additions are genuinely required.
- This transition does not mean every old PR meets the new policy. The
  checker retains its explicit pre-policy creation-time exception and these
  older PRs require **manual review**.
- A `pull_request_target` run is associated with base-branch context in
  GitHub Actions. **Do not assume** its status is a required PR-head check.
  Independently verify ruleset/branch-protection behavior and check-run
  association; publishing a PR-head check may require separate reviewed
  GitHub App authority. No check-writing token is granted here.
- The source organization runner must actually execute nonzero workflow
  steps. `steps=[]` is **NOT EXECUTED**, regardless of the workflow's
  GitHub conclusion.
- Source review / bash selftests do not establish `.atto` compiler execution,
  Java JIT/AOT correctness or LLVM/Wasm/.NET/Forge parity.

This is a private-repository workflow. Do not transfer source outside its
authorized trust domain merely to obtain GitHub Actions minutes.
