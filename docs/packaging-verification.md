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

## HTTPS runtime regression and Java 17 assessment (2026-10-26)

The packaged runtime and the development JDK both reported Java 21.0.12.1, but the old app image did not resolve the `jdk.crypto.ec` module and did not list the `SunEC` security provider. The development JDK did. The JDK documentation identifies `jdk.crypto.ec` as the module that provides SunEC ([module summary](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.crypto.ec/module-summary.html)). That missing provider explains why the same URL failed during TLS negotiation in the old image before an HTTP response was received.

The supplied public URL was tested with `GET` and `User-Agent: BYTEBOX/1.0`:

```text
https://th.bing.com/th/id/OIP.5_qyYLSWz440nEx-HV7-HwHaEK?w=305&h=180&c=7&r=0&o=7&dpr=1.3&pid=1.7&rm=3
```

| Runtime | Result | Integrity |
| --- | --- | --- |
| Existing `dist\BYTEBOX\runtime` | FAIL: `SSLHandshakeException: (handshake_failure) Received fatal alert: handshake_failure`; BYTEBOX status `FAILED`, 0 bytes, and no HTTP status. `jdk.crypto.ec` was unresolved. | No file received. |
| Development JDK 21 | PASS: HTTP 200, `image/webp`, 13,732 bytes. | SHA-256 `592517b4841c8117d52eb5f7958f68eceb60944c4a5c4a09a645d4102468c580`. |
| `dist\BYTEBOX-candidate\runtime` | PASS: HTTP 200, `image/webp`, 13,732 bytes; BYTEBOX status `COMPLETED`. `jdk.crypto.ec` resolved and `SunEC` was present. | Direct response and downloaded file had the same SHA-256 as the development JDK result. |

A controlled loopback HTTPS fixture provided a second comparison. It used a locally generated certificate with a `localhost` subject alternative name, an isolated trust store containing that certificate, TLS 1.2, and only `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`. Normal certificate trust and hostname checks remained enabled. The old image failed the handshake with `Remote host terminated the handshake` and received 0 bytes. The candidate completed both requests (direct probe and BYTEBOX) with 68 bytes and SHA-256 `431ced6916a2a21a156e38701afe55bbd7f88969fbbfc56d7fe099d47f265460`; the fixture observed `User-Agent: BYTEBOX/1.0`.

The separate reported `rare-gallery.com` HTTP 403/Cloudflare challenge is an HTTP-layer response and is distinct from the old image's TLS failure, which occurred before an HTTP status existed. This QA pass did not retry that site's challenge.

The minimal packaging repair adds `jdk.crypto.ec` to the `jlink` runtime roots and the generated launcher module options. `scripts\package-windows.ps1` now accepts `-ImageDirectoryName BYTEBOX-candidate` and stages the jpackage output before moving it into the requested new directory. The normal overwrite guard remains active. The candidate was built with JDK 21.0.12.1 and JavaFX 21.0.13; its runtime module inventory contains `jdk.crypto.ec`, `javafx.controls`, and `javafx.fxml`. The existing `dist\BYTEBOX` image was not replaced; its launcher, application JAR, and runtime release-file SHA-256 values remained `DB0A7E0B0BB9C5FA76790C5E1734D5129E9EBC139A4645850678969F56094AD0`, `7CB758FFB3A7CEE7EEBDA83A41356D04BAC3F7A70F8694708D73CD2F4CB500D3`, and `7122C5D034BFD0B4F9FDDE3C12A20961740165D9BD215EBED16FC8C1D60CAC87`, respectively.

The candidate app image was packaged successfully and its bundled runtime was used directly for the HTTPS probes. The candidate `BYTEBOX.exe` JavaFX window was not launched or visually inspected in this pass because the available computer-use session exposed no desktop apps. The image is therefore a candidate for further validation, not a verified replacement for the baseline.

### Java 17 compatibility

The project remains configured for Java 21. A temporary Maven audit compiled main and test classes with `--release 17` and ran all 24 tests successfully under the installed JDK 21; the audit output classes use Java 17 class-file version 61. Maven documents that `--release` checks the language level, generated bytecode, and Java SE API surface ([Compiler Plugin 3.13.0 guide](https://maven.apache.org/plugins-archives/maven-compiler-plugin-3.13.0/examples/set-compiler-release.html)). The resolved compile/test dependency JARs were also scanned: JavaFX 21.0.13 Windows classes use major version 61, and the JUnit 5.14.4/test-support classes use major version 53. The source scan found records, pattern matching for `instanceof`, and `Stream.toList()`, all available by Java 17, and no Java 21-only language feature. This is useful source/API evidence, but it does not prove Java 17 runtime compatibility.

No JDK 17 installation was available; the installed `javac`, `jlink`, and `jpackage` belong to JDK 21. The application was not built, tested, launched, or packaged using an actual JDK 17. The package script also explicitly requires JDK 21, so a Java 17 package would need an intentional script update followed by full JDK 17 verification. Java 17 compatibility is therefore **NOT TESTED**, and the verified build/package baseline remains Java 21. Do not change the documented minimum or replace the baseline image until JDK 17 compilation, tests, JavaFX launch, and package-runtime checks pass on an actual JDK 17.

### Follow-up verification matrix

| Check | Result | Evidence / limitation |
| --- | --- | --- |
| Existing automated suite | PASS | 24/24 tests passed during the candidate packaging build. |
| Exact supplied HTTPS URL | PASS on candidate; FAIL on old image | Candidate and development JDK returned the same HTTP 200 payload hash. The old image failed TLS before HTTP. |
| Controlled TLS 1.2 ECDHE fixture | PASS on candidate; FAIL on old image | Trusted test certificate, hostname validation, 68-byte file hash, and BYTEBOX request header verified. |
| Candidate package construction | PASS | `jlink` and `jpackage` created `dist\BYTEBOX-candidate` with the required JavaFX and TLS provider modules. |
| Candidate EXE window launch and visual appearance | NOT TESTED | No desktop app or window was available to inspect through the current computer-use session. |
| Java 17 compatibility | NOT TESTED | `--release 17` audit passed on JDK 21, but an actual JDK 17 was unavailable. |
| TLS security settings | PASS | No trust-all manager, hostname-verification bypass, or weakened global TLS setting was introduced. |
