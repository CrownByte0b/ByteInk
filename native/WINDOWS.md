# Windows verification

Use Windows x86-64, Git for Windows and Azul JDK 27 for the Gradle daemon. Native binaries are
cross-built on Linux; a Windows SDK, Visual Studio and VC++ redistributable are not needed for Ink.
The JVM and Skiko have their own runtime requirements.

## Bring native artifacts into the VM

Build on Linux with `native/build-linux.sh` and `native/build-windows.sh`, or download the CI
artifacts `libink-linux-x86_64` and `ink-windows-x86_64`.

Extract/copy the artifact contents below `native/build/out/`, preserving these paths:

```text
native/build/out/<google.ink.commit>/linux-x86_64/libink.so
native/build/out/<google.ink.commit>/linux-x86_64/build.properties
native/build/out/<google.ink.commit>/windows-x86_64/ink.dll
native/build/out/<google.ink.commit>/windows-x86_64/build.properties
```

The commit is in `upstream/pins.properties`. Both binaries are needed because the loader jar
packages both platforms. Copy the source checkout without its Linux `.gradle/` and module `build/`
directories; Gradle will rebuild the JVM code in the VM.

From PowerShell at the repository root:

```powershell
.\gradlew.bat build :upstream:checkLinuxLibrary :upstream:checkWindowsLibrary :conformance:oracle:oracleByteink
```

To run every suite on another installed runtime while keeping the daemon on Azul 27:

```powershell
.\gradlew.bat build :conformance:oracle:oracleByteink "-PbyteinkTestJavaHome=C:\Java\jbr25"
```

`byteinkTestJavaHome` also selects JavaExec applications such as the oracle and raster harness.
CI runs the full build on Linux and Windows using Zulu 25, Zulu 27 and the checksum-pinned JBR 25
SDK. Google-binary conformance runs only on Linux because Google ships no Windows binary.
The extra oldest-JDK suite is omitted when the runtime matrix already selects a specific JDK.

## Compare the same notebooks and images

On Linux, run `./gradlew :byteink-testing:desktopParityDump`. Copy
`byteink-testing/build/desktop-parity/` to `linux-reference/desktop-parity/` in the VM, then:

```powershell
.\gradlew.bat :byteink-testing:desktopParityCompare `
  "-PbyteinkParityFixtures=linux-reference/desktop-parity/fixtures" `
  "-PbyteinkParityReference=linux-reference/desktop-parity"
```

The default fixture is generated, contains no personal data, and covers all ten brushes with
opaque/translucent pressure loops. Forty cases exercise stored replay, partial erases, object
erases, and a move/resize after erasure. Both platforms read the exact same archive bytes.
Bounds and transforms use the existing engine tolerance; identities and projection counts must
match exactly. Software images must differ by at most two RGB levels at any pixel.
The report is `byteink-testing/build/desktop-parity/comparison.md`.

For the graphical smoke test, open the generated notebook in the viewer:

```powershell
.\gradlew.bat :samples:viewer:run "--args=byteink-testing/build/desktop-parity/fixtures/brushes.vive"
```

Check page navigation, Fit, zoom and light/dark ink colours. The same viewer can open your own
notebook through its Open button.

`-PbyteinkParityFixtures=<directory>` accepts existing `.vive` notebooks instead; outputs include
copies and images, so keep personal fixtures and their output under ignored `build/` directories.
Use a fresh `-PbyteinkParityDirectory=<directory>` when changing the fixture set.
`-PoracleFixtures=<directory>` adds the same notebooks to the raw engine oracle. CI uses the
non-sensitive synthetic fixtures and uploads both Windows-vs-Google and Windows-vs-Linux reports.

To compare raw engine dumps, use the oracle application produced by
`:conformance:oracle:installDist`, with Java arguments:

```text
-cp "conformance/oracle/build/install/oracle/lib/*" com.vivenotes.byteink.oracle.OracleKt
compare --reference <Linux oracleByteink.tsv> --candidate <Windows oracleByteink.tsv> --report <report.md>
```

## Native tests and loader checks

`native/build-windows-tests.sh` cross-builds five portable upstream gtest executables. Copy
`native/build/windows-tests/` into the VM and run them:

```powershell
Get-ChildItem native/build/windows-tests/*.exe | ForEach-Object {
  & $_.FullName
  if ($LASTEXITCODE -ne 0) { throw "Native test failed: $($_.Name)" }
}
```

The subset covers Android angle arithmetic, distances, brushes, packed mesh geometry and input
serialization. It runs 103 tests; three mesh death tests are skipped by upstream on this platform.
Linux still runs the full upstream suite, including the fuzztest-based tests.

The loader suite starts four independent JVMs against a fresh shared cache, verifies warm reuse
while another process holds the DLL, and confirms Windows locks are released on process exit.
Cache paths include spaces and characters supported by the runtime's native path encoding.
JBR additionally exercises Japanese characters outside the default Windows code page.

Standard OpenJDK native loading can fail for paths outside the Windows code page (observed on
Zulu 27 with Cp1252 under Wine). The pinned JBR's UTF-8 native loader passes the same path.
If necessary, choose an ASCII cache directory with `-Dbyteink.ink.cache=C:\byteink-cache` or name
an installed library with `-Dbyteink.ink.library=C:\path\ink.dll`. The cache override deliberately
names the only extraction location; it does not silently extract elsewhere.

Wine checks are useful local evidence. Final acceptance still requires the Windows CI matrix
or equivalent VM runs, including the Gradle consumer tests and a graphical sample smoke test.
