# Examples

Each example is a self-contained Java program with a `main` method that runs
against the openDAQ library. You need a JDK 22 or newer (the bindings use the
FFM API, final since Java 22); any example runs straight from source, no
separate `javac` step, thanks to the single-file source launcher.

## Running an example

### With the downloaded release jars

From the folder holding the two jars (the main jar and the `natives-<platform>`
jar for your OS — `linux-x86_64`, `osx-aarch_64`, or `windows-x86_64`):

```bash
java --enable-native-access=ALL-UNNAMED \
  -cp jopendaq-0.1.0.jar:jopendaq-0.1.0-natives-linux-x86_64.jar \
  examples/StreamReaderExample.java
```

Swap `StreamReaderExample` for any other file in this folder. On Windows, use
`;` instead of `:` as the classpath separator.

### From a local build

Entirely inside the dev container (builds the project and natives as needed):

```bash
make example NAME=StreamReaderExample
```

Or directly, once the project is built (`make package`) and the natives are
built (`make natives`, which writes them to `bin/<platform>/`):

```bash
OPENDAQ_JAVA_NATIVE_DIR=bin/linux-x86_64 \
java --enable-native-access=ALL-UNNAMED \
  -cp target/classes \
  examples/StreamReaderExample.java
```
