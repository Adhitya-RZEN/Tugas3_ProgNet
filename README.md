# BYTEBOX Download Manager

BYTEBOX is a Java 21 desktop download manager built with JavaFX, FXML, and Java's `URLConnection` API. It streams real HTTP and HTTPS downloads, runs up to three transfers at once, and stores its download history locally.

## Requirements

- Java Development Kit (JDK) 21 or newer.
- Maven 3.9.16 or newer, or use the included Maven Wrapper (`mvnw.cmd` on Windows and `./mvnw` on macOS/Linux).
- A desktop environment that supports JavaFX. The Maven JavaFX plugin selects the platform-specific JavaFX libraries.

The project uses JavaFX 21.0.13, JUnit Jupiter 5.14.4, Maven Compiler Plugin 3.13.0, Maven Surefire Plugin 3.5.4, and JavaFX Maven Plugin 0.0.8. JavaFX is managed as Maven dependencies; it does not need to be installed separately.

## Build, test, and run

From the project directory:

Make sure `JAVA_HOME` points to JDK 21 and `java -version` reports version 21. The Maven Wrapper uses `JAVA_HOME` (or `java` on `PATH`) to run Maven.

```powershell
.\mvnw.cmd test
.\mvnw.cmd javafx:run
```

On macOS or Linux:

```sh
./mvnw test
./mvnw javafx:run
```

To create the project JAR, run `mvnw.cmd package` on Windows or `./mvnw package` on macOS/Linux. The app can also be run with an installed Maven using `mvn test`, `mvn javafx:run`, and `mvn package`.

The generated JAR is a regular Maven artifact, not a self-contained application: it does not bundle JavaFX or the other runtime dependencies and is not directly launchable with `java -jar`. Run BYTEBOX with `mvnw.cmd javafx:run` (or `./mvnw javafx:run`) on a machine with JDK 21 and Maven dependency access. For a portable Windows application image, use the instructions below. The project does not produce a Windows installer.

### Portable Windows application image

On Windows x64, set `JAVA_HOME` to a 64-bit JDK 21 installation that includes `jlink` and `jpackage`, then run this from the project directory in PowerShell:

```powershell
.\scripts\package-windows.ps1
```

The script runs the Maven package (including the test suite), collects the Windows JavaFX runtime modules, builds a minimized Java runtime, and creates an app image at `dist\BYTEBOX\`. Launch it with:

```powershell
.\dist\BYTEBOX\BYTEBOX.exe
```

Distribute and keep the entire `dist\BYTEBOX` directory together; `BYTEBOX.exe` is a launcher and does not contain the runtime by itself. The packaged application includes Java and JavaFX, so a user does not need to install JDK, Maven, or JavaFX to run it. The packaging step itself requires JDK 21 and Maven dependency access (or a populated Maven cache). This app image is not an installer.

The application image can be moved to another directory. BYTEBOX stores settings, history, and partial files under the current user's home directory (`%USERPROFILE%\.bytebox` on Windows); completed downloads go to `%USERPROFILE%\Downloads\BYTEBOX` by default. Moving the app image does not move this user data. Clean-machine portability has not been verified; see [the packaging verification report](docs/packaging-verification.md) for the tested environment and remaining limits.

## Use BYTEBOX

1. Start the app and paste a direct HTTP or HTTPS file URL into the top field.
2. Select **Add download**. Add more URLs the same way; the first three eligible downloads run concurrently and the rest wait in the queue.
3. Each row reports its own status, transferred bytes, progress (or an indeterminate indicator when the server omits the file size), speed, and ETA when available.
4. Use **Pause** to stop one transfer and keep its partial file, **Resume** to continue it when the server supports safe range requests, or **Cancel** to stop it and delete its partial file. Other transfers continue independently.
5. Use **Restart from zero** when BYTEBOX reports that resuming is unsafe or unsupported. Choose a different destination with **Folder**; **Open folder** opens the current destination.

By default, downloads go to `~/Downloads/BYTEBOX`. Job metadata and partial files live in `~/.bytebox/jobs`; the chosen download directory is stored in `~/.bytebox/settings.properties`. When BYTEBOX closes, active jobs are paused and their partial files are kept for recovery on the next launch.

## HTTP resume behavior and limitations

BYTEBOX resumes a partial file by sending `Range` and, when available, `If-Range` with a strong ETag or Last-Modified validator. It appends data only after validating the server's `206 Partial Content`, `Content-Range`, response length, and the selected resume validator. If the server ignores the range, omits or changes the selected validator, or sends an inconsistent range, BYTEBOX keeps the partial file and marks the job as requiring a restart from zero; it never appends a `200 OK` response to partial data. To avoid combining different content, a saved partial without a usable server validator also requires restarting.

Some servers do not support range requests or provide validators, so those downloads cannot safely resume. BYTEBOX currently downloads direct HTTP(S) URLs without browser cookies, interactive sign-in, or custom request headers. It follows at most five HTTP redirects, rejects redirect loops and HTTPS-to-HTTP downgrades, and keeps the JDK's default TLS certificate and hostname validation. It does not verify a cryptographic checksum unless the server's content itself is independently checked.

## Java Networking and I/O Concepts Used

`HttpDownloadWorker.openConnection` starts each request with `URL.openConnection()`. The returned `URLConnection` must be an `HttpURLConnection`; HTTPS connections use the JDK's `HttpsURLConnection` subclass and its default certificate checks. The worker sets connect and read timeouts, sends `Range` and `If-Range` when resuming, checks HTTP status codes, and follows only bounded HTTP(S) redirects.

In `HttpDownloadWorker.run`, `HttpURLConnection.getInputStream()` provides the response `InputStream`. BYTEBOX wraps it in a 64 KiB `BufferedInputStream`, then `writeBody` repeatedly reads into a fixed 64 KiB `byte[]` until EOF. Each actual byte count updates that job's progress; the file is never loaded into memory as a whole.

`HttpDownloadWorker.writeBody` opens the partial file through `Files.newOutputStream`, which returns an `OutputStream`, and wraps it in a 64 KiB `BufferedOutputStream`. Both wrappers use try-with-resources, so closing the buffered file stream flushes bytes to disk and closing the buffered response stream releases the connection's input stream. A job opens its file for append only after the resumed response has passed the range and validator checks.

The relationship is: `URL.openConnection()` → `HttpURLConnection` → response `InputStream` → `BufferedInputStream` → fixed-size `byte[]` → `BufferedOutputStream` → file `OutputStream` → partial file. `BufferedInputStream` and `BufferedOutputStream` are wrappers around the underlying streams, not separate sources or destinations. `DownloadManager.scheduleEligible` runs the blocking transfer work away from the JavaFX Application Thread and limits active jobs to three.

For resume, the existing partial-file length becomes the `Range` start. BYTEBOX sends a strong ETag in `If-Range`, or Last-Modified when no strong ETag is available. It appends only after receiving a matching `206 Partial Content`, a `Content-Range` beginning at the saved offset, consistent lengths, and the selected validator; an ignored or unsafe range leaves the partial file intact and requires a restart.

## Verification

Run `mvnw.cmd test` (Windows) or `./mvnw test` (macOS/Linux). Tests use local HTTP fixtures and verify byte-for-byte output, three concurrent transfers with independent progress, queue scheduling, pause/resume, ignored or inconsistent ranges, unknown-total ranges beyond a known size, changed or missing validators, bounded redirects and redirect-loop rejection, HTTP error reporting, cancellation isolation, unknown content length, duplicate names, interrupted transfers, persistence recovery, aggregate progress summaries, and invalid URLs.

## Design reference

The approved Minty Pop dashboard reference is [ByteBox Download Manager Dashboard.png](docs/design/ByteBox%20Download%20Manager%20Dashboard.png). The interface uses its four summary cards, left navigation, download table, and compact transfer summary while retaining the BYTEBOX download controls.

## Technical references

- [OpenJFX Maven guide](https://openjfx.io/openjfx-docs/maven)
- [Java 21 URLConnection API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/URLConnection.html)
- [Java 21 HttpURLConnection API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/HttpURLConnection.html)
- [Java 21 HttpsURLConnection API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/net/ssl/HttpsURLConnection.html)
- [JUnit 5.14.4 documentation](https://docs.junit.org/5.14.4/)
- [Apache Maven Wrapper usage](https://maven.apache.org/tools/wrapper/)
