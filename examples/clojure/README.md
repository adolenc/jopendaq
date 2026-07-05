# Clojure examples

Line-for-line Clojure ports of the [Java examples](../java) — the openDAQ
bindings are plain Java classes, so they are used straight from Clojure through
normal interop (`(Instance.)`, `(.addDevice instance ...)`,
`(.asType c Channel)`). Every file is a self-contained script; run it with the
[Clojure CLI](https://clojure.org/guides/install_clojure) on a JDK 22 or newer
(the bindings use the FFM API, final since Java 22).

## Running an example

The [`deps.edn`](./deps.edn) here defines a `:jopendaq` alias that puts the two
published jars on the classpath — the main jar and the `natives-<platform>` jar
the loader extracts the native libraries from — and enables the FFM API. It
expects the jars in the repository root (the "downloaded release jars" flow from
the top-level README); if you are not on `linux-x86_64`, swap the natives jar in
`deps.edn` for the one matching your OS (`osx-aarch_64`, `windows-x86_64`).

From this folder:

```bash
clojure -M:jopendaq StreamReaderExample.clj
```

Swap `StreamReaderExample` for any other file here.

## From a local build (dev container)

The Clojure CLI is not part of the base dev image, so the ports run in a
dedicated `java-opendaq-examples` image (the dev image plus the Clojure CLI and
scala-cli). Build it once, then run any example by name:

```bash
make examples-image                                  # once
make clojure-example NAME=StreamReaderExample
```

`make clojure-example` runs the command above inside that container, with the
Clojure and native caches redirected into the mounted `.cache/` so only the
first run downloads Clojure itself.
