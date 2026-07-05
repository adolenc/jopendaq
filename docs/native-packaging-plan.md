# Plan: package the openDAQ natives as Maven artifacts

Status: **planned, not implemented**.  This documents the agreed approach so a
later session can execute it without re-deriving the design.

## Goal

Make Maven-managed platform-classifier jars the primary way the bindings get
their native openDAQ libraries, demoting the runtime GitHub download to a
last-resort fallback.  Loader search order becomes:

1. `OPENDAQ_JAVA_NATIVE_DIR` env var / `opendaq.native.dir` system property
   (unchanged — dev builds, packagers);
2. **NEW: classpath resources** from a `natives-<platform>` classifier jar,
   extracted into the versioned per-user cache;
3. GitHub release download (existing behaviour, kept for zero-setup
   REPL/scripting use; still disabled by `OPENDAQ_NO_DOWNLOAD`).

Rationale (from the discussion that produced this plan): runtime downloads are
un-idiomatic on the JVM — corporate networks block GitHub, offline/CI builds
break, and Maven already provides checksums, mirroring, and repeatable builds.
Precedents: LWJGL / Netty / JavaCPP presets (classifier jars), SQLite-JDBC /
RocksDB (fat jar, rejected here for size), gRPC (os-maven-plugin classifiers).

## Artifact layout

- Main jar `org.opendaq:opendaq-java` stays pure Java (as today).
- One natives jar per platform, same GAV with a classifier:
  - `org.opendaq:opendaq-java:<version>:natives-linux-x64`
  - `org.opendaq:opendaq-java:<version>:natives-windows-x64`
  - `org.opendaq:opendaq-java:<version>:natives-darwin-arm64`

  **Decision:** keep the project's existing platform names
  (`linux-x64`, `darwin-arm64`, `windows-x64`) for classifiers — they match
  `NativeLoader.currentPlatformName()`, the cache directory names, and the
  cl-opendaq release archive names.  Do NOT switch to os-maven-plugin's
  `linux-x86_64`/`osx-aarch64` spelling; if we later document os-maven-plugin
  usage, add a mapping note instead.
- Optional convenience artifact `org.opendaq:opendaq-java-platform`
  (`pom` packaging) that depends on all three classifier jars, for users who
  want one dependency that works everywhere (the JavaCPP `-platform` trick).

### Contents of a natives jar

```
org/opendaq/natives/<platform>/index.txt      <- file list, one name per line
org/opendaq/natives/<platform>/<every file from bin/<platform>/>
```

- `index.txt` is required because jar resources cannot be enumerated
  portably.  It lists every file to extract (the four core libs AND all
  `*.module.so` module libraries — openDAQ scans the directory as its module
  path, so the whole set must land in one directory).
- Also include `org/opendaq/natives/<platform>/tag.txt` holding the
  native-binaries tag (e.g. `binaries-20260703-869cb62`) so the extractor can
  key its cache identically to the download path.

## Build changes

1. **Fetching the archives (build time, all platforms).**  The local machine
   only builds `linux-x64`, but the pinned release archives exist for all
   three platforms.  Add a Makefile target:
   - `make natives-jars` — for each platform in
     `src/main/resources/org/opendaq/native-binaries.properties`: download
     `<base-url><archive.PLATFORM>` into `target/natives-work/`, verify
     `sha256.PLATFORM`, unpack, generate `index.txt` + `tag.txt`, and jar it
     up as `target/opendaq-java-<version>-natives-<platform>.jar`.
   - Prefer reusing an existing local `../cl-opendaq/bin/<platform>/` over
     downloading when present (dev loop), same precedence idea as the loader.
2. **Maven wiring.**  Two acceptable routes; pick (a) unless it fights Maven:
   - (a) `maven-assembly-plugin` with three assembly descriptors (one per
     platform, `<formats><format>jar</format></formats>`, files pulled from
     `target/natives-work/<platform>/`), bound to `package`, each producing
     the classifier artifact and attached via `attach=true`.  Skip gracefully
     (profile `-Pnatives`) when the archives haven't been fetched, so plain
     `mvn package` stays fast and offline.
   - (b) keep jar creation in the Makefile (plain `jar cf`) and attach at
     deploy time with `build-helper-maven-plugin:attach-artifact`.
3. **`opendaq-java-platform` pom module** (optional, can be deferred):
   a tiny `pom`-packaging module with the three classifier dependencies.

## Runtime changes (`NativeLoader`)

Add a classpath probe between the env-var check and the download:

1. `classpathNativeDirectory()`:
   - resource root: `/org/opendaq/natives/<currentPlatformName()>/`;
   - if `index.txt` is absent → return null (no natives jar on classpath);
   - read `tag.txt` (fallback: the manifest tag) → target dir
     `cacheRoot(tag)/<platform>/` — **the same directory the downloader
     uses**, so both paths converge and share the cache;
   - if the target dir already contains a `.complete` marker → return it;
   - otherwise extract every `index.txt` entry via
     `getResourceAsStream` → `Files.copy` into a temp sibling directory, then
     atomically rename onto the target and write `.complete`.  (Add the same
     `.complete` marker to the download/untar path so the two stay
     symmetrical; treat a cache dir without a marker as incomplete and
     re-populate it.)
2. Insert into `nativeLibraryDirectory()` between the env override and the
   cached/download steps.  Everything downstream (`ensureLoaded`, healthcheck)
   is unchanged; add the classpath candidate to the healthcheck report.
3. No new dependencies; keep it `java.base` only.

## Tests / verification

All in Docker (`java-opendaq-dev`), as usual:

1. Unit test for `index.txt`-driven extraction into an empty fake cache
   (can use a natives jar built for linux-x64 on the test classpath).
2. End-to-end proof, mirroring the earlier download test: run the quickstart
   with classpath = main jar + `natives-linux-x64` jar, fresh `HOME`,
   **`OPENDAQ_NO_DOWNLOAD=1` set** → must find devices with zero network
   access (proves the download is no longer needed).
3. Precedence tests: `OPENDAQ_JAVA_NATIVE_DIR` still wins over classpath;
   download fallback still works when no natives jar is present.
4. Full `mvn test` + a couple of examples, unchanged.

## Docs

- README "Native binaries" section: rewrite around the dependency snippet
  (`<classifier>natives-linux-x64</classifier>`, and the `-platform`
  aggregator), with the download demoted to "no natives artifact on the
  classpath" fallback.
- README quickstart: mention adding the natives dependency for your platform.
- `tools/README.md`: document `make natives-jars`.

## Out of scope / later

- Publishing pipeline (GitHub Actions to `mvn deploy` the main + classifier
  jars to a repository) — depends on where the artifacts will be hosted;
  coordinates `org.opendaq:*` may need to change at publish time.
- macOS/Windows smoke testing of extraction (no such hosts here; the code
  paths are platform-independent except `libraryFileNames()`, already done).
- Fat "all platforms in the main jar" variant — rejected (~45 MB).

## Execution order (suggested)

1. `NativeLoader` classpath probe + `.complete` markers (+ unit test).
2. Makefile `natives-jars` target (linux-x64 from local bin, others via
   download-and-verify).
3. Maven attach wiring behind `-Pnatives`.
4. End-to-end no-network test; precedence tests.
5. README / tools docs.
6. (Optional) `opendaq-java-platform` pom module.
