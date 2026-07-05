# Extends the dev image with Clojure and Scala toolchains, so the ports of the
# Java examples in examples/clojure/ and examples/scala/ can be run and tested
# the same way the Java ones are.  The base java-opendaq-dev image already
# supplies the JDK everything runs on (the bindings need JDK 22+); here we add
# the Clojure CLI and scala-cli on top.
#
# Build it with `make examples-image`, run examples with `make clojure-example`
# / `make scala-example` (see the Makefile).  Both toolchains fetch their own
# runtime (Clojure itself, the Scala 3 compiler) from Maven Central on first
# use into the mounted, persisted .cache/, so only the first run downloads.
FROM java-opendaq-dev

# Clojure CLI: the `clojure` / `clj` launchers.
RUN curl -fsSL https://download.clojure.org/install/linux-install.sh -o /tmp/clojure-install.sh \
    && bash /tmp/clojure-install.sh \
    && rm /tmp/clojure-install.sh

# scala-cli: a single-file Scala 3 runner (statically linked native launcher).
RUN curl -fsSL https://github.com/VirtusLab/scala-cli/releases/latest/download/scala-cli-x86_64-pc-linux-static.gz \
      | gzip -d > /usr/local/bin/scala-cli \
    && chmod +x /usr/local/bin/scala-cli
