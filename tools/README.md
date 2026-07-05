# Tools

This folder contains the python scripts that generate the Java binding files
in `src/generated/java/`.

- `parse_bindings.py` is the general-purpose openDAQ C header parser shared
  with the cl-opendaq bindings: it walks `openDAQ/bindings/c/include` and
  emits a JSON description of every interface, function, typedef, and error
  code.  See the comment at the top of the script for the schema.
- `bindings_model.py` resolves that description into a typed model for Java:
  C types onto `java.lang.foreign` layouts and Java carrier types, and each
  function parameter classified as an input, an output slot, or in-out.
- `generate_low_level_bindings.py` emits the 1:1 FFI layer
  (`org.opendaq.lowlevel`): one class of checked static wrappers per C
  receiver, plus the Java enums and error-code table.
- `generate_high_level_bindings.py` emits the idiomatic wrapper classes
  (`org.opendaq`): the interface hierarchy, constructors and factories,
  boxing/unboxing conversions, and the type registry.  Classes listed in its
  `MANUAL_*` tables get hand-written parts injected from `manual/*.java.inc`
  (readers, data packets, callables, ...).

Regenerate with `make bindings` (see the repository Makefile), or directly:

```bash
python3 tools/generate_low_level_bindings.py --opendaq-repo path/to/openDAQ
python3 tools/generate_high_level_bindings.py --opendaq-repo path/to/openDAQ
```
