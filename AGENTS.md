# AhaKey Desktop agent guide

Guide version 1.1 -- private-source publication boundary

Open this file at the start of work in this repository. It is the public,
shared entry point for contributors and coding agents.

`.agents/` is tracked only on the maintainer's private source line. When it is
present, read `.agents/index.md` first because it takes precedence for
personal workflow and release operations. Never merge that private line into
a public branch; public promotion must be a checked, sanitized squash.

## Public boundary

- Every commit reachable from a branch pushed to GitHub must be safe to
  publish in the public fork and an upstream pull request.
- Never track personal instructions, machine paths, private remote locations,
  account names, credentials, signing material, or private release procedures.
- Do not commit build outputs such as `target/`, `.build/`, `dist/`, `.app`,
  `.dmg`, `.exe`, `.msi`, or generated runtime bundles.
- Never push directly to `upstream`. Contributions go through a public-safe
  branch on `origin` and an upstream pull request.
- Do not rewrite shared history, force-push, publish a release, or change
  signing configuration without explicit maintainer approval.

## Situation dispatch

| Situation | Open |
| --- | --- |
| Before editing, committing, or preparing a PR | `docs/agents/workflow.md` |
| Locating a client, build entry point, or extension seam | `docs/agents/code-index.md` |
| Naming, versioning, generated files, or known repository drift | `docs/agents/conventions.md` |
| A consequential request is underspecified | `docs/agents/clarification.md` |

## Core rules

- Run Git and project commands from the worktree root or the component root
  that owns the relevant manifest.
- Inspect `git status`, linked worktrees, and active task manifests before
  editing. Concurrent write tasks use separate worktrees and disjoint scopes.
- Preserve unrelated changes and ignored files. Anything ignored may be the
  only copy of a local artifact.
- Verify before committing. Keep one complete, reviewable change per commit
  and use an imperative conventional subject without co-author trailers.
- Run `python scripts/check_agent_docs.py` before committing workflow or
  documentation-boundary changes.
