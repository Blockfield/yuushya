import importlib.util
import io
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "recipe", Path(__file__).resolve().parents[1] / "scripts" / "patch.py"
)
recipe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recipe)


class ChecksumChecks(unittest.TestCase):
    def test_wrong_jdk_is_rejected_before_compilation(self):
        version = subprocess.CompletedProcess(
            ["java", "-version"], 0, b"", b'openjdk version "22.0.1"\n'
        )
        with (
            patch.dict(recipe.os.environ, {"JAVA_HOME": ""}),
            patch.object(recipe.shutil, "which", return_value="/native/java"),
            patch.object(recipe.subprocess, "run", return_value=version) as run,
        ):
            with self.assertRaisesRegex(SystemExit, "JDK 21 required"):
                recipe.java_tool(["java", "-jar", "candidate.jar"])
            self.assertEqual(run.call_count, 1)

    def test_upstream_checksum_failure_preserves_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            upstream = root / "files" / "mods" / recipe.JARS[0]["upstream_name"]
            upstream.parent.mkdir(parents=True)
            upstream.write_bytes(b"bad upstream")
            output = root / recipe.JARS[0]["out"]
            output.write_bytes(b"existing output")
            with patch.object(recipe, "ROOT", root), patch.object(recipe, "get_bytes") as fetch:
                with self.assertRaisesRegex(SystemExit, "Checksum mismatch"):
                    recipe.main()
                fetch.assert_not_called()
            self.assertEqual(output.read_bytes(), b"existing output")

    def test_asm_cached_checksum_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cached = root / "files" / "tools" / f"asm-{recipe.ASM_VERSION}.jar"
            cached.parent.mkdir(parents=True)
            cached.write_bytes(b"bad ASM")
            with patch.object(recipe, "ROOT", root), patch.object(recipe, "get_bytes") as fetch:
                with self.assertRaisesRegex(SystemExit, "Checksum mismatch"):
                    recipe.compile_transformer()
                fetch.assert_not_called()

    def test_bad_download_is_not_cached(self):
        with tempfile.TemporaryDirectory() as directory:
            cached = Path(directory) / "upstream.jar"
            with patch.object(recipe, "get_bytes", return_value=b"bad download"):
                with self.assertRaisesRegex(SystemExit, "Checksum mismatch"):
                    recipe.cached_bytes(
                        cached,
                        "https://example.invalid/upstream.jar",
                        "sha512",
                        recipe.JARS[0]["sha512"],
                    )
            self.assertFalse(cached.exists())

    def test_zlib_guard_runs_before_download_or_write(self):
        with (
            patch.object(recipe.zlib, "ZLIB_RUNTIME_VERSION", "different"),
            patch.object(recipe, "cached_bytes") as cache,
        ):
            with self.assertRaisesRegex(SystemExit, "expected zlib 1.3.2/1.3.2"):
                recipe.main()
            cache.assert_not_called()


class BytecodeChecks(unittest.TestCase):
    def test_pinned_class_and_fail_closed_fixtures(self):
        upstream = recipe.cached_bytes(
            recipe.ROOT / "files" / "mods" / recipe.JARS[0]["upstream_name"],
            recipe.JARS[0]["url"],
            "sha512",
            recipe.JARS[0]["sha512"],
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with zipfile.ZipFile(io.BytesIO(upstream)) as jar:
                original = jar.read(recipe.JARS[0]["patch_class"])
            old = bytes.fromhex("b20063ba006d0000b900600200")
            self.assertEqual(original.count(old), 1)
            (root / "original.class").write_bytes(original)
            (root / "legacy.class").write_bytes(original.replace(old, bytes(13)))
            classpath = recipe.compile_transformer()
            recipe.java_tool(
                [
                    "javac",
                    "-J-Xmx128m",
                    "--release",
                    "21",
                    "-g:none",
                    "-Xlint:all",
                    "-Werror",
                    "-cp",
                    classpath,
                    "-d",
                    str(recipe.ROOT / "files" / "tools" / "classes"),
                    str(recipe.ROOT / "tests" / "YuushyaPatchChecks.java"),
                ]
            )
            result = recipe.java_tool(
                [
                    "java",
                    "-Xmx128m",
                    "-Xverify:all",
                    "-cp",
                    classpath,
                    "YuushyaPatchChecks",
                    str(root / "original.class"),
                    str(root / "legacy.class"),
                    str(root / "variation.class"),
                    str(root / "variation-patched.class"),
                ]
            )
            print(result.stdout.decode(), end="")


if __name__ == "__main__":
    unittest.main()
