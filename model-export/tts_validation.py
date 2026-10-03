"""Fail-closed local TTS candidate gates (not a substitute for Android device tests)."""
from __future__ import annotations

import json
from pathlib import Path

DENIED_OPS = frozenset({"ConvInteger", "MatMulInteger", "DynamicQuantizeLinear"})
ORT_VERSION = "1.20.0"
ROLE_FILE = {
    "TTS_ACOUSTIC": "fastpitch-{L}.v2.onnx",
    "TTS_VOCODER": "hifigan-{L}.v2.onnx",
    "TTS_TOKENS": "fastpitch-{L}.v2.tokens.json",
}


def load_symbols(path: Path) -> list[str]:
    symbols = json.loads(path.read_text(encoding="utf-8"))
    if (not isinstance(symbols, list) or not symbols
            or not all(isinstance(s, str) for s in symbols)
            or not any(symbols)):
        raise ValueError(f"{path}: expected a nonempty string symbol table")
    return symbols


def probe_ids(symbols: list[str]) -> list[int]:
    # Stay within the actual vocabulary, excluding conventional special symbols.
    ids = [i for i, s in enumerate(symbols) if s and not s.startswith("<")]
    if not ids:
        raise ValueError("symbol table has no usable probe tokens")
    return ids


def _check_nodes(nodes, location: str, onnx) -> None:
    for node in nodes:
        where = f"{location}/{node.name or node.op_type}"
        if node.op_type in DENIED_OPS:
            raise ValueError(f"denied operator {node.op_type} at {where}")
        _check_attributes(node.attribute, where, onnx)


def _check_attributes(attributes, location: str, onnx) -> None:
    for attr in attributes:
        if attr.type == onnx.AttributeProto.GRAPH:
            _check_nodes(attr.g.node, location, onnx)
        elif attr.type == onnx.AttributeProto.GRAPHS:
            for graph in attr.graphs:
                _check_nodes(graph.node, location, onnx)


def _check_tensor(value, dtype, shape: tuple, label: str) -> None:
    tensor = value.type.tensor_type
    dims = tensor.shape.dim
    if tensor.elem_type != dtype or len(dims) != len(shape):
        raise ValueError(f"{label}: wrong tensor type or rank")
    for axis, (dim, expected) in enumerate(zip(dims, shape)):
        if expected is None:
            if dim.HasField("dim_value") or not dim.dim_param:
                raise ValueError(f"{label}: axis {axis} must be symbolic/dynamic")
        elif not dim.HasField("dim_value") or dim.dim_value != expected:
            raise ValueError(f"{label}: axis {axis} must be {expected}")


def validate_graph(path: Path, kind: str, *, standalone: bool = True) -> None:
    import onnx

    if kind not in {"fastpitch", "hifigan"}:
        raise ValueError(f"unknown TTS model kind: {kind}")
    model = onnx.load(str(path), load_external_data=False)
    _check_nodes(model.graph.node, "graph", onnx)
    for function in model.functions:
        _check_nodes(function.node, f"function/{function.name}", onnx)
        _check_attributes(function.attribute_proto, f"function/{function.name}/defaults", onnx)
    for training in model.training_info:
        _check_nodes(training.initialization.node, "training/initialization", onnx)
        _check_nodes(training.algorithm.node, "training/algorithm", onnx)
    # ORT 1.20 supports ONNX IR <= 10 and the standard ONNX opset <= 21.
    if model.ir_version > 10:
        raise ValueError(f"IR {model.ir_version} exceeds ORT {ORT_VERSION} support (10)")
    for owner in (model, *model.functions):
        for opset in owner.opset_import:
            if opset.domain in {"", "ai.onnx"} and opset.version > 21:
                raise ValueError(f"opset {opset.version} exceeds ORT {ORT_VERSION} support (21)")

    def external(message):
        # Includes nested graphs, sparse initializers and tensor-valued attributes.
        if message.DESCRIPTOR.full_name == "onnx.TensorProto" and (
                message.external_data or message.data_location == onnx.TensorProto.EXTERNAL):
            return True
        return any(external(child) for field, value in message.ListFields()
                   if field.message_type is not None
                   for child in (value if field.label == field.LABEL_REPEATED else [value]))

    if standalone and external(model):
        raise ValueError("candidate needs external tensor data; app artifacts must be standalone")
    onnx.checker.check_model(str(path))
    graph = model.graph
    if len(graph.input) != 1 or len(graph.output) != 1:
        raise ValueError("expected exactly one input and one output")
    if kind == "fastpitch":
        _check_tensor(graph.input[0], onnx.TensorProto.INT64, (1, None), "tokens")
        _check_tensor(graph.output[0], onnx.TensorProto.FLOAT, (1, 80, None), "mel output")
    else:
        _check_tensor(graph.input[0], onnx.TensorProto.FLOAT, (1, 80, None), "mel input")
        rank = len(graph.output[0].type.tensor_type.shape.dim)
        shape = (1, None) if rank == 2 else (1, 1, None)
        _check_tensor(graph.output[0], onnx.TensorProto.FLOAT, shape, "audio")


def runtime():
    import onnxruntime as ort

    if ort.__version__ != ORT_VERSION:
        raise ValueError(f"require onnxruntime=={ORT_VERSION}; found {ort.__version__}")
    return ort


def create_session(path: Path):
    return runtime().InferenceSession(str(path), providers=["CPUExecutionProvider"])


def validate_mel(mel) -> None:
    import numpy as np

    if (mel.ndim != 3 or mel.shape[:2] != (1, 80) or mel.shape[2] < 1
            or mel.dtype != np.float32 or not np.isfinite(mel).all()):
        raise ValueError("mel must be finite nonempty float32 [1,80,F]")


def validate_audio(audio) -> None:
    import numpy as np

    if (audio.ndim not in (2, 3) or audio.shape[0] != 1
            or (audio.ndim == 3 and audio.shape[1] != 1)
            or audio.shape[-1] < 1 or audio.dtype != np.float32
            or not np.isfinite(audio).all()):
        raise ValueError("audio must be finite nonempty float32 [1,S] or [1,1,S]")


def validate_dynamic_session(session, kind: str, ids: list[int] | None = None) -> list[tuple[int, int]]:
    """Actually execute off-trace lengths; symbolic annotations alone are insufficient."""
    import numpy as np

    if kind not in {"fastpitch", "hifigan"}:
        raise ValueError(f"unknown TTS model kind: {kind}")
    if kind == "fastpitch" and not ids:
        raise ValueError("FastPitch dynamic probes need valid token IDs")
    results = []
    for length in ((8, 24, 41) if kind == "fastpitch" else (17, 40, 63)):
        if kind == "fastpitch":
            data = np.array([[ids[i % len(ids)] for i in range(length)]], dtype=np.int64)
        else:
            data = np.zeros((1, 80, length), dtype=np.float32)
        output = session.run(None, {session.get_inputs()[0].name: data})[0]
        (validate_mel if kind == "fastpitch" else validate_audio)(output)
        results.append((length, output.shape[-1]))
    if len({size for _, size in results}) != len(results):
        raise ValueError(f"{kind}: output length did not change across dynamic probes: {results}")
    return results


def validate_artifact(path: Path, kind: str, ids: list[int] | None = None,
                      *, standalone: bool = True) -> list[tuple[int, int]]:
    validate_graph(path, kind, standalone=standalone)
    return validate_dynamic_session(create_session(path), kind, ids)


def validate_pair(fastpitch: Path, hifigan: Path, symbols: list[str]):
    """Graph gates, independent dynamic probes, and a finite end-to-end probe."""
    import numpy as np

    for path, kind in ((fastpitch, "fastpitch"), (hifigan, "hifigan")):
        validate_graph(path, kind)
    fp, hg = create_session(fastpitch), create_session(hifigan)
    ids = probe_ids(symbols)
    probes = {"fastpitch": validate_dynamic_session(fp, "fastpitch", ids),
              "hifigan": validate_dynamic_session(hg, "hifigan")}
    tokens = np.array([[ids[i % len(ids)] for i in range(24)]], dtype=np.int64)
    mel = fp.run(None, {fp.get_inputs()[0].name: tokens})[0]
    validate_mel(mel)
    audio = hg.run(None, {hg.get_inputs()[0].name: mel})[0]
    validate_audio(audio)
    return probes
