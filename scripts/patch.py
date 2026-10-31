#!/usr/bin/env python3
"""Build the bf3 candidate: Yuushya resource repairs and checked ASM join-notice/door-shape patches."""

import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from toolchain import child_env  # noqa: E402 — isolated Python omits the script directory.

ASM_VERSION = "9.8"
ASM_SHA256 = {
    "asm": "876eab6a83daecad5ca67eb9fcabb063c97b5aeb8cf1fca7a989ecde17522051",
    "asm-tree": "14b7880cb7c85eed101e2710432fc3ffb83275532a6a894dc4c4095d49ad59f1",
    "asm-analysis": "e640732fbcd3c6271925a504f125e38384688f4dfbbf92c8622dfcee0d09edb9",
    "asm-util": "8ba0460ecb28fd0e2980e5f3ef3433a513a457bc077f81a53bdc75b587a08d15",
}

JARS = [
    {
        "upstream_name": "yuushya-1.21.0-fabric-2.3.0.jar",
        "url": "https://cdn.modrinth.com/data/lfVtagkm/versions/dVVX3bpL/yuushya-1.21.0-fabric-2.3.0.jar",
        "sha512": "d4336ad28055e2bc62b4fe110da1c085347ea7b40f77ede4009256417a67ff59ec13ecf8af81abf052a67d52a8ae60f4627d9732e4474f72abbde400c65779f3",
        "out": "files/mods/yuushya-1.21.0-fabric-2.3.0-bf3.jar",
        "remove": ["data/yuushya/recipe/painter_gift_box.json"],
        "patch_class": "com/yuushya/Yuushya.class",
        "door_class": "com/yuushya/block/YuushyaBlockFactory$3.class",
        "entity_class": "com/yuushya/registries/YuushyaRegistries.class",
        "replace": [
            (
                "assets/yuushya/models/extra_building_material/sign/sign_14.json",
                "yuushya:0extra_building_material/preview/poster/poster_large",
                "yuushya:0extra_building_material/preview/randomized_poster/5",
            ),
            (
                "assets/yuushya/models/extra_building_material/sign/sign_14.json",
                '"particle": "yuushya:0extra_building_material/preview/randomized_poster/5"',
                '"particle": "#0"',
            ),
            (
                "assets/yuushya/models/extra_building_material/butterfly_0.json",
                "yuushya:0extra_building_material/preview/character_black_blur",
                "#main",
            ),
            (
                "assets/yuushya/models/item/butterfly.json",
                "yuushya:0extra_building_material/preview/character_black_blur",
                "#main",
            ),
        ],
        "replace_all": [
            ("assets/yuushya/models/", '"block/grass"', '"yuushya:block/grass/grass"'),
            ("assets/yuushya/models/", '"block/dark_concrete"', '"yuushya:block/dark_concrete"'),
            (
                "assets/yuushya/models/",
                '"block/black_concrete_"',
                '"yuushya:block/black_concrete_"',
            ),
        ],
        "fix_empty_faces": [
            "assets/yuushya/models/extra_building_material/easel_hitbox.json",
            "assets/yuushya/models/extra_building_material/easel_hitbox_rotated.json",
            "assets/yuushya/models/extra_building_material/roof/roof/tile1_lp_none_hitbox.json",
            "assets/yuushya/models/extra_building_material/tanghulu_bunch_hitbox.json",
        ],
    }
]


def get_bytes(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Blockfield-CI/1.0"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def sha512(data):
    return hashlib.sha512(data).hexdigest()


def cached_bytes(path, url, algorithm, expected):
    data = path.read_bytes() if path.is_file() else get_bytes(url)
    actual = hashlib.new(algorithm, data).hexdigest()
    if actual != expected:
        sys.exit(f"Checksum mismatch: {path.name} {algorithm} {actual}; expected {expected}")
    if not path.is_file():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    return data


def java_tool(command, **kwargs):
    environment = child_env()
    suffix = ".exe" if os.name == "nt" else ""
    home = environment.get("JAVA_HOME")
    candidate = Path(home) / "bin" / (command[0] + suffix) if home else None
    executable = (
        str(candidate)
        if candidate and candidate.is_file()
        else shutil.which(command[0], path=environment["PATH"])
    )
    if executable is None:
        sys.exit("Install JDK 21 and set JAVA_HOME or put its bin directory on PATH")
    version = subprocess.run(
        [executable, "-version"], env=environment, capture_output=True, check=True, timeout=30
    )
    match = re.search(
        rb'\b(?:java|javac|openjdk)(?: version)?\s+"?(\d+)', version.stdout + version.stderr
    )
    if match is None or match[1] != b"21":
        sys.exit(f"JDK 21 required: {executable}")
    return subprocess.run(
        [executable, *command[1:]],
        cwd=ROOT,
        env=environment,
        check=True,
        capture_output=True,
        timeout=120,
        **kwargs,
    )


def compile_transformer():
    tools = ROOT / "files" / "tools"
    jars = []
    for name, checksum in ASM_SHA256.items():
        filename = f"{name}-{ASM_VERSION}.jar"
        path = tools / filename
        cached_bytes(
            path,
            f"https://repo.maven.apache.org/maven2/org/ow2/asm/{name}/{ASM_VERSION}/{filename}",
            "sha256",
            checksum,
        )
        jars.append(str(path))
    classes = tools / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    classpath = os.pathsep.join(jars)
    java_tool(
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
            str(classes),
            str(ROOT / "scripts" / "PatchYuushya.java"),
        ]
    )
    return os.pathsep.join([str(classes), classpath])


def patch_class(payload, classpath):
    return java_tool(
        ["java", "-Xmx128m", "-Xverify:all", "-cp", classpath, "PatchYuushya"], input=payload
    ).stdout


def apply_replacements(name, text, spec):
    hits = 0
    for old, new in spec:
        count = text.count(old)
        if count == 0:
            sys.exit(f"{name}: замена не нашла {old!r} -- паттерн устарел?")
        text = text.replace(old, new)
        hits += count
    return text, hits


def strip_empty_faces(text, name):
    """Невидимые *_hitbox модели: в 1.21.1 у каждой грани обязана быть строка texture,
    а у элемента -- хотя бы одна грань. Вырезаем пустые грани, а элементы, оставшиеся
    без граней, целиком (модель не рисует ничего и раньше -- это только коллизия)."""
    model = json.loads(text)
    faces_removed = 0
    kept = []
    for element in model.get("elements", []):
        faces = element.get("faces")
        if not isinstance(faces, dict):
            kept.append(element)
            continue
        empty = [side for side, face in faces.items() if not face]
        for side in empty:
            del faces[side]
        faces_removed += len(empty)
        if faces:
            kept.append(element)
    model["elements"] = kept
    if faces_removed == 0:
        sys.exit(f"{name}: пустых граней не найдено -- фикс устарел?")
    return json.dumps(model, ensure_ascii=False, separators=(",", ":")), faces_removed


def main():
    if (zlib.ZLIB_VERSION, zlib.ZLIB_RUNTIME_VERSION) != ("1.3.2", "1.3.2"):
        sys.exit(
            f"Use just build: expected zlib 1.3.2/1.3.2, "
            f"got {zlib.ZLIB_VERSION}/{zlib.ZLIB_RUNTIME_VERSION}"
        )
    for spec in JARS:
        out_path = ROOT / spec["out"]
        local = ROOT / "files" / "mods" / spec["upstream_name"]
        data = cached_bytes(local, spec["url"], "sha512", spec["sha512"])
        with zipfile.ZipFile(io.BytesIO(data)) as source:
            entries = (spec["patch_class"], spec["door_class"], spec["entity_class"])
            for entry in entries:
                if source.namelist().count(entry) != 1:
                    sys.exit(f"{entry}: expected exactly one class entry")
            classpath = compile_transformer()
            patched = {entry: patch_class(source.read(entry), classpath) for entry in entries}
            replacements = dict.fromkeys(spec["replace"], 0)
            replace_all = dict.fromkeys(spec["replace_all"], 0)
            emptied = dict.fromkeys(spec["fix_empty_faces"], 0)
            removed = []
            out_path.parent.mkdir(parents=True, exist_ok=True)
            temporary = out_path.with_suffix(".jar.tmp")
            try:
                with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as target:
                    for info in source.infolist():
                        name = info.filename
                        if name in spec["remove"]:
                            removed.append(name)
                            continue
                        payload = patched[name] if name in patched else source.read(name)
                        for replace_entry, old, new in spec["replace"]:
                            if replace_entry == name:
                                text, hits = apply_replacements(
                                    name, payload.decode("utf-8"), [(old, new)]
                                )
                                payload = text.encode("utf-8")
                                replacements[(replace_entry, old, new)] = hits
                        for prefix, old, new in spec["replace_all"]:
                            if name.startswith(prefix) and name.endswith(".json"):
                                text = payload.decode("utf-8")
                                count = text.count(old)
                                if count:
                                    payload = text.replace(old, new).encode("utf-8")
                                    replace_all[(prefix, old, new)] += count
                        if name in spec["fix_empty_faces"]:
                            text, emptied[name] = strip_empty_faces(payload.decode("utf-8"), name)
                            payload = text.encode("utf-8")
                        target.writestr(info, payload)
                if set(removed) != set(spec["remove"]):
                    sys.exit("Resource removal target missing")
                if (
                    not all(replacements.values())
                    or not all(replace_all.values())
                    or not all(emptied.values())
                ):
                    sys.exit("Resource repair target missing")
                temporary.replace(out_path)
            finally:
                temporary.unlink(missing_ok=True)
        for name in removed:
            print(f"  - {name}")
        for (name, old, new), hits in replacements.items():
            print(f"  ~ {name}: {old} -> {new} (x{hits})")
        for (prefix, old, new), hits in replace_all.items():
            print(f"  ~ {prefix}* : {old} -> {new} (x{hits})")
        for name, faces in emptied.items():
            print(f"  ~ {name}: removed empty faces {faces}")
        print(f"  ~ {spec['patch_class']}: PLAYER_JOIN registration removed (ASM)")
        print(f"  ~ {spec['door_class']}: mapped door shapes and dynamic collision cache (ASM)")
        print(f"  ~ {spec['entity_class']}: ride_entity build() without id (ASM)")
        print(
            f"{out_path.relative_to(ROOT)} <- {spec['upstream_name']} (sha512 {sha512(out_path.read_bytes())})"
        )


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        sys.exit(error.stderr.decode("utf-8", errors="replace") or str(error))
