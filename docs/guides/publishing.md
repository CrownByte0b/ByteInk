# Publish GitHub Packages

The [ci workflow](https://github.com/CrownByte0b/ByteInk/actions/workflows/ci.yml)
publishes to `https://maven.pkg.github.com/crownbyte0b/byteink` after the native
builds, native tests, pin checks and Linux/Windows JVM test matrices pass.
The source release configuration includes the desktop libraries, shared `byteink-model`,
`byteink-portable`, `byteink-wasm` and `byteink-compose-wasm`, with sources and metadata. The
Wasm engine has a separate `engine@zip` asset classifier; see the [Wasm setup](wasm.md).
The native loader includes Linux/Windows x86_64 and arm64 binaries and separate Linux
debug-symbol archives. These Wasm artifacts are absent from the existing `0.1.0-alpha.1` release.

To publish a version from a tag, push `v<major.minor.patch[-prerelease]>`:

```sh
git tag v0.1.0
git push origin v0.1.0
```

To publish manually, open **Actions → ci → Run workflow**, select the branch or
tag to build, and enter **version** as `0.1.0` or `0.2.0-rc.1` without the `v`.
The selected ref is tested and published; no tag is created by a manual run.
Leave version empty to run checks without publishing. With GitHub CLI:

```sh
gh workflow run ci.yml --ref master -f version=0.1.0
```

Both paths validate the version before starting native builds and use identical
coordinates for testing and publishing. For `0.1.0`, the main modules use
`com.vivenotes.byteink:<module>:0.1.0`; the loader uses
`com.vivenotes.byteink:ink-nativeloader:1.1.0-alpha06-byteink.0.1.0` with the
current AndroidX pin. SNAPSHOT releases are rejected. Choose an unpublished
version; these runs do not overwrite or delete existing packages.

Publishing uses the workflow's automatic `GITHUB_TOKEN` with `packages: write`
only in the publish job; no additional publishing secret is required. Release
runs queue instead of cancelling a run that might already be uploading packages.
See GitHub's [Gradle publishing documentation](https://docs.github.com/en/actions/tutorials/publish-packages/publish-java-packages-with-gradle).
