#!/usr/bin/env python3
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from patch import ROOT, cached_bytes, java_tool  # noqa: E402 — explicit checkout-local import.

VERSION = "1.36.1"
SHA256 = "25b400f003089d23cc5320cdaf1a16cabee19b8aa3434d0ff021b3d9f42154b4"
filename = f"google-java-format-{VERSION}-all-deps.jar"
jar = ROOT / "files" / "tools" / filename
cached_bytes(
    jar,
    f"https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/{VERSION}/{filename}",
    "sha256",
    SHA256,
)
flags = ["--dry-run", "--set-exit-if-changed"] if sys.argv[1:] == ["--check"] else ["--replace"]
if sys.argv[1:] not in ([], ["--check"]):
    sys.exit("Usage: python3 scripts/format-java.py [--check]")
try:
    result = java_tool(
        [
            "java",
            "-Xmx128m",
            "-jar",
            str(jar),
            "--aosp",
            *flags,
            str(ROOT / "scripts" / "PatchYuushya.java"),
            str(ROOT / "tests" / "YuushyaPatchChecks.java"),
        ]
    )
    sys.stdout.buffer.write(result.stdout)
except subprocess.CalledProcessError as error:
    sys.stderr.buffer.write(error.stderr)
    sys.stdout.buffer.write(error.stdout)
    raise SystemExit(error.returncode) from error
