# Development workflow for the openDAQ Java bindings.
#
# The bindings are generated from the openDAQ API spec (opendaq.json) of one
# release of the opendaq-bindings-nightly repository, and run against the C
# bindings libraries of that same release.  opendaq-nightly.lock says which;
# nothing here builds or even clones openDAQ.
#
# The Java/Maven steps run in the java-opendaq-dev container, so the host needs
# Docker for them.  `make bindings`, `make natives` and `make bump-nightly` are
# the exceptions and run directly on the host with python3 (standard library
# only):
#
#   make bindings      regenerate src/generated/java from the pinned spec
#   make natives       download the pinned native libraries into bin/<triple>/
#   make bump-nightly TAG=<release>
#                      pin another nightly release, then `make bindings`
#   make test          run the JUnit suite against the bin/<triple>/ natives
#                      (fetch them with `make natives` first)
#
# End users who only run the bindings never need `make natives`: they add the
# published natives-<triple> jar for their OS to the classpath and the loader
# extracts it into a per-user cache on first use -- there is no network access.

DOCKER_IMAGE ?= java-opendaq-dev
OPENDAQ_RUNTIME_TRIPLE ?= linux-x86_64
NATIVES_DIR ?= $(CURDIR)/bin/$(OPENDAQ_RUNTIME_TRIPLE)
PYTHON ?= python3
M2_CACHE := $(CURDIR)/.m2
GENERATED_DIR := $(CURDIR)/src/generated/java
PROJECT_VERSION := $(shell grep -m1 '<version>' pom.xml | sed -E 's|.*<version>(.*)</version>.*|\1|')
NATIVES_JAR := target/jopendaq-$(PROJECT_VERSION)-natives-$(OPENDAQ_RUNTIME_TRIPLE).jar
# Staged per platform, so that the jar of one never picks up another's libraries.
NATIVES_JAR_STAGE := target/natives-jar/$(OPENDAQ_RUNTIME_TRIPLE)
NIGHTLY_TAG := $(shell $(PYTHON) -c "import json; print(json.load(open('opendaq-nightly.lock'))['tag'])" 2>/dev/null)

# The nightly names its platforms differently from the Maven os classifiers.
NIGHTLY_TRIPLE_linux-x86_64 := linux-x64
NIGHTLY_TRIPLE_osx-aarch_64 := darwin-arm64
NIGHTLY_TRIPLE_windows-x86_64 := windows-x64
NIGHTLY_TRIPLE := $(NIGHTLY_TRIPLE_$(OPENDAQ_RUNTIME_TRIPLE))

USER_SPEC := $(shell id -u):$(shell id -g)

# Everything below runs in the dev container as the calling user, with the repo
# mounted at /workspace and Maven's repository cached in .m2/.  Natives fetched
# into bin/<triple>/ are mounted read-only and pointed at by the loader
# via OPENDAQ_JAVA_NATIVE_DIR; XDG_CACHE_HOME is redirected into the persisted
# .cache/ so the loader's per-user native cache survives across container runs.
NATIVES_MOUNT := $(if $(wildcard $(NATIVES_DIR)/*.so),\
  -v $(NATIVES_DIR):/natives:ro -e OPENDAQ_JAVA_NATIVE_DIR=/natives,)

DOCKER_RUN := docker run --rm \
  -u $(USER_SPEC) \
  -v $(CURDIR):/workspace \
  -v $(M2_CACHE):/workspace/.m2 \
  $(NATIVES_MOUNT) \
  -e MAVEN_OPTS="-Dmaven.repo.local=/workspace/.m2" \
  -e XDG_CACHE_HOME=/workspace/.cache \
  -w /workspace \
  $(DOCKER_IMAGE)

.PHONY: docker-image natives natives-jar bindings bump-nightly \
        build test package example clojure-example scala-example repl-shell clean

# One image with every tool the containerised targets need: the JDK + Maven for
# the Java build/test targets and the Clojure CLI + scala-cli for the example
# ports.  Build it once; every docker-backed target below uses it.
docker-image:
	docker build -t $(DOCKER_IMAGE) .

# Download the pinned native libraries for OPENDAQ_RUNTIME_TRIPLE into
# bin/<triple>/, verified against the checksums of the release's manifest.
natives:
	@test -n "$(NIGHTLY_TRIPLE)" || { echo "No nightly platform for $(OPENDAQ_RUNTIME_TRIPLE)."; exit 1; }
	@set -e; \
	spec=$$($(PYTHON) tools/fetch_nightly.py --natives $(NIGHTLY_TRIPLE)); \
	mkdir -p $(NATIVES_DIR); \
	cp -a "$$(dirname "$$spec")/natives/$(NIGHTLY_TRIPLE)/." $(NATIVES_DIR)/; \
	ls -1 $(NATIVES_DIR)

# Package the bin/<triple>/ libraries into a natives-<triple> classifier
# jar (resources under com/opendaq/natives/<triple>/ with an index.txt file list
# and a tag.txt cache key), which the NativeLoader extracts at runtime.  This is
# what CI publishes to the release for each platform.
natives-jar:
	@if [ -z "$$(ls $(NATIVES_DIR)/ 2>/dev/null)" ]; then \
	  echo "No native libraries in $(NATIVES_DIR) -- run 'make natives' first."; exit 1; fi
	mkdir -p $(NATIVES_JAR_STAGE)/com/opendaq/natives/$(OPENDAQ_RUNTIME_TRIPLE)
	cp -a $(NATIVES_DIR)/. $(NATIVES_JAR_STAGE)/com/opendaq/natives/$(OPENDAQ_RUNTIME_TRIPLE)/
	ls -1 $(NATIVES_DIR) > $(NATIVES_JAR_STAGE)/com/opendaq/natives/$(OPENDAQ_RUNTIME_TRIPLE)/index.txt
	echo "$(NIGHTLY_TAG)" > $(NATIVES_JAR_STAGE)/com/opendaq/natives/$(OPENDAQ_RUNTIME_TRIPLE)/tag.txt
	$(DOCKER_RUN) jar --create --file $(NATIVES_JAR) -C $(NATIVES_JAR_STAGE) .
	@echo "wrote $(NATIVES_JAR)"

# Regenerate src/generated/java from the spec of the pinned nightly.
bindings:
	@set -e; \
	spec=$$($(PYTHON) tools/fetch_nightly.py); \
	$(PYTHON) tools/generate_low_level_bindings.py --spec "$$spec" --output-dir $(GENERATED_DIR); \
	$(PYTHON) tools/generate_high_level_bindings.py --spec "$$spec" --output-dir $(GENERATED_DIR); \
	echo "regenerated from $$(basename "$$(dirname "$$spec")")"

# Move the pin to another nightly and regenerate:
#   make bump-nightly TAG=nightly-20261110-0e29cce-10aaf38
bump-nightly:
	@test -n "$(TAG)" || { echo "usage: make bump-nightly TAG=<release tag>"; exit 1; }
	$(PYTHON) tools/fetch_nightly.py --pin $(TAG) > /dev/null
	$(MAKE) bindings

build:
	mkdir -p $(M2_CACHE)
	$(DOCKER_RUN) mvn -q compile

test:
	mkdir -p $(M2_CACHE)
	$(DOCKER_RUN) mvn test

package:
	mkdir -p $(M2_CACHE)
	$(DOCKER_RUN) mvn -q -DskipTests package

# Run one Java example, e.g.:  make example NAME=StreamReaderExample
example:
	mkdir -p $(M2_CACHE)
	$(DOCKER_RUN) bash -c "mvn -q -DskipTests package && \
	  javac -cp target/classes -d target/examples examples/java/$(NAME).java && \
	  java --enable-native-access=ALL-UNNAMED -cp target/classes:target/examples $(NAME)"

# ---------------------------------------------------------------------------
# Clojure / Scala example ports (examples/clojure, examples/scala).
#
# These run in the same $(DOCKER_IMAGE) image (which bundles the Clojure CLI and
# scala-cli alongside the JDK -- build it once with `make docker-image`).  Both
# put the two published jars on the classpath -- the main jar and the natives jar
# the loader extracts from -- exactly like the "downloaded release jars" flow in
# the example READMEs, so the jopendaq-*.jar files must be present in the repo
# root (build them with `make package` + `make natives natives-jar` and copy them
# up, or download them from a release).  Unlike DOCKER_RUN, the natives are not
# mounted -- the examples exercise the classpath natives jar -- and
# HOME/COURSIER_CACHE point into the mounted .cache/ so the Clojure and Scala
# runtimes download themselves only once.
MAIN_JAR := jopendaq-$(PROJECT_VERSION).jar
NATIVES_CP_JAR := jopendaq-$(PROJECT_VERSION)-natives-$(OPENDAQ_RUNTIME_TRIPLE).jar

DOCKER_RUN_EXAMPLES := docker run --rm \
  -u $(USER_SPEC) \
  -v $(CURDIR):/workspace \
  -v $(M2_CACHE):/workspace/.m2 \
  -e MAVEN_OPTS="-Dmaven.repo.local=/workspace/.m2" \
  -e XDG_CACHE_HOME=/workspace/.cache \
  -e HOME=/workspace/.cache/lang-home \
  -e COURSIER_CACHE=/workspace/.cache/coursier \
  -w /workspace \
  $(DOCKER_IMAGE)

# Run one Clojure example, e.g.:  make clojure-example NAME=StreamReaderExample
clojure-example:
	mkdir -p $(CURDIR)/.cache/lang-home
	$(DOCKER_RUN_EXAMPLES) bash -c "cd examples/clojure && clojure -M:jopendaq $(NAME).clj"

# Run one Scala example, e.g.:  make scala-example NAME=StreamReaderExample
scala-example:
	mkdir -p $(CURDIR)/.cache/lang-home
	$(DOCKER_RUN_EXAMPLES) scala-cli run examples/scala/$(NAME).scala \
	  --jar $(MAIN_JAR) --jar $(NATIVES_CP_JAR) \
	  --java-opt=--enable-native-access=ALL-UNNAMED --server=false

# An interactive shell inside the dev container.
repl-shell:
	mkdir -p $(M2_CACHE)
	docker run --rm -it \
	  -u $(USER_SPEC) \
	  -v $(CURDIR):/workspace \
	  -v $(M2_CACHE):/workspace/.m2 \
	  $(NATIVES_MOUNT) \
	  -e MAVEN_OPTS="-Dmaven.repo.local=/workspace/.m2" \
	  -e XDG_CACHE_HOME=/workspace/.cache \
	  -w /workspace \
	  $(DOCKER_IMAGE) bash

clean:
	rm -rf target .m2 .cache bin tmp
