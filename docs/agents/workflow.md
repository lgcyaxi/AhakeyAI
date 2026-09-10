# Public agent workflow

Guide version 1.1 -- private-source publication boundary

Open this guide before editing, committing, preparing a pull request, or
changing a build or release path.

Jump to: [startup](#startup) · [Git topology](#git-topology) ·
[verification](#verification) · [concurrency](#concurrency) ·
[commits and PRs](#commits-and-prs) · [publication boundary](#publication-boundary)

## Startup

1. Run commands from `git rev-parse --show-toplevel`, not from Git's metadata
   directory.
2. Inspect `git status --short --branch` and `git worktree list --porcelain`.
3. Read an active `.agent-task.yaml` in every linked worktree before writing.
4. Confirm that the requested component and its manifest agree with the
   intended platform.
5. Preserve unrelated work and inventory ignored content before moving or
   deleting it.

## Git topology

- `main` is the public primary branch and the pull-request head published as
  `origin/main`.
- `origin` is the contributor-controlled public fork. Only public-safe refs
  may be pushed there.
- `upstream` is an external public reference. Its push URL must remain
  disabled; contribute from `origin/main` by pull request.
- Maintainer-only source branches are outside the public collaboration
  contract. Never merge their commits into `main`; promote an intended change
  only through a squash in a clean public worktree followed by the public
  boundary check.
- Upstream synchronization flows from `upstream/main` into public `main`, then
  from `main` into the maintainer source line. Publication flows in the other
  direction only as a sanitized squash into `main`.
- If a local copy of an upstream branch is needed, name it
  `upstream-<branch>` so it cannot be confused with an owned branch.

## Verification

Run only the checks relevant to the changed component, then report exactly
what was and was not exercised.

| Component | Command root | Minimum check |
| --- | --- | --- |
| Windows Java client | `ahakeyconfig-win-java/` | `mvn package`; on Windows, run `build-exe.ps1` for the application image |
| Linux Java client | `ahakeyconfig-ubuntu-java/` | `mvn package` on Linux |
| macOS client | repository root | `swift build`; release packaging runs from `ahakeyconfig-mac/` |
| TypeScript SDK | `sdks/typescript/` | `npm install` then `npm test` |
| Public documentation boundary | repository root | `python scripts/check_agent_docs.py` |

A compile is not a release acceptance test. Report separately whether signing,
installer creation, GUI launch, hardware/BLE behavior, hooks, and platform
security checks were exercised.

## Concurrency

- Persistent linked worktrees live under `.worktree/<branch-slug>`.
- One active write task owns each worktree and advertises its branch, owner,
  write paths, shared resources, and state in `.agent-task.yaml`.
- Use `.agent-claim-lock/` only as a short-lived atomic mutex while creating or
  expanding claims. Remove the lock before implementation work.
- Write scopes and shared resources must not overlap. Read-only overlap is
  allowed.
- A dependent task waits for an exact handoff commit or records an explicit
  stacked-branch relationship.

## Commits and PRs

- Use conventional types such as `feat`, `fix`, `docs`, `test`, `build`, and
  `ci`; keep the subject imperative.
- Verify before committing, with one complete change per commit and no
  co-author trailer.
- Review the complete PR diff for credentials, account names, absolute user
  paths, private infrastructure, ignored build output, and unrelated changes.
- Confirm the public commit has only public parents and contains no
  private-source path before pushing `origin/main` or opening the PR.
- Public workflow documentation describes code and collaboration only. Keep
  personal behavior, machine topology, and unreleased operations out of every
  public branch, including branches that are not intended to merge.
- Opening a PR, pushing a branch, or publishing an artifact requires explicit
  maintainer authorization.

## Publication boundary

- Source repositories contain source, project files, required assets, and
  audience-safe documentation.
- Release binaries belong in release assets, not Git history.
- Platform publishers must upload only their own asset and preserve assets
  already attached by other platforms.
- A public release workflow must not depend on private infrastructure or
  expose contributor-specific deployment information.
