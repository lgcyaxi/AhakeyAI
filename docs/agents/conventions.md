# Repository conventions

Guide version 1.1 -- private-source publication boundary

Open this guide when naming files or branches, changing versions, handling
generated files, or checking known repository drift.

Jump to: [versioning](#versioning) · [source layout](#source-layout) ·
[generated output](#generated-output) · [documentation](#documentation) ·
[known drift](#known-drift)

## Versioning

- Use `major.minor.revision` for application versions.
- A release tag, manifests, package scripts, metadata, and release assets must
  resolve to the same version before publication.
- Do not silently infer a release version from a stale hard-coded script.

## Source layout

- Keep platform implementations inside their existing client roots.
- Java packages follow the existing `com.example.ahakey` hierarchy.
- Swift products and targets remain declared by the root `Package.swift`.
- New environment or packaging configuration belongs under the owning
  component unless a deliberate repository-wide migration says otherwise.
- Do not add another large dependency surface to the repository as an
  incidental helper.

## Generated output

- Keep Maven `target/`, Swift `.build/`, application images, installers,
  archives, logs, local models, credentials, and signing files out of Git.
- A final local build may remain as evidence on its producing machine, but it
  is not source and is never staged in a public PR.
- Inventory ignored directories before cleanup because an ignored artifact may
  be the only local copy.

## Documentation

- `AGENTS.md` is the canonical shared entry point; root `CLAUDE.md` is exactly
  the one-line import `@AGENTS.md`.
- `docs/agents/` contains audience-safe code and collaboration rules, not
  product documentation or personal operating notes.
- Do not add account names, absolute user directories, private repository
  coordinates, machine inventory, or private release commands to public docs.
- Run `python scripts/check_agent_docs.py` after changing the entry files,
  agent guides, ignore rules, or code-indexed paths.

## Known drift

- The repository tag version and the Windows/Ubuntu Maven version are not yet
  sourced from one release value.
- Windows and Linux clients are not currently built by public CI.
- The Windows application-image build depends on a separately produced BLE
  bridge executable and may complete without including it.
- Some Ubuntu `target/` files are already tracked despite the repository rule
  that build outputs do not belong in source control.
- `docs/installation.md` names a Java entry class that differs from the Maven
  manifests and current application source.

Treat these as explicit maintenance items. Do not expand an unrelated change
to repair them without confirming scope and validation.
