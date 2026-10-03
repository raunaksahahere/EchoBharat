"""Offline unit tests. Native ML dependencies are mocked; no model claims follow."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import export_batch
import tts_validation as validation
import update_manifest as manifest_tool


def load_script(name, dependencies):
    spec = importlib.util.spec_from_file_location(name + "_test", ROOT / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    with patch.dict(sys.modules, dependencies):
        spec.loader.exec_module(module)
    return module


class VerifyTest(unittest.TestCase):
    def setUp(self):
        self.np = MagicMock()
        self.script = load_script("verify_tts", {"numpy": self.np})

    def test_longest_match_first_duplicate_wins(self):
        self.assertEqual([2, 0], self.script.tokenize("aba", ["a", "b", "ab", "ab"]))

    def test_unmapped_is_error_not_silently_skipped(self):
        with self.assertRaisesRegex(ValueError, "unmapped"):
            self.script.tokenize("a?", ["a"])

    def test_nonfinite_wav_does_not_create_or_replace_file(self):
        audio = SimpleNamespace(ndim=1, size=4)
        self.np.isfinite.return_value.all.return_value = False
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "sample.wav"
            path.write_bytes(b"previous listening sample")
            with self.assertRaisesRegex(ValueError, "finite"):
                self.script.write_wav(path, audio, 22050)
            self.assertEqual(b"previous listening sample", path.read_bytes())

    def test_benchmark_excludes_warmups_and_computes_rtf(self):
        audio = SimpleNamespace(size=44100)
        with patch.object(self.script, "synthesize", side_effect=[
                ("warmup", audio, 9.0), ("first", audio, 0.5), ("last", audio, 0.25)]) as synth:
            mel, retained, timings, rtfs = self.script.benchmark(None, None, None, 22050, 1, 2)
        self.assertEqual(3, synth.call_count)
        self.assertEqual("last", mel)
        self.assertIs(audio, retained)
        self.assertEqual([0.5, 0.25], timings)
        self.assertEqual([0.25, 0.125], rtfs)

    def test_bad_mel_never_reaches_vocoder(self):
        fp, hg = MagicMock(), MagicMock()
        with patch.object(self.script, "validate_mel", side_effect=ValueError("nonfinite mel")):
            with self.assertRaisesRegex(ValueError, "nonfinite"):
                self.script.synthesize(fp, hg, object())
        hg.run.assert_not_called()

    def test_candidate_cli_paths_and_nonfinite_rejection_before_wav(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = ["verify_tts", "--dir", tmp, "--lang", "hi", "--text", "a",
                    "--fastpitch", "candidate-fp.onnx", "--hifigan", "candidate-hg.onnx",
                    "--tokens", "symbols.json", "--runs", "2", "--warmup", "0"]
            with patch.object(sys, "argv", args), \
                    patch.object(self.script, "load_symbols", return_value=["a"]), \
                    patch.object(self.script, "validate_graph") as graph, \
                    patch.object(self.script, "create_session", return_value=MagicMock()), \
                    patch.object(self.script, "validate_dynamic_session"), \
                    patch.object(self.script, "synthesize", side_effect=ValueError("nonfinite audio")), \
                    patch.object(self.script, "write_wav") as wav, contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(ValueError, "nonfinite"):
                    self.script.main()
            wav.assert_not_called()
            self.assertEqual(Path(tmp) / "candidate-fp.onnx", graph.call_args_list[0].args[0])
            self.assertEqual(Path(tmp) / "candidate-hg.onnx", graph.call_args_list[1].args[0])


class ValidationTest(unittest.TestCase):
    def test_exact_runtime_version_required(self):
        with patch.dict(sys.modules, {"onnxruntime": SimpleNamespace(__version__="1.21.0")}):
            with self.assertRaisesRegex(ValueError, "1.20.0"):
                validation.runtime()

    def test_dynamic_probe_rejects_constant_output_length(self):
        session = MagicMock()
        session.run.return_value = [SimpleNamespace(shape=(1, 80, 24))]
        with patch.dict(sys.modules, {"numpy": MagicMock()}), patch.object(validation, "validate_mel"):
            with self.assertRaisesRegex(ValueError, "did not change"):
                validation.validate_dynamic_session(session, "fastpitch", [1, 2])
        self.assertEqual(3, session.run.call_count)

    def test_dynamic_probe_executes_distinct_off_trace_lengths(self):
        np = MagicMock()
        np.array.side_effect = lambda data, **kwargs: data
        session = MagicMock()
        session.run.side_effect = lambda _, feed: [SimpleNamespace(
            shape=(1, 80, len(next(iter(feed.values()))[0])))]
        with patch.dict(sys.modules, {"numpy": np}), patch.object(validation, "validate_mel"):
            self.assertEqual([(8, 8), (24, 24), (41, 41)],
                             validation.validate_dynamic_session(session, "fastpitch", [1, 2]))

    def test_nonfinite_tensor_boundaries_rejected(self):
        np = MagicMock()
        np.isfinite.return_value.all.return_value = False
        mel = SimpleNamespace(ndim=3, shape=(1, 80, 20), dtype=np.float32)
        audio = SimpleNamespace(ndim=3, shape=(1, 1, 200), dtype=np.float32)
        with patch.dict(sys.modules, {"numpy": np}):
            with self.assertRaisesRegex(ValueError, "finite"):
                validation.validate_mel(mel)
            with self.assertRaisesRegex(ValueError, "finite"):
                validation.validate_audio(audio)

    def test_recursive_graph_and_graphs_denylist(self):
        onnx = SimpleNamespace(AttributeProto=SimpleNamespace(GRAPH=5, GRAPHS=10))
        for op in validation.DENIED_OPS:
            bad = SimpleNamespace(name="nested", op_type=op, attribute=[])
            for attr in (SimpleNamespace(type=5, g=SimpleNamespace(node=[bad])),
                         SimpleNamespace(type=10, graphs=[SimpleNamespace(node=[bad])])):
                parent = SimpleNamespace(name="outer", op_type="If", attribute=[attr])
                with self.assertRaisesRegex(ValueError, op):
                    validation._check_nodes([parent], "graph", onnx)


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.path = self.root / "manifest.json"
        self.manifest = {"languages": []}
        for lang in ("hi", "en"):
            models = [{"role": role, "fileName": f"old-{lang}-{role}",
                       "sha256": "old-hash", "sizeBytes": 13, "url": "https://old.invalid/model",
                       "customField": {"preserve": True}} for role in validation.ROLE_FILE]
            models.append({"role": "STT_MODEL", "fileName": "unchanged.onnx"})
            self.manifest["languages"].append({"lang": lang, "models": models})
            (self.root / lang).mkdir()
            for role, template in validation.ROLE_FILE.items():
                (self.root / lang / template.format(L=lang)).write_bytes(
                    b'["a", "b"]' if role == "TTS_TOKENS" else b"mock local candidate")
        self.path.write_text(json.dumps(self.manifest))
        self.original = self.path.read_bytes()
        self.validate = patch.object(manifest_tool, "validate_pair").start()
        self.addCleanup(patch.stopall)

    def run_main(self, *extra):
        with patch.object(sys, "argv", ["update_manifest", "--manifest", str(self.path),
                                       "--out", str(self.root), "--langs", "hi", "en", *extra]), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return manifest_tool.main()

    def test_complete_migration_preserves_specs_and_other_models(self):
        original = copy.deepcopy(self.manifest)
        result = manifest_tool.prepare_manifest(self.manifest, self.root, ["hi", "en"])
        self.assertEqual(original, self.manifest)
        self.assertEqual(2, self.validate.call_count)
        for old, new in zip(original["languages"], result["languages"]):
            self.assertEqual(2, new["packVersion"])
            self.assertEqual(old["models"][:3], new["legacyModels"])
            self.assertEqual(old["models"][3], new["models"][3])
            for model in new["models"][:2]:
                self.assertTrue(model["url"].endswith(".v2.onnx"))
                artifact = self.root / new["lang"] / model["fileName"]
                self.assertEqual(manifest_tool.sha256_of(artifact), model["sha256"])
                self.assertEqual(artifact.stat().st_size, model["sizeBytes"])
        self.assertEqual(result, manifest_tool.prepare_manifest(result, self.root, ["hi", "en"]))

    def test_existing_legacy_specs_preserved(self):
        self.manifest["languages"][0]["legacyModels"] = [{"role": "OTHER", "fileName": "older"}]
        result = manifest_tool.prepare_manifest(self.manifest, self.root, ["hi"])
        self.assertEqual(self.manifest["languages"][0]["legacyModels"][0],
                         result["languages"][0]["legacyModels"][0])

    def test_missing_later_artifact_leaves_manifest_byte_identical(self):
        (self.root / "en" / "hifigan-en.v2.onnx").unlink()
        self.assertEqual(1, self.run_main())
        self.assertEqual(self.original, self.path.read_bytes())

    def test_invalid_later_graph_leaves_manifest_byte_identical(self):
        self.validate.side_effect = [None, ValueError("denied operator ConvInteger")]
        self.assertEqual(1, self.run_main())
        self.assertEqual(self.original, self.path.read_bytes())

    def test_invalid_tokens_leaves_manifest_byte_identical(self):
        (self.root / "en" / "fastpitch-en.v2.tokens.json").write_text('[42]')
        self.assertEqual(1, self.run_main())
        self.assertEqual(self.original, self.path.read_bytes())

    def test_remote_failure_precedes_write(self):
        with patch.object(manifest_tool, "verify_url", return_value=(False, "not uploaded")):
            self.assertEqual(1, self.run_main("--verify"))
        self.assertEqual(self.original, self.path.read_bytes())

    def test_default_flow_never_uses_network(self):
        with patch.object(manifest_tool, "verify_url") as remote:
            self.assertEqual(0, self.run_main())
        remote.assert_not_called()
        self.assertEqual(2, json.loads(self.path.read_text())["languages"][0]["packVersion"])

    def test_atomic_replace_failure_preserves_original(self):
        with patch.object(manifest_tool.os, "replace", side_effect=OSError("disk error")):
            self.assertEqual(1, self.run_main())
        self.assertEqual(self.original, self.path.read_bytes())
        self.assertEqual([], list(self.root.glob(".manifest.json.*")))

    def test_missing_role_unknown_language_and_odia_rejected(self):
        for langs in (["or"], ["hi", "unknown"], ["hi", "hi"]):
            with self.assertRaises(ValueError):
                manifest_tool.prepare_manifest(self.manifest, self.root, langs)
        self.manifest["languages"][0]["models"].pop(0)
        with self.assertRaisesRegex(ValueError, "exactly one"):
            manifest_tool.prepare_manifest(self.manifest, self.root, ["hi"])

    def test_versioned_filename_cannot_be_reused_for_new_bytes(self):
        migrated = manifest_tool.prepare_manifest(self.manifest, self.root, ["hi"])
        (self.root / "hi" / "hifigan-hi.v2.onnx").write_bytes(b"different candidate")
        with self.assertRaisesRegex(ValueError, "refusing to replace bytes"):
            manifest_tool.prepare_manifest(migrated, self.root, ["hi"])


class ExportTest(unittest.TestCase):
    def load_export(self):
        torch = MagicMock()
        torch.nn.Module = object
        return load_script("export_tts", {"torch": torch})

    def test_float16_conversion_keeps_io_and_inlines_weights(self):
        script = self.load_export()
        onnx, converter = MagicMock(), MagicMock()
        with tempfile.TemporaryDirectory() as tmp:
            src, dst = Path(tmp) / "fp32.onnx", Path(tmp) / "candidate.onnx"
            src.write_bytes(b"source")
            dst.write_bytes(b"candidate")
            with patch.dict(sys.modules, {"onnx": onnx, "onnxconverter_common": converter}):
                script.convert_float16(src, dst)
        converter.float16.convert_float_to_float16.assert_called_once_with(
            onnx.load.return_value, keep_io_types=True)
        onnx.external_data_helper.convert_model_from_external_data.assert_called_once()
        self.assertFalse(onnx.save_model.call_args.kwargs["save_as_external_data"])

    def test_failed_candidate_retains_fp32_and_does_not_promote(self):
        script = self.load_export()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for stage in ("fastpitch", "hifigan"):
                folder = root / "ckpt" / "hi" / stage
                folder.mkdir(parents=True)
                (folder / "best_model.pth").touch()
            tokens = MagicMock()
            tokens.main.side_effect = lambda: Path(sys.argv[2]).write_text('["a"]')
            def export(_checkpoint, output, *_):
                output.write_bytes(b"fp32 retained")
            def convert(source, output):
                output.write_bytes(b"bad candidate")
            with patch.object(sys, "argv", ["export_tts", "--lang", "hi", "--ckpt-root",
                                           str(root / "ckpt"), "--out", str(root / "out")]), \
                    patch.dict(sys.modules, {"make_tokens": tokens}), \
                    patch.object(script, "export_fastpitch", side_effect=export), \
                    patch.object(script, "export_hifigan", side_effect=export), \
                    patch.object(script, "convert_float16", side_effect=convert), \
                    patch.object(script, "validate_artifact"), \
                    patch.object(script, "validate_pair", side_effect=ValueError("denied operator")), \
                    contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(ValueError, "denied"):
                    script.main()
            out = root / "out" / "hi"
            self.assertEqual(2, len(list(out.glob("*.fp32.onnx"))))
            self.assertEqual([], list(out.glob("*.v2.*")))
            self.assertEqual([], list(out.glob(".candidate-*")))

    def test_local_batch_isolates_failure_and_never_downloads(self):
        with patch.object(sys, "argv", ["batch", "--langs", "hi", "en", "--ckpt-root", "local"]), \
                patch.object(export_batch, "run", side_effect=[1, 0, 0]) as run, \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, export_batch.main())
        self.assertEqual(3, run.call_count)
        self.assertTrue(all("export_tts.py" in str(c.args) or "verify_tts.py" in str(c.args)
                            for c in run.call_args_list))


if __name__ == "__main__":
    unittest.main()
