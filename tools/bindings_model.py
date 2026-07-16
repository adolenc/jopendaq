#!/usr/bin/env python3
"""bindings_model.py - Shared model for the Java binding generators.

Loads the openDAQ C API description produced by parse_bindings.py (the same
generic parser the Common Lisp bindings use) and resolves it into a typed
model both generate_low_level_bindings.py and generate_high_level_bindings.py
consume: a type table mapping C types onto java.lang.foreign layouts and Java
carrier types, and a function model with each parameter classified as an
input, an output slot, or an in-out slot.

The in/out classification and its reader overrides mirror the reference
implementation in cl-opendaq/tools/generate_low_level_bindings.py.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

import parse_bindings


# ---------------------------------------------------------------------------
# Name utilities
# ---------------------------------------------------------------------------

JAVA_KEYWORDS = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "const", "continue", "default", "do", "double", "else", "enum",
    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native", "new",
    "package", "private", "protected", "public", "record", "return", "short",
    "static", "strictfp", "super", "switch", "synchronized", "this", "throw",
    "throws", "transient", "try", "var", "void", "volatile", "while", "yield",
}

_TOKEN_RE = re.compile(r"[A-Z]+(?=[A-Z][a-z]|[0-9]|\b)|[A-Z]?[a-z]+|[0-9]+")


def camel_tokens(name: str) -> list[str]:
    """Split a camelCase / PascalCase identifier into lowercase tokens."""
    tokens: list[str] = []
    for chunk in name.split("_"):
        tokens.extend(m.group(0).lower() for m in _TOKEN_RE.finditer(chunk))
    return [t for t in tokens if t]


def upper_snake(tokens: list[str]) -> str:
    """Join TOKENS as an UPPER_SNAKE identifier, gluing digit tokens onto the
    previous token so e.g. (float, 64) reads FLOAT64 rather than FLOAT_64, and
    rejoining the (u, int...) split the camel tokenizer makes of UInt8 and
    friends so they read UINT8 rather than U_INT8."""
    parts: list[str] = []
    for token in tokens:
        upper = token.upper()
        if token.isdigit() and parts:
            parts[-1] += token
        elif parts and parts[-1] == "U" and upper.startswith("INT"):
            parts[-1] += upper
        else:
            parts.append(upper)
    return "_".join(parts)


def sanitize_identifier(name: str) -> str:
    """Make NAME a legal Java identifier (keywords get a trailing underscore)."""
    if name in JAVA_KEYWORDS:
        return name + "_"
    if name and name[0].isdigit():
        return "_" + name
    return name


def strip_common_token_prefix(token_lists: list[list[str]]) -> list[list[str]]:
    """Drop the leading token run shared by every entry (used for enum entries),
    capped so each entry keeps at least one token and never starts with a digit."""
    if len(token_lists) < 2:
        return token_lists
    shortest = min(len(tokens) for tokens in token_lists)
    common = 0
    while common < shortest and len({tuple(t[common:common + 1]) for t in token_lists}) == 1:
        common += 1
    common = min(common, shortest - 1)
    while common > 0:
        if all(tokens[common:] and not tokens[common][0].isdigit() for tokens in token_lists):
            break
        common -= 1
    return [tokens[common:] for tokens in token_lists]


def strip_daq(name: str) -> str:
    """daqDevice -> Device; leaves names without the daq prefix untouched."""
    if name.startswith("daq") and len(name) > 3 and (name[3].isupper() or name[3].isdigit()):
        return name[3:]
    return name


def receiver_class_name(receiver: str) -> str:
    """Low-level class name for a C receiver: daqDevice -> DaqDevice."""
    stripped = strip_daq(receiver)
    return "Daq" + stripped[0].upper() + stripped[1:]


def enum_java_name(c_name: str) -> str:
    """daqSampleType -> SampleType."""
    return strip_daq(c_name)


# ---------------------------------------------------------------------------
# In/out parameter classification (ported from the cl-opendaq generator)
# ---------------------------------------------------------------------------

IN_OUT_PARAMETER_OVERRIDES = {
    "daqBlockReader_read": {"count": "in-out"},
    "daqBlockReader_readWithDomain": {"count": "in-out"},
    "daqConnectionInternal_dequeueUpTo": {"count": "in-out"},
    "daqFunction_call": {"result": "in-out"},
    "daqMultiReader_read": {"count": "in-out"},
    "daqMultiReader_readWithDomain": {"count": "in-out"},
    "daqMultiReader_skipSamples": {"count": "in-out"},
    "daqStreamReader_read": {"count": "in-out"},
    "daqStreamReader_readWithDomain": {"count": "in-out"},
    "daqStreamReader_skipSamples": {"count": "in-out"},
    "daqTailReader_read": {"count": "in-out"},
    "daqTailReader_readWithDomain": {"count": "in-out"},
}


# ---------------------------------------------------------------------------
# Type table
# ---------------------------------------------------------------------------

# C builtin -> (java.lang.foreign layout, Java carrier type)
BUILTIN_TYPES = {
    "char": ("JAVA_BYTE", "byte"),
    "int8_t": ("JAVA_BYTE", "byte"),
    "uint8_t": ("JAVA_BYTE", "byte"),
    "int16_t": ("JAVA_SHORT", "short"),
    "uint16_t": ("JAVA_SHORT", "short"),
    "int": ("JAVA_INT", "int"),
    "int32_t": ("JAVA_INT", "int"),
    "uint32_t": ("JAVA_INT", "int"),
    "int64_t": ("JAVA_LONG", "long"),
    "uint64_t": ("JAVA_LONG", "long"),
    "size_t": ("JAVA_LONG", "long"),
    "float": ("JAVA_FLOAT", "float"),
    "double": ("JAVA_DOUBLE", "double"),
}


@dataclass
class JType:
    """A C type resolved for Java: its FFI layout and Java carrier."""
    kind: str                 # "pointer" | "scalar" | "bool" | "enum" | "struct" | "void"
    layout: str               # e.g. "ADDRESS", "JAVA_LONG", "Ffi.INTF_ID"
    java: str                 # e.g. "MemorySegment", "long", "boolean", "SampleType"
    c_base: str = ""          # base C type name, e.g. "daqList"
    enum: "EnumInfo | None" = None


@dataclass
class EnumInfo:
    c_name: str
    java_name: str
    entries: list[tuple[str, int]]      # (JAVA_CONSTANT, value)
    docstring: str = ""


@dataclass
class Param:
    c_name: str               # original C argument name
    java_name: str            # sanitized Java parameter name
    mode: str                 # "in" | "out" | "in-out"
    jtype: JType              # for "in": the passed value; for out: the *slot pointee*
    pointer_depth: int
    base_type: str
    value_type: str | None = None      # daqList element / daqDict value annotation
    key_type: str | None = None        # daqDict key annotation
    default_value: str | None = None   # C default literal from DAQ_DEFAULT_VALUE(...)
    struct_pointee: bool = False       # out slot whose pointee is a struct (not auto-wrappable)


@dataclass
class Function:
    c_name: str
    receiver: str             # e.g. "daqDevice"
    stem: str                 # e.g. "getSignals"
    ret: JType                # resolved C return type ("errcode" flagged via is_errcode)
    is_errcode: bool
    params: list[Param]
    docstring: str

    @property
    def outputs(self) -> list[Param]:
        return [p for p in self.params if p.mode != "in"]

    @property
    def inputs(self) -> list[Param]:
        return [p for p in self.params if p.mode != "out"]

    @property
    def auto_wrap(self) -> bool:
        return not any(p.struct_pointee for p in self.outputs)

    @property
    def has_self(self) -> bool:
        return bool(self.params) and self.params[0].c_name == "self"


class Model:
    """The full resolved binding model."""

    def __init__(self, opendaq_repo: Path):
        # Normalize the parser's dataclasses into the plain-dict shape of its
        # JSONL output (None fields and zero pointer depths dropped).
        records = [json.loads(json.dumps(r, cls=parse_bindings.DataclassEncoder))
                   for r in parse_bindings.parse_repo(opendaq_repo)]
        self.typedefs: dict[str, dict] = {}
        self.interfaces: dict[str, str] = {}      # name -> parent
        self.interface_docs: dict[str, str] = {}
        self.enums: dict[str, EnumInfo] = {}      # c name -> info
        self.error_codes: list[tuple[int, str]] = []
        self.functions: list[Function] = []

        raw_functions = []
        seen_codes: dict[int, str] = {}
        for record in records:
            kind = record["kind"]
            if kind == "typedef":
                self.typedefs.setdefault(record["name"], record)
            elif kind == "interface":
                self.interfaces[record["name"]] = record["parent"]
                if record.get("docstring"):
                    self.interface_docs.setdefault(record["name"], record["docstring"])
            elif kind == "error_code":
                seen_codes.setdefault(record["code"], record["name"])
            elif kind == "function":
                raw_functions.append(record)

        self.error_codes = sorted(seen_codes.items())
        for c_name, record in self.typedefs.items():
            if record["category"] == "enum":
                self.enums[c_name] = self._build_enum(c_name, record)
        for record in sorted(raw_functions, key=lambda r: r["name"]):
            self.functions.append(self._build_function(record))

    # -- enums ---------------------------------------------------------------

    def _build_enum(self, c_name: str, record: dict) -> EnumInfo:
        entries = [(e["name"], e["value"]) for e in record.get("enum_entries", [])
                   if e["value"] is not None]
        token_lists = strip_common_token_prefix([camel_tokens(name) for name, _ in entries])
        java_entries = [(upper_snake(tokens), value)
                        for tokens, (_, value) in zip(token_lists, entries)]
        return EnumInfo(c_name=c_name, java_name=enum_java_name(c_name),
                        entries=java_entries, docstring=record.get("docstring", ""))

    # -- type resolution -----------------------------------------------------

    def _category(self, name: str) -> str | None:
        record = self.typedefs.get(name)
        return record["category"] if record else None

    def pointer_like(self, name: str) -> bool:
        """True when the bare C name is itself pointer-valued (opaque interface
        handles, callback pointers, daqBaseObject, and pointer aliases)."""
        record = self.typedefs.get(name)
        if record is None:
            return False
        category = record["category"]
        if category in {"opaque", "callback"}:
            return True
        if name == "daqBaseObject":
            return True
        if category == "alias":
            if record.get("pointer_depth", 0) > 0:
                return True
            base = record.get("base_type")
            return self.pointer_like(base) if base else False
        return False

    def resolve(self, name: str, depth: int) -> JType:
        """Resolve the C type NAME with DEPTH pointer levels into a JType."""
        if name == "void":
            if depth == 0:
                return JType("void", "", "void", c_base=name)
            return JType("pointer", "ADDRESS", "MemorySegment", c_base=name)
        record = self.typedefs.get(name)
        if record is None:
            if name not in BUILTIN_TYPES:
                raise ValueError(f"Unsupported C type: {name}")
            if depth > 0:
                return JType("pointer", "ADDRESS", "MemorySegment", c_base=name)
            layout, java = BUILTIN_TYPES[name]
            return JType("scalar", layout, java, c_base=name)
        category = record["category"]
        if self.pointer_like(name):
            # One pointer level is absorbed by the name itself (daqString means
            # struct daqString, used only behind a pointer): daqString* in C is
            # a plain handle, daqString** an out slot.
            return self.resolve_pointerlike(name, depth)
        if category == "enum":
            if depth > 0:
                return JType("pointer", "ADDRESS", "MemorySegment", c_base=name)
            return JType("enum", "JAVA_INT", self.enums[name].java_name,
                         c_base=name, enum=self.enums[name])
        if category == "struct":
            if depth > 0:
                return JType("pointer", "ADDRESS", "MemorySegment", c_base=name)
            return JType("struct", "Ffi.INTF_ID", "MemorySegment", c_base=name)
        if category == "alias":
            base = record.get("base_type")
            if name == "daqBool":
                if depth > 0:
                    return JType("pointer", "ADDRESS", "MemorySegment", c_base=name)
                return JType("bool", "JAVA_BYTE", "boolean", c_base=name)
            if base in BUILTIN_TYPES:
                if depth > 0:
                    return JType("pointer", "ADDRESS", "MemorySegment", c_base=name)
                layout, java = BUILTIN_TYPES[base]
                return JType("scalar", layout, java, c_base=name)
            if base:
                resolved = self.resolve(base, depth)
                resolved.c_base = name
                return resolved
        raise ValueError(f"Unsupported C type: {name} (category {category})")

    def resolve_pointerlike(self, name: str, depth: int) -> JType:
        if depth <= 1:
            return JType("pointer", "ADDRESS", "MemorySegment", c_base=name)
        return JType("pointer", "ADDRESS", "MemorySegment", c_base=name)

    def slot_pointee(self, param_base: str, depth: int) -> JType:
        """The JType stored in an out slot for a parameter of base type
        PARAM_BASE with DEPTH pointer levels (depth >= 1)."""
        if self.pointer_like(param_base):
            # daqList** -> the slot holds a daqList* handle.
            return JType("pointer", "ADDRESS", "MemorySegment", c_base=param_base)
        return self.resolve(param_base, depth - 1)

    # -- functions -------------------------------------------------------------

    def _effective_category(self, name: str) -> str | None:
        """A typedef's category with zero-depth aliases resolved through to
        their base (so an alias of an opaque handle classifies as opaque),
        mirroring how the reference generator inherits categories."""
        record = self.typedefs.get(name)
        if record is None:
            return None
        category = record["category"]
        if category == "alias" and record.get("pointer_depth", 0) == 0:
            base = record.get("base_type")
            base_category = self._effective_category(base) if base else None
            if base_category in {"opaque", "callback"}:
                return base_category
        return category

    def _parameter_mode(self, function_name: str, arg: dict) -> str:
        override = IN_OUT_PARAMETER_OVERRIDES.get(function_name, {}).get(arg["name"])
        if override:
            return override
        arg_type = arg["type"]
        base = arg_type["name"]
        depth = arg_type.get("pointer_depth", 0)
        if depth == 0:
            return "in"
        if depth == 1 and (self._effective_category(base) in {"opaque", "callback"}
                           or base == "daqBaseObject"):
            return "in"
        if base == "void":
            return "in"
        return "out"

    def _build_function(self, record: dict) -> Function:
        c_name = record["name"]
        receiver, _, stem = c_name.partition("_")
        ret_type = record["return_type"]
        ret_base = ret_type["name"]
        ret_depth = ret_type.get("pointer_depth", 0)
        is_errcode = (ret_base == "daqErrCode" and ret_depth == 0)
        ret = self.resolve(ret_base, ret_depth)

        params: list[Param] = []
        for arg in record.get("arguments", []):
            arg_type = arg["type"]
            base = arg_type["name"]
            depth = arg_type.get("pointer_depth", 0)
            mode = self._parameter_mode(c_name, arg)
            if mode == "in":
                jtype = self.resolve(base, depth)
                struct_pointee = False
            else:
                pointee = self.slot_pointee(base, depth)
                struct_pointee = pointee.kind == "struct"
                jtype = pointee
            params.append(Param(
                c_name=arg["name"],
                java_name=sanitize_identifier(arg["name"]),
                mode=mode,
                jtype=jtype,
                pointer_depth=depth,
                base_type=base,
                value_type=arg_type.get("value_type"),
                key_type=arg_type.get("key_type"),
                default_value=arg.get("default_value"),
                struct_pointee=struct_pointee,
            ))
        return Function(c_name=c_name, receiver=receiver, stem=stem, ret=ret,
                        is_errcode=is_errcode, params=params,
                        docstring=record.get("docstring", ""))

    def functions_by_receiver(self) -> dict[str, list[Function]]:
        grouped: dict[str, list[Function]] = {}
        for function in self.functions:
            grouped.setdefault(function.receiver, []).append(function)
        return grouped


# ---------------------------------------------------------------------------
# Javadoc helpers
# ---------------------------------------------------------------------------

def escape_javadoc(text: str) -> str:
    return (text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("*/", "*&#47;")
                .replace("@", "&#64;"))


def doc_brief(docstring: str) -> str:
    """First line of the doxygen text with the @brief marker removed."""
    text = docstring.strip()
    text = re.sub(r"^@brief\s+", "", text)
    return text.split("\n", 1)[0].strip()


def javadoc_lines(text: str, indent: str) -> list[str]:
    """Render TEXT as a Javadoc comment at INDENT."""
    if not text:
        return []
    lines = [f"{indent}/**"]
    for line in escape_javadoc(text).split("\n"):
        lines.append(f"{indent} * {line}".rstrip())
    lines.append(f"{indent} */")
    return lines
