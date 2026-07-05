# Development workflow for the openDAQ Java bindings.
#
# The host machine needs only Docker: every Java/Maven/Python step runs inside
# the java-opendaq-dev image (see Dockerfile).  Native openDAQ libraries are
# taken from the sibling cl-opendaq checkout's bin/<platform>/ by default (a
# `make bindings` build there; end users get the pinned release archives
# instead).  The C headers used to regenerate the bindings come from a pinned
# openDAQ clone: `make bindings` clones it into tmp/openDAQ when missing, or
# set OPENDAQ_SRC_DIR to an existing checkout (e.g. cl-opendaq's tmp/openDAQ).

DOCKER_IMAGE ?= java-opendaq-dev
OPENDAQ_RUNTIME_TRIPLE ?= linux-x64
CL_OPENDAQ_DIR ?= $(abspath $(CURDIR)/../cl-opendaq)
NATIVES_DIR ?= $(CL_OPENDAQ_DIR)/bin/$(OPENDAQ_RUNTIME_TRIPLE)
OPENDAQ_REPO_URL ?= https://github.com/adolenc/openDAQ.git
OPENDAQ_REF ?= c-bindings-docstrings
OPENDAQ_SRC_DIR ?= $(CURDIR)/tmp/openDAQ
PYTHON ?= python3
M2_CACHE := $(CURDIR)/.m2

GENERATED_DIR := $(CURDIR)/src/generated/java

# Everything below runs in the dev container as the calling user, with the
# repo mounted at /workspace, the natives at /natives, and Maven's repository
# cached in .m2/ so repeated builds are offline-fast.
DOCKER_RUN := docker run --rm \
  -u $(shell id -u):$(shell id -g) \
  -v $(CURDIR):/workspace \
  -v $(M2_CACHE):/workspace/.m2 \
  -v $(NATIVES_DIR):/natives:ro \
  -e OPENDAQ_JAVA_NATIVE_DIR=/natives \
  -e MAVEN_OPTS="-Dmaven.repo.local=/workspace/.m2" \
  -w /workspace \
  $(DOCKER_IMAGE)

.PHONY: docker-image clone-opendaq bindings build test package example repl-shell clean

docker-image:
	docker build -t $(DOCKER_IMAGE) .

# Clone the pinned openDAQ source (only the C binding headers are needed).
clone-opendaq:
	@if [ ! -d "$(OPENDAQ_SRC_DIR)" ]; then \
	  git clone --depth 1 --branch $(OPENDAQ_REF) $(OPENDAQ_REPO_URL) $(OPENDAQ_SRC_DIR); \
	else \
	  echo "openDAQ source already present at $(OPENDAQ_SRC_DIR)"; \
	fi

# Regenerate src/generated/java from the openDAQ C binding headers.
bindings: clone-opendaq
	$(PYTHON) tools/generate_low_level_bindings.py \
	  --opendaq-repo $(OPENDAQ_SRC_DIR) --output-dir $(GENERATED_DIR)
	$(PYTHON) tools/generate_high_level_bindings.py \
	  --opendaq-repo $(OPENDAQ_SRC_DIR) --output-dir $(GENERATED_DIR)

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
	  -u $(shell id -u):$(shell id -g) \
	  -v $(CURDIR):/workspace \
	  -v $(M2_CACHE):/workspace/.m2 \
	  -v $(NATIVES_DIR):/natives:ro \
	  -e OPENDAQ_JAVA_NATIVE_DIR=/natives \
	  -e MAVEN_OPTS="-Dmaven.repo.local=/workspace/.m2" \
	  -w /workspace \
	  $(DOCKER_IMAGE) bash

clean:
	rm -rf target .m2 tmp
