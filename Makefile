# Development workflow for the openDAQ Java bindings.
#
# The Java/Maven/Python steps run in the java-opendaq-dev container, so the host
# needs Docker for them.  `make natives` is the exception: it builds the openDAQ
# runtime from source with the host's C/C++ toolchain (CMake, a compiler, git),
# exactly like cl-opendaq's `make bindings` -- no container involved.  The build
# is self-contained, cloning openDAQ at the pinned ref with no sibling checkout:
#
#   make natives    build libcopendaq.so + friends from source into bin/<triple>/
#                   (needs CMake + a C/C++ toolchain on the host)
#   make bindings   regenerate src/generated/java from the openDAQ C headers
#   make test       run the JUnit suite against the bin/<triple>/ natives
#                   (build them with `make natives` first)
#
# End users who only run the bindings never need `make natives`: they add the
# published natives-<triple> jar for their OS to the classpath and the loader
# extracts it into a per-user cache on first use -- there is no network access.

DOCKER_IMAGE ?= java-opendaq-dev
OPENDAQ_RUNTIME_TRIPLE ?= linux-x86_64
OPENDAQ_REPO_URL ?= https://github.com/adolenc/openDAQ.git
OPENDAQ_REF ?= c-bindings-docstrings
OPENDAQ_SRC_DIR ?= $(CURDIR)/tmp/openDAQ
OPENDAQ_BUILD_DIR ?= $(CURDIR)/tmp/build
NATIVES_DIR ?= $(CURDIR)/bin/$(OPENDAQ_RUNTIME_TRIPLE)
PYTHON ?= python3
JOBS ?= $(shell nproc 2>/dev/null || echo 4)
M2_CACHE := $(CURDIR)/.m2
GENERATED_DIR := $(CURDIR)/src/generated/java
PROJECT_VERSION := $(shell grep -m1 '<version>' pom.xml | sed -E 's|.*<version>(.*)</version>.*|\1|')
NATIVES_JAR := target/jopendaq-$(PROJECT_VERSION)-natives-$(OPENDAQ_RUNTIME_TRIPLE).jar

USER_SPEC := $(shell id -u):$(shell id -g)

# openDAQ CMake configuration -- the C bindings plus the reference device and
# function-block modules, everything else off.  Identical to the flags
# cl-opendaq's `make bindings` builds its runtime with.
OPENDAQ_CMAKE_ARGS := \
  -DOPENDAQ_GENERATE_C_BINDINGS=ON \
  -DOPENDAQ_GENERATE_PYTHON_BINDINGS=OFF \
  -DOPENDAQ_GENERATE_DELPHI_BINDINGS=OFF \
  -DOPENDAQ_GENERATE_CSHARP_BINDINGS=OFF \
  -DOPENDAQ_ENABLE_TESTS=OFF \
  -DOPENDAQ_ENABLE_TEST_UTILS=OFF \
  -DOPENDAQ_ENABLE_ACCESS_CONTROL=OFF \
  -DOPENDAQ_ENABLE_NATIVE_STREAMING=ON \
  -DDAQMODULES_OPENDAQ_CLIENT_MODULE=ON \
  -DDAQMODULES_REF_DEVICE_MODULE=ON \
  -DDAQMODULES_REF_FB_MODULE=ON \
  -DDAQMODULES_REF_FB_MODULE_ENABLE_RENDERER=OFF \
  -DBOOST_LOCALE_ENABLE_ICU=OFF

# Everything below runs in the dev container as the calling user, with the repo
# mounted at /workspace and Maven's repository cached in .m2/.  Locally-built
# natives in bin/<triple>/ are mounted read-only and pointed at by the loader
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

.PHONY: docker-image clone-opendaq natives natives-jar bindings \
        build test package example repl-shell clean

docker-image:
	docker build -t $(DOCKER_IMAGE) .

# Clone the pinned openDAQ source (shared by `natives` and `bindings`).  A full
# clone + checkout --force resolves a branch, tag, or raw commit sha alike.
clone-opendaq:
	@if [ ! -d "$(OPENDAQ_SRC_DIR)/.git" ]; then \
	  rm -rf "$(OPENDAQ_SRC_DIR)"; \
	  git clone $(OPENDAQ_REPO_URL) $(OPENDAQ_SRC_DIR); \
	  git -C $(OPENDAQ_SRC_DIR) checkout --force $(OPENDAQ_REF); \
	else \
	  echo "openDAQ source already present at $(OPENDAQ_SRC_DIR)"; \
	fi

# Build the openDAQ native libraries from source into bin/<triple>/, using the
# host's C/C++ toolchain (CMake, a compiler, git) -- the same way cl-opendaq
# builds its runtime.
natives: clone-opendaq
	mkdir -p $(NATIVES_DIR)
	cmake -S $(OPENDAQ_SRC_DIR) -B $(OPENDAQ_BUILD_DIR) $(OPENDAQ_CMAKE_ARGS)
	cmake --build $(OPENDAQ_BUILD_DIR) -j$(JOBS)
	cp -a $(OPENDAQ_BUILD_DIR)/bin/*.so $(NATIVES_DIR)/

# Package the built bin/<triple>/ libraries into a natives-<triple> classifier
# jar (resources under com/opendaq/natives/<triple>/ with an index.txt file list
# and a tag.txt cache key), which the NativeLoader extracts at runtime.  This is
# what CI publishes to the release for each platform.
natives-jar:
	@if [ -z "$$(ls $(NATIVES_DIR)/ 2>/dev/null)" ]; then \
	  echo "No native libraries in $(NATIVES_DIR) -- run 'make natives' first."; exit 1; fi
	rm -rf target/natives-jar
	mkdir -p target/natives-jar/com/opendaq/natives/$(OPENDAQ_RUNTIME_TRIPLE)
	cp -a $(NATIVES_DIR)/. target/natives-jar/com/opendaq/natives/$(OPENDAQ_RUNTIME_TRIPLE)/
	ls -1 $(NATIVES_DIR) > target/natives-jar/com/opendaq/natives/$(OPENDAQ_RUNTIME_TRIPLE)/index.txt
	git -C $(OPENDAQ_SRC_DIR) rev-parse --short HEAD \
	  > target/natives-jar/com/opendaq/natives/$(OPENDAQ_RUNTIME_TRIPLE)/tag.txt 2>/dev/null \
	  || echo "$(PROJECT_VERSION)" > target/natives-jar/com/opendaq/natives/$(OPENDAQ_RUNTIME_TRIPLE)/tag.txt
	$(DOCKER_RUN) jar --create --file $(NATIVES_JAR) -C target/natives-jar .
	@echo "wrote $(NATIVES_JAR)"

# Regenerate src/generated/java from the openDAQ C binding headers.
bindings: clone-opendaq
	mkdir -p $(M2_CACHE)
	$(DOCKER_RUN) $(PYTHON) tools/generate_low_level_bindings.py \
	  --opendaq-repo tmp/openDAQ --output-dir src/generated/java
	$(DOCKER_RUN) $(PYTHON) tools/generate_high_level_bindings.py \
	  --opendaq-repo tmp/openDAQ --output-dir src/generated/java

build:
	mkdir -p $(M2_CACHE)
	$(DOCKER_RUN) mvn -q compile

test:
	mkdir -p $(M2_CACHE)
	$(DOCKER_RUN) mvn test

package:
	mkdir -p $(M2_CACHE)
	$(DOCKER_RUN) mvn -q -DskipTests package

# Run one example, e.g.:  make example NAME=StreamReaderExample
example:
	mkdir -p $(M2_CACHE)
	$(DOCKER_RUN) bash -c "mvn -q -DskipTests package && \
	  javac -cp target/classes -d target/examples examples/$(NAME).java && \
	  java --enable-native-access=ALL-UNNAMED -cp target/classes:target/examples $(NAME)"

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
