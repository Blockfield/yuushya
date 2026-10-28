"""format-check must fail while the Git index holds CRLF."""

import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import quality


class Eol(unittest.TestCase):
    def test_clean_index_passes(self):
        quality.eol()

    def test_index_crlf_reported(self):
        with patch.object(
            quality.subprocess,
            "check_output",
            return_value=(
                "i/crlf\tw/crlf\tattr/text=auto eol=lf\tconfig/x.toml\n"
                "i/lf\tw/lf\tattr/text=auto eol=lf\tok.py\n"
            ),
        ):
            with self.assertRaises(SystemExit) as ctx:
                quality.eol()
        self.assertIn("config/x.toml", str(ctx.exception))
        self.assertNotIn("ok.py", str(ctx.exception))

    def test_notext_crlf_passes(self):
        with patch.object(
            quality.subprocess,
            "check_output",
            return_value=(
                "i/crlf\tw/crlf\tattr/-text\tgradlew.bat\n"
                "i/lf\tw/lf\tattr/text=auto eol=lf\tok.py\n"
            ),
        ):
            quality.eol()

    def test_bat_without_notext_reported(self):
        with patch.object(
            quality.subprocess,
            "check_output",
            return_value="i/lf\tw/lf\tattr/text=auto eol=lf\tgradlew.bat\n",
        ):
            with self.assertRaises(SystemExit) as ctx:
                quality.eol()
        self.assertIn("gradlew.bat", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
