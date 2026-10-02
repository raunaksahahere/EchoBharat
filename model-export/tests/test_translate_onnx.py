"""Checks CLI text-processing wiring without downloading models or importing native ML libs."""
import contextlib
import importlib.util
import io
from pathlib import Path
import sys
import unittest
from unittest.mock import MagicMock, patch


class TranslateOnnxTest(unittest.TestCase):
    def load_script(self, processor):
        golden = MagicMock()
        golden.FLORES = {
            "en": "eng_Latn", "hi": "hin_Deva", "bn": "ben_Beng", "gu": "guj_Gujr",
            "kn": "kan_Knda", "ml": "mal_Mlym", "mr": "mar_Deva", "or": "ory_Orya",
            "ta": "tam_Taml", "te": "tel_Telu",
        }
        golden.load_processor.return_value = processor
        spec = importlib.util.spec_from_file_location(
            "translate_onnx_under_test", Path(__file__).resolve().parents[1] / "translate_onnx.py"
        )
        module = importlib.util.module_from_spec(spec)
        with patch.dict(sys.modules, {
            "numpy": MagicMock(), "onnxruntime": MagicMock(), "sentencepiece": MagicMock(),
            "make_mt_golden": golden,
        }):
            spec.loader.exec_module(module)
        return module

    def test_cli_preprocesses_before_encoding_and_restores_placeholders(self):
        processor = MagicMock()
        processor.preprocess_batch.return_value = ["eng_Latn hin_Deva Call < ID1 > now"]
        processor.postprocess_batch.return_value = ["98765-43210 पर कॉल करें"]
        script = self.load_script(processor)
        sp_src, sp_tgt, enc, dec = (MagicMock() for _ in range(4))
        output = io.StringIO()
        with patch.object(script, "load_dir", return_value=({}, {}, sp_src, sp_tgt, enc, dec)), \
                patch.object(script, "encode_source", return_value=[4, 5, 2]) as encode, \
                patch.object(script, "greedy_decode", return_value=("< ID1 > पर कॉल करें", 0.0, 0.0, 6)), \
                patch.object(sys, "argv", ["translate_onnx.py", "--dir", ".", "--src", "en", "--tgt", "hi",
                                          "--text", "Call 98765-43210 now"]), \
                contextlib.redirect_stdout(output):
            self.assertEqual(0, script.main())
        encode.assert_called_once_with("Call < ID1 > now", "en", "hi", sp_src, {})
        processor.preprocess_batch.assert_called_once_with(
            ["Call 98765-43210 now"], src_lang="eng_Latn", tgt_lang="hin_Deva"
        )
        processor.postprocess_batch.assert_called_once_with(["< ID1 > पर कॉल करें"], lang="hin_Deva")
        self.assertIn("98765-43210 पर कॉल करें", output.getvalue())

    def test_cli_recognizes_all_ten_app_languages(self):
        script = self.load_script(MagicMock())
        self.assertEqual({"en", "hi", "bn", "gu", "kn", "ml", "mr", "or", "ta", "te"}, set(script.LANG_TAG))


if __name__ == "__main__":
    unittest.main()
