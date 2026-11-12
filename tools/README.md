# Tools

This folder contains the python scripts that generate the Java binding files
in `src/generated/java/`.

- `fetch_nightly.py` downloads the release of
  [opendaq-bindings-nightly](https://github.com/adolenc/opendaq-bindings-nightly)
  pinned in `opendaq-nightly.lock`: the API spec `opendaq.json` and, on
  request, the native libraries, each verified against the checksums in the
  release's manifest.
- `spec_bindings.py` turns `opendaq.json` into binding records (every
  interface, function, typedef, and error code).  The spec describes the C API
  exactly as the library exports it and defines every type it uses, so nothing
  is parsed out of a header and the script carries no tables.  See the comment
  at the top of it for the schema.  Both scripts are shared with the cl-opendaq
  bindings.
- `bindings_model.py` resolves that description into a typed model for Java:
  C types onto `java.lang.foreign` layouts and Java carrier types, and each
  function parameter classified as an input, an output slot, or in-out.
- `generate_low_level_bindings.py` emits the 1:1 FFI layer
  (`com.opendaq.lowlevel`): one class of checked static wrappers per C
  receiver, plus the Java enums and error-code table.
- `generate_high_level_bindings.py` emits the idiomatic wrapper classes
  (`com.opendaq`): the interface hierarchy, constructors and factories,
  boxing/unboxing conversions, and the type registry.  Classes listed in its
  `MANUAL_*` tables get hand-written parts injected from `manual/*.java.inc`
  (readers, data packets, callables, ...).

Regenerate with `make bindings` (see the repository Makefile), or directly:

```bash
spec=$(python3 tools/fetch_nightly.py)
python3 tools/generate_low_level_bindings.py --spec "$spec"
python3 tools/generate_high_level_bindings.py --spec "$spec"
```
