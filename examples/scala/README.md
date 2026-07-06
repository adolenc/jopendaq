# Scala examples

Line-for-line Scala 3 ports of the [Java examples](../java) — the openDAQ
bindings are plain Java classes, so they are used straight from Scala
(`Instance()`, `instance.addDevice(...)`, `.asType(classOf[Channel])`). Each
file is a self-contained `object` with a `main`; run it with
[scala-cli](https://scala-cli.virtuslab.org/) on a JDK 22 or newer (the bindings
use the FFM API, final since Java 22).

One wrinkle: openDAQ ships a `com.opendaq.Unit` class, which would shadow
`scala.Unit`, so each file imports with `import com.opendaq.{Unit as _, *}` to
keep `Unit` meaning `scala.Unit`.

## Running an example

scala-cli runs a single source file directly, given the two published jars on
the classpath — the main jar and the `natives-<platform>` jar the loader
extracts the native libraries from — and the FFM flag. From the repository root
(which holds the jars; swap the natives jar for your OS —  `osx-aarch_64`,
`windows-x86_64` — if you are not on `linux-x86_64`):

```bash
scala-cli run examples/scala/StreamReaderExample.scala \
  --jar jopendaq-0.1.0.jar \
  --jar jopendaq-0.1.0-natives-linux-x86_64.jar \
  --java-opt=--enable-native-access=ALL-UNNAMED
```

Swap `StreamReaderExample` for any other file here. Add `--server=false` for a
one-shot compile without leaving a background build server running.

## From a local build (dev container)

The `java-opendaq-dev` image bundles scala-cli and the Clojure CLI alongside the
JDK, so the ports run in the same container as the Java build. Build the image
once, then run any example by name:

```bash
make docker-image                                  # once
make scala-example NAME=StreamReaderExample
```

`make scala-example` runs the command above inside that container, with the
Scala/coursier and native caches redirected into the mounted `.cache/` so only
the first run downloads the Scala compiler.
