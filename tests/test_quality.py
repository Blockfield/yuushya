"""Canonical output checks must reject drift without changing release metadata."""

import hashlib
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import patch as recipe
import quality


class ChecksumTests(unittest.TestCase):
    def test_wrong_candidate_is_rejected_without_rewriting_checksums(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            candidate = root / "candidate jar.jar"
            candidate.write_bytes(b"changed candidate")
            baseline = hashlib.sha256(b"reviewed candidate").hexdigest()
            expected = root / "BUILD-SHA256SUMS"
            expected.write_text(f"{baseline}  candidate.jar.jar\n", encoding="utf-8")
            released = root / "SHA256SUMS"
            released.write_text("immutable published checksum\n", encoding="utf-8")
            with (
                patch.object(quality, "ROOT", root),
                patch.object(recipe, "JARS", [{"out": candidate.name}]),
            ):
                with self.assertRaisesRegex(SystemExit, "Canonical checksum mismatch"):
                    quality.verify()
            self.assertEqual(
                expected.read_text(encoding="utf-8"), f"{baseline}  candidate.jar.jar\n"
            )
            self.assertEqual(released.read_text(encoding="utf-8"), "immutable published checksum\n")
