# Code index

Guide version 1.1 -- private-source publication boundary

Open this guide to locate a client, build entry point, or extension seam before
loading implementation files.

Jump to: [products](#products) · [Windows](#windows) · [macOS](#macos) ·
[Linux](#linux) · [shared components](#shared-components) ·
[automation and documentation](#automation-and-documentation)

## Products

| Product | Root | Manifest or entry point |
| --- | --- | --- |
| macOS application and helper | `ahakeyconfig-mac/` | `Package.swift` |
| Windows Java client | `ahakeyconfig-win-java/` | `ahakeyconfig-win-java/pom.xml` |
| Windows legacy Python client | `ahakeyconfig-win-python/` | `ahakeyconfig-win-python/main.py` |
| Ubuntu Java client | `ahakeyconfig-ubuntu-java/` | `ahakeyconfig-ubuntu-java/pom.xml` |
| BLE-to-TCP bridge | `BLE_tcp_bridge/` | `BLE_tcp_bridge/BLE_tcp_driver.csproj` |
| TypeScript SDK | `sdks/typescript/` | `sdks/typescript/package.json` |

## Windows

The maintained Windows client is the Java 17/JavaFX application under
`ahakeyconfig-win-java/`. Its application entry point is
`ahakeyconfig-win-java/src/main/java/com/example/ahakey/App.java`.

- `ahakeyconfig-win-java/build-exe.ps1` creates a Windows application image
  with a bundled runtime.
- `ahakeyconfig-win-java/build-installer.ps1` is the installer entry point.
- `ahakeyconfig-win-java/src/main/java/com/example/ahakey/platform/windows/`
  owns Windows-specific voice and keyboard integration.
- `ahakeyconfig-win-python/` is a legacy imported baseline; do not treat it as
  the maintained Java client or modernize it incidentally.

## macOS

The root `Package.swift` exposes the `AhaKeyConfig`, `ahakeyconfig-agent`, and
`PluginShowcase` executables plus `AhaKeyPluginKit`.

- `ahakeyconfig-mac/Sources/` contains the main application sources.
- `ahakeyconfig-mac/Sources/Agent/` contains the background helper.
- `ahakeyconfig-mac/scripts/package_app.sh` assembles an application bundle.
- `ahakeyconfig-mac/scripts/pack-release.sh` drives signed release packaging.

## Linux

`ahakeyconfig-ubuntu-java/` is a separate JavaFX/Maven client. Build and test it
on Linux; do not infer Linux compatibility from the Windows Maven build.

## Shared components

- `BLE_tcp_bridge/` provides the C# bridge used by non-native BLE clients.
- `sdks/typescript/` contains the TypeScript SDK and its tests.
- `vibebar/` is a Swift package consumed by the macOS manifest.
- `assets/` contains shared brand and build assets.

## Automation and documentation

- `.github/workflows/ci.yml` defines public pull-request and branch CI.
- `.github/workflows/release.yml` defines the tag-triggered macOS release.
- `docs/installation.md`, `docs/releases.md`, and
  `docs/supported-platforms.md` describe contributor-facing platform support.
- `scripts/check_agent_docs.py` validates this index's required paths and the
  public documentation boundary.
