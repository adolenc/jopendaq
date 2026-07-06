# Development / CI image for the openDAQ Java bindings and the example ports.
#
# The bindings target the Java 22+ Foreign Function & Memory API (Panama), so a
# JDK 22 or newer is required; we pin the current LTS.  Maven drives the build
# and tests.  On top of the JDK we add the Clojure CLI and scala-cli, so the
# Clojure/Scala ports of the Java examples (examples/clojure, examples/scala) run
# in this same image -- one image covers `make build`/`test`/`package`/`example`
# and `make clojure-example`/`scala-example` alike.  Both example toolchains
# fetch their own runtime (Clojure itself, the Scala 3 compiler) from Maven
# Central on first use into the mounted, persisted .cache/, so only the first run
# downloads.
FROM eclipse-temurin:25-jdk

RUN apt-get update \
    && apt-get install -y --no-install-recommends maven python3 curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Clojure CLI: the `clojure` / `clj` launchers.
RUN curl -fsSL https://download.clojure.org/install/linux-install.sh -o /tmp/clojure-install.sh \
    && bash /tmp/clojure-install.sh \
    && rm /tmp/clojure-install.sh

# scala-cli: a single-file Scala 3 runner (statically linked native launcher).
RUN curl -fsSL https://github.com/VirtusLab/scala-cli/releases/latest/download/scala-cli-x86_64-pc-linux-static.gz \
      | gzip -d > /usr/local/bin/scala-cli \
    && chmod +x /usr/local/bin/scala-cli

WORKDIR /workspace
