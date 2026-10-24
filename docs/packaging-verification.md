# BYTEBOX Windows portable package verification

Verified on 2026-10-24 on the project Windows desktop with a 64-bit Temurin JDK 21.0.12.1, JavaFX 21.0.13, and the Maven Wrapper. The distribution is a Windows x64 `jpackage` application image, not an installer.

## Build

From the project directory in PowerShell, set `JAVA_HOME` to a 64-bit JDK 21 and run:

```powershell
.\scripts\package-windows.ps1
```

The script runs `mvnw.cmd -B package`, collects runtime-scoped Maven dependencies with Maven Dependency Plugin 3.8.1, then uses `jlink` and `jpackage --type app-image`. The image is written to `dist\BYTEBOX\`; the script refuses to overwrite an existing image. The app image contains:

- `BYTEBOX.exe` (453,632 bytes; about 443 KiB)
- the application JAR, FXML, CSS, and other resources under `app\`
- a bundled Java runtime and JavaFX modules under `runtime\`

The complete image has 148 files and totals 57,276,753 bytes (54.62 MiB). Its `runtime\bin\java.exe` reports Java 21.0.12.1. `java --list-modules` from that bundled runtime lists JavaFX base, graphics, controls, and FXML modules at 21.0.13. The generated launcher config points to the app JAR and BYTEBOX main class and enables the JavaFX controls/FXML modules. The Windows JavaFX classifier JARs supply the native libraries used by the bundled runtime.

## Verification results

| Check | Result | Evidence |
| --- | --- | --- |
| Full automated suite before packaging | PASS | 24/24 tests: 19 download-manager integration, 3 filename resolver, and 2 download-summary tests. |
| Maven package | PASS | `mvnw.cmd -B package` completed, including its test run. |
| Full automated suite after packaging | PASS | `mvnw.cmd -B test` completed after the image and documentation changes: 24/24 tests. |
| Application image and launcher | PASS | `dist\BYTEBOX\BYTEBOX.exe`, `app\`, and `runtime\` are present; packaged launcher opened the actual JavaFX window. |
| FXML, CSS, branding, and JavaFX controls | PASS | The packaged GUI rendered its BYTEBOX branding, Minty Pop cards, navigation, download table, transfer actions, and footer in the inspected window. |
| Launch without Maven | PASS | Launched `BYTEBOX.exe` from a copied image in a different directory; the runtime and JavaFX modules are within the image. |
| Real download and integrity | PASS | Seven local HTTP-fixture files completed; the 512 KiB file and six 8 MiB files matched their expected SHA-256 content. |
| Concurrent progress and queue | PASS | Three real transfers advanced independently at once. A fourth slow transfer was observed queued, then entered an active slot when another transfer paused. |
| Pause and resume | PASS | A real 8 MiB transfer paused and resumed to completion; its complete bytes matched the expected fixture hash. Other active transfers continued during the pause. |
| Cancel | PASS | The approved test-only `slow-hold-four.bin` transfer reached `CANCELLED`; its partial file was absent afterward. |
| Recovery after restart | PASS | After closing and reopening the relocated app image, seven completed items remained and the three interrupted test transfers recovered as paused with partial data retained. |
| Writable storage after relocation | PASS | The relocated copy wrote job metadata, completed files, and paused partial files under an isolated user-home directory, separate from the app image. |
| Clean Windows machine without JDK | NOT TESTED | Testing used a development machine that has JDK 21 installed. The image contains its own runtime and launched through `BYTEBOX.exe`, but no clean machine was available for isolation testing. |
| Installer | NOT PRODUCED | WiX `candle.exe` and `light.exe` were unavailable; installer creation was optional and was not attempted. |

The GUI was captured and visually inspected at approximately 1344×792 through the desktop-control session. The screenshot was not saved as a project file. The package smoke test did not independently exercise every failure-message state in the JavaFX UI; HTTP error reporting is covered by the automated integration suite.

## Portability and limitations

Distribute the entire `dist\BYTEBOX` directory and launch `BYTEBOX.exe` from inside it. The EXE alone is only the launcher. Runtime execution does not use Maven; the app image carries the Java runtime and JavaFX runtime modules. Building the image requires Windows x64, a 64-bit JDK 21 with `jlink`/`jpackage`, and Maven dependency access (unless the needed artifacts are already cached).

BYTEBOX stores history and partial files under the current user's home directory in `.bytebox`; downloads default to `Downloads\BYTEBOX`. These user files stay with that user account when the app image is moved. No custom Windows `.ico` asset was present in the project, so no custom launcher icon was packaged. No installer or standalone single-file executable was produced.
