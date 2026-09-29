set script-interpreter := ["python", "-I", "-X", "utf8"]
set positional-arguments

[script]
default:
    import subprocess
    subprocess.run(["just", "--list"], check=True)

check: (_run "check")
lint: (_run "lint")
format-check: (_run "format-check")
format: (_run "format")
test: (_run "test")
build: (_run "build")
verify: (_run "verify")

[script]
_run command:
    import subprocess, sys
    subprocess.run([sys.executable, "-I", "-X", "utf8", "scripts/quality.py", *sys.argv[1:]], check=True)
