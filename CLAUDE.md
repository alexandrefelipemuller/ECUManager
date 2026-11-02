# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Cross-platform client for Speeduino and MegaSquirt/rusEFI-family ECUs, built with Kotlin Multiplatform + Compose Multiplatform. It reuses protocol/model/connection code from the SpeeduinoManager Android project and ships it to desktop (Windows/macOS/Linux) and iOS. New functionality should prioritize the `shared` module so Android, desktop, and iOS stay on the same core.

## Commands

```bash
# Run the desktop app
./gradlew :desktopApp:run

# Build
./gradlew :desktopApp:build

# Tests (shared module logic — protocol, models, parsers)
./gradlew :shared:desktopTest
./gradlew :shared:desktopTest --tests "com.speeduino.manager.model.TableValidatorTest"

# Package distributables
./build-release.sh                       # auto-detects OS (dmg/deb+AppImage/exe)
./gradlew :desktopApp:packageReleaseDmg   # macOS
./gradlew :desktopApp:packageReleaseDeb :desktopApp:packageReleaseAppImage   # Linux
./gradlew :desktopApp:packageReleaseExe  # Windows

# iOS (macOS only, needs Xcode)
sh iosApp/build-ios.sh
# equivalently, what Xcode runs pre-compile:
./gradlew --console=plain :desktopApp:embedAndSignAppleFrameworkForXcode
```

There is no Android target wired into `shared`/`desktopApp` currently (an `androidMain` source set exists for shared expect/actuals but is not attached to a target in the Gradle config).

### Raspberry Pi (arm64) packaging

`packageReleaseDeb`/jpackage bundles a JRE for whatever machine runs the build — building on an x86_64 dev box produces an amd64 `.deb` that will not install on a Raspberry Pi. There's no arm64 dev machine in the loop yet, so cross-build with:

```bash
./scripts/build-arm64-deb.sh
```

This runs the full Gradle build inside a `linux/arm64` Debian container via Docker + QEMU user-mode emulation (`docker run --platform linux/arm64`), producing a real arm64 `.deb` under `desktopApp/build/compose/binaries/main-release/deb/`. Notes:
- Needs `docker` with arm64 emulation registered (binfmt) — verify with `docker run --rm --platform linux/arm64 arm64v8/debian:trixie uname -m`.
- Debian trixie (the container base) only ships OpenJDK 21/25, not 17; the script uses 21 (the project has no pinned toolchain, so this is fine).
- The build forces IPv4 (`Acquire::ForceIPv4`, `-Djava.net.preferIPv4Stack=true`) — on some networks (VPNs especially) AAAA DNS records resolve but have no real IPv6 route, which silently hangs apt/Gradle downloads for a long time otherwise.
- Expect ~1.5–2h under QEMU emulation on typical dev hardware (vs. minutes natively) — it's CPU-bound Kotlin/Compose compilation, not actually stuck.
- The resulting `.deb` is a normal `dpkg`/`apt install ./foo.deb` target on the Pi.

Local protocol testing without real hardware: run `python3 simulator/speeduino_tcp_simulator.py --host 0.0.0.0 --port 5555` (from the separate main SpeeduinoManager repo, not this one) and connect the desktop app to `127.0.0.1:5555`.

## Module layout

- `shared/` — Kotlin Multiplatform core: protocol parsing/CRC, connection transports, ECU models/table definitions, live-data parsing, storage, tuning analysis. Targets: `jvm("desktop")`, `iosArm64()`, `iosSimulatorArm64()`.
- `desktopApp/` — Compose UI. Also multiplatform: `desktopMain` (Compose Desktop windowed app) and `iosMain` (Compose UIViewController embedded via `SpeeduinoManagerKit` framework). Same module, two very different UIs sharing navigation-adjacent code.
- `iosApp/` — thin Xcode project (`AppDelegate.swift`, `Info.plist`) that hosts the `SpeeduinoManagerKit` framework produced by `desktopApp`'s iOS targets.

### `shared` source set structure

Gradle target `jvm("desktop")` produces `desktopMain`/`desktopTest` automatically; `jvmMain`/`jvmTest` are hand-created intermediate source sets that `desktopMain` depends on (a seam for a future non-desktop JVM target, e.g. Android). Platform-specific pieces use `expect`/`actual` across `commonMain` → `{android,desktop,ios}Main`:

- `com.speeduino.manager.shared.Logger` — actual per platform
- `com.speeduino.manager.storage.PlatformFileSystem` — actual per platform
- `com.speeduino.manager.units.PlatformLocale` — actual per platform
- `com.speeduino.manager.connection.{BleConnection,NativeTcpConnection}` — actual per platform

Desktop-only additions live in `jvmMain`/`desktopMain`: `EcuProtocol`, `EcuClient`, `ConfigManager`, `ConfigSyncService`, serial/TCP connection implementations (using `jSerialComm`), `LiveLogRecorder`, `BeforeAfterLogCompare`.

### ECU abstraction

- `connection.IEcuConnection` — low-level transport interface (TCP/serial/BLE), byte-level send/receive, handshake retry hooks, legacy vs. "modern" (CRC32) protocol negotiation.
- `transport.EcuTransport` — higher-level interface consumed by the UI: connection lifecycle, live-data streaming, and per-table/per-config read/write operations (VE table, ignition table, AFR table, engine constants, trigger settings, idle control, closed-loop correction, TPS/pressure calibration, etc). Most operations default to `UnsupportedOperationException` — a given ECU family only overrides what it actually supports.
- `desktopApp` provides concrete `EcuTransport` implementations per ECU family/protocol: `AutoDetectEcuTransport`, `Obd2Transport`/`PromotingObd2Transport`, `PsaTransport`, `RenaultTransport`, `VagTransport`.
- `model.EcuDefinitionRegistry` resolves a firmware signature string to an `EcuDefinition` via a list of `EcuDefinitionProvider`s (Speeduino, MegaSpeed/MS2Extra, MS2, MS3, rusEFI). Each provider declares its own page catalog, byte order, and block size. When adding support for a new firmware variant, add a provider here rather than branching on signature strings elsewhere.
- Table/config models (`VeTable`, `IgnitionTable`, `AfrTable`, `EngineConstants`, `TriggerSettings`, `EngineProtectionConfig`, `IdleControlSettings`, etc.) are plain data classes in `model/`, independent of any one ECU family's on-wire layout — family-specific definitions (`EcuTableDefinitions`, `Ms2TableDefinitions`, `Ms3TableDefinitions`, `RusefiTableDefinitions`, `MegaSpeedIniTableDefinitions`) map wire format to these models.

### Desktop UI structure

`desktopApp/src/desktopMain` follows a feature/navigation split:
- `feature/{app,configs,connection,logs,maps}/` — screen composables per feature area.
- `navigation/` — one `*Navigation.kt` (route enum/sealed type) + `*RouteHost.kt` (renders the route) pair per feature area, wired together by `AppNavigation.kt`/`AppRouteHost.kt`.
- `DesktopController.kt` / `app/DesktopAppState.kt` — cross-cutting app state (current route, connection settings) and the controller that bridges UI to `EcuTransport`.
- `telemetry/` — diagnostics/logging support (`ConnectionDiagnosticsLogger`, `LiveDataPipelineProbe`, `Obd2InvestigationRecorder`) for debugging connection issues in the field, not user-facing features.

`desktopApp/src/iosMain` is a separate, simpler app shell (`Main.kt: createAppViewController()`): three-tab bottom navigation (Home/Dashboard/Settings) with an in-tab back-stack, screens under `screens/Ios*.kt`. It does not reuse `desktopMain`'s navigation system — it's a parallel, iOS-idiomatic UI over the same `shared` core.

## Paridade com o app Android (EcuManager)

O app Android (`~/Projects/EcuManager`) e este projeto devem evoluir juntos. A lógica comum vive no submódulo `ecu-core` (mesmo pin nos dois). Telas Compose são portadas à mão: `scripts/import_android_strings.py '<regex>'` copia chaves de `res/values*/strings.xml` do Android para `Localization.kt` (chave Android = chave do Desktop, idempotente, 6 idiomas; ms não existe no Desktop). Telas portadas usam `LocalStrings.current` (`strings["chave"]`, `strings.format(...)`) no lugar de `stringResource`.

## Docs in this repo

`docs/PLAN.md` and `docs/ARCHITECTURE.md` are early planning documents (in Portuguese) written before the KMP project existed and describe an initial Linux-only scope — they're out of date relative to the current multi-platform (desktop + iOS), multi-ECU-family (Speeduino/MS2/MS3/rusEFI) state of the code. Prefer reading the actual source over these when they conflict. `docs/BUILD.md` is similarly a planning doc with placeholder command names, not the current build instructions.
