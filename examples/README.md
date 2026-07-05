# Examples

Stand-alone example programs covering the common openDAQ flows — device
discovery, readers, function blocks, search filters, core events, hand-built
signals, and more. Because the bindings are plain Java classes, the same
programs are provided in three JVM languages, one file per example in each:

| Language | Folder | Run with |
|----------|--------|----------|
| Java     | [`java/`](./java)       | the single-file source launcher (`java …Example.java`) |
| Clojure  | [`clojure/`](./clojure) | the Clojure CLI (`clojure -M:jopendaq …Example.clj`) |
| Scala    | [`scala/`](./scala)     | scala-cli (`scala-cli run …Example.scala`) |

Each folder has its own README with the exact commands. All of them need a JDK
22 or newer (the bindings use the Foreign Function & Memory API, final since
Java 22) and the two published jars — the main jar and the `natives-<platform>`
jar for your OS — which the run commands put on the classpath.
