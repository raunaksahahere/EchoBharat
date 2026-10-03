"""Real ONNX protobuf/checker tests; explicitly skipped when ONNX is unavailable.

These synthetic graphs test the gates, not any trained speech model or Android runtime.
"""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import tts_validation as validation

HAS_ONNX = importlib.util.find_spec("onnx") is not None
HAS_RUNTIME = importlib.util.find_spec("onnxruntime") is not None
HAS_CONVERTER = importlib.util.find_spec("onnxconverter_common") is not None


@unittest.skipUnless(HAS_ONNX, "ONNX not installed; real graph tests require onnx")
class RealGraphTest(unittest.TestCase):
    def setUp(self):
        import onnx
        self.onnx = onnx
        self.h = onnx.helper
        self.t = onnx.TensorProto
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "model.onnx"

    def model(self, kind="hifigan"):
        if kind == "hifigan":
            inputs = [self.h.make_tensor_value_info("mel", self.t.FLOAT, [1, 80, "frames"])]
            outputs = [self.h.make_tensor_value_info("audio", self.t.FLOAT, [1, 1, "samples"])]
            nodes = [self.h.make_node("ReduceMean", ["mel"], ["audio"], axes=[1], keepdims=1)]
            initializers = []
        else:
            inputs = [self.h.make_tensor_value_info("text", self.t.INT64, [1, "tokens"])]
            outputs = [self.h.make_tensor_value_info("mel", self.t.FLOAT, [1, 80, "frames"])]
            nodes = [self.h.make_node("Cast", ["text"], ["float_text"], to=self.t.FLOAT),
                     self.h.make_node("Unsqueeze", ["float_text", "axes"], ["expanded"]),
                     self.h.make_node("Tile", ["expanded", "repeats"], ["mel"])]
            initializers = [self.h.make_tensor("axes", self.t.INT64, [1], [1]),
                            self.h.make_tensor("repeats", self.t.INT64, [3], [1, 80, 1])]
        graph = self.h.make_graph(nodes, "synthetic", inputs, outputs, initializer=initializers)
        return self.h.make_model(graph, opset_imports=[self.h.make_opsetid("", 17)], ir_version=9)

    def check(self, model, kind="hifigan"):
        self.onnx.save(model, str(self.path))
        validation.validate_graph(self.path, kind)

    def test_valid_dynamic_boundaries(self):
        for kind in ("fastpitch", "hifigan"):
            self.check(self.model(kind), kind)

    def test_fixed_token_frame_and_output_axes_rejected(self):
        for kind, boundary, axis in (("fastpitch", "input", 1), ("fastpitch", "output", 2),
                                     ("hifigan", "input", 2), ("hifigan", "output", 2)):
            model = self.model(kind)
            getattr(model.graph, boundary)[0].type.tensor_type.shape.dim[axis].dim_value = 24
            with self.assertRaisesRegex(ValueError, "symbolic/dynamic"):
                self.check(model, kind)

    def test_float16_boundary_rejected(self):
        model = self.model()
        model.graph.input[0].type.tensor_type.elem_type = self.t.FLOAT16
        with self.assertRaisesRegex(ValueError, "tensor type"):
            self.check(model)

    def test_all_denied_operators_at_top_level(self):
        for op in validation.DENIED_OPS:
            model = self.model()
            model.graph.node.append(self.h.make_node(op, [], [], name="forbidden"))
            with self.assertRaisesRegex(ValueError, op):
                self.check(model)

    def test_denied_operators_inside_if_loop_and_graph_list(self):
        for outer in ("If", "Loop", "CustomWithGraphs"):
            for op in validation.DENIED_OPS:
                model = self.model()
                body = self.h.make_graph([self.h.make_node(op, [], [])], "body", [], [])
                attr = {"branches": [body]} if outer == "CustomWithGraphs" else {"body": body}
                model.graph.node.append(self.h.make_node(outer, [], [], **attr))
                with self.assertRaisesRegex(ValueError, op):
                    self.check(model)

    def test_local_function_denylist(self):
        model = self.model()
        function = self.h.make_function("local", "Forbidden", [], [],
                                        [self.h.make_node("MatMulInteger", [], [])],
                                        [self.h.make_opsetid("", 17)])
        model.functions.append(function)
        with self.assertRaisesRegex(ValueError, "MatMulInteger"):
            self.check(model)

    def test_function_default_graph_denylist(self):
        model = self.model()
        function = self.h.make_function("local", "DefaultGraph", [], [], [],
                                        [self.h.make_opsetid("", 17)])
        graph = self.h.make_graph([self.h.make_node("DynamicQuantizeLinear", [], [])],
                                 "default", [], [])
        function.attribute_proto.append(self.h.make_attribute("body", graph))
        model.functions.append(function)
        with self.assertRaisesRegex(ValueError, "DynamicQuantizeLinear"):
            self.check(model)

    def test_external_weights_rejected(self):
        model = self.model()
        tensor = self.h.make_tensor("unused_weight", self.t.FLOAT, [1], [0.0])
        tensor.ClearField("float_data")
        tensor.data_location = self.t.EXTERNAL
        item = tensor.external_data.add()
        item.key, item.value = "location", "missing.data"
        model.graph.initializer.append(tensor)
        with self.assertRaisesRegex(ValueError, "external tensor data"):
            self.check(model)

    def test_unsupported_ir_and_opset_rejected(self):
        model = self.model()
        model.ir_version = 11
        with self.assertRaisesRegex(ValueError, "IR"):
            self.check(model)
        model = self.model()
        model.opset_import[0].version = 22
        with self.assertRaisesRegex(ValueError, "opset"):
            self.check(model)

    @unittest.skipUnless(HAS_RUNTIME, "onnxruntime missing; dynamic execution requires ORT 1.20.0")
    def test_actual_dynamic_execution(self):
        import onnxruntime
        if onnxruntime.__version__ != validation.ORT_VERSION:
            self.skipTest("dynamic tests require exact onnxruntime==1.20.0")
        for kind in ("fastpitch", "hifigan"):
            self.onnx.save(self.model(kind), str(self.path))
            result = validation.validate_artifact(self.path, kind, [1, 2, 3])
            self.assertEqual(3, len(result))
            self.assertTrue(all(input_size == output_size for input_size, output_size in result))

    @unittest.skipUnless(HAS_CONVERTER, "onnxconverter_common missing; float16 conversion test skipped")
    def test_actual_keep_io_types_float16_conversion(self):
        from onnxconverter_common import float16
        for kind in ("fastpitch", "hifigan"):
            candidate = float16.convert_float_to_float16(self.model(kind), keep_io_types=True)
            self.check(candidate, kind)


if __name__ == "__main__":
    unittest.main()
