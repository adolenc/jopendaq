# Development / CI image for the openDAQ Java bindings.
#
# The bindings target the Java 22+ Foreign Function & Memory API (Panama), so a
# JDK 22 or newer is required; we pin the current LTS.  Maven drives the build
# and tests, python3 runs the binding generators in tools/.
FROM eclipse-temurin:25-jdk

RUN apt-get update \
    && apt-get install -y --no-install-recommends maven python3 curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
