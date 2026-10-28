"""Native project checks and reproducible recipe builds."""

import argparse
import hashlib
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from toolchain import canonical_python, child_env, tool  # noqa: E402

COMMANDS = {
    "lint": [("ruff", "check", "."), ("actionlint",)],
    "format-check": [
        (
            "prettier",
            "--check",
            "--no-error-on-unmatched-pattern",
            "*.md",
            "docs/**/*.md",
            "release-notes/**/*.md",
            ".github/**/*.yml",
        ),
        ("just", "--unstable", "--fmt", "--check"),
        ("ruff", "format", "--check", "--exclude", "scripts/toolchain.py", "."),
        ("ruff", "format", "--check", "--config", "line-length=88", "scripts/toolchain.py"),
        ("python", "scripts/format-java.py", "--check"),
    ],
    "format": [
        (
            "prettier",
            "--write",
            "--no-error-on-unmatched-pattern",
            "*.md",
            "docs/**/*.md",
            "release-notes/**/*.md",
            ".github/**/*.yml",
        ),
        ("just", "--unstable", "--fmt"),
        ("ruff", "format", "--exclude", "scripts/toolchain.py", "."),
        ("ruff", "format", "--config", "line-length=88", "scripts/toolchain.py"),
        ("python", "scripts/format-java.py"),
    ],
    "test": [("python", "-m", "unittest", "discover", "-s", "tests", "-v")],
    "build": [("python", "scripts/patch.py")],
}


def run(command):
    if command[0] == "python":
        args = [str(canonical_python()), "-I", "-X", "utf8", *command[1:]]
    else:
        args = [str(tool(command[0])), *command[1:]]
    subprocess.run(args, cwd=ROOT, env=child_env(), check=True)


def eol():
    output = subprocess.check_output(
        ["git", "ls-files", "--eol"], cwd=ROOT, env=child_env(), text=True
    )
    bad = sorted(
        line.split("\t", 1)[1]
        for line in output.splitlines()
        if line.startswith(("i/crlf", "i/mixed"))
    )
    if bad:
        raise SystemExit(
            "CRLF line endings in the Git index:\n"
            + "\n".join(bad)
            + "\nFix with: git add --renormalize ."
        )


def verify():
    from patch import JARS

    expected = {
        line.split(maxsplit=1)[1].strip(): line.split(maxsplit=1)[0]
        for line in (ROOT / "BUILD-SHA256SUMS").read_text(encoding="utf-8").splitlines()
        if line.strip()
    }
    for spec in JARS:
        output = ROOT / spec["out"]
        with output.open("rb") as source:
            checksum = hashlib.file_digest(source, "sha256").hexdigest()
        if checksum != expected[output.name.replace(" ", ".")]:
            raise SystemExit(f"Canonical checksum mismatch: {output.name} {checksum}")
        print(f"Canonical checksum PASS: {output.name} {checksum}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command", choices=(*COMMANDS, "check", "verify"), nargs="?", default="check"
    )
    args = parser.parse_args()
    python = canonical_python()
    if (
        Path(sys.executable).resolve() != python.resolve()
        or not sys.flags.isolated
        or not sys.flags.utf8_mode
    ):
        subprocess.run(
            [str(python), "-I", "-X", "utf8", str(Path(__file__).resolve()), *sys.argv[1:]],
            cwd=ROOT,
            env=child_env(),
            check=True,
        )
        return
    if args.command == "verify":
        verify()
        return
    stages = ("lint", "format-check", "test") if args.command == "check" else (args.command,)
    for stage in stages:
        if stage in ("format", "format-check"):
            eol()
        for command in COMMANDS[stage]:
            run(command)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from error
