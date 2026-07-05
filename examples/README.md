# Examples

Each example is a self-contained Java program with a `main` method, compiled
against the library classes.

## Running an example

From the repository root, with Docker:

```bash
make example NAME=StreamReaderExample
```

or, with a local JDK 22+ and the project built (`mvn package`):

```bash
javac -cp target/classes -d target/examples examples/StreamReaderExample.java
java --enable-native-access=ALL-UNNAMED -cp target/classes:target/examples StreamReaderExample
```

All examples run against the bundled reference device simulator
(`daqref://device0`), except `ConnectViewOnlyClientExample`, which needs a
networked openDAQ device (set `OPENDAQ_DEVICE`, default `daq.nd://127.0.0.1`).
