#!/usr/bin/env python3
"""Пересобрать files/mods/*-bf1.jar из апстримных jar-ов с Modrinth.

Yuushya: 2.3.0 удалила предмет yuushya:the_encyclopedia (остались текстура и локализация),
но оставила рецепт painter_gift_box.json со ссылкой на него -- рецепт не загружается и
шумит в ERROR на старте. Каменная резка (recipe/block/painter_gift_box.json) остаётся,
подарочная коробка доступна в игре: 1 алмаз -> 4 коробки.

Yuushya-ассеты (правки replace/fix_empty_faces): в 2.3.0 потерялись текстуры, на которые
указывают живые модели (poster/poster_large для sign_14 -- тот же рисунок лежит в
randomized_poster/5.png; character_black_blur для бабочек никогда не существовала -- частицы
переведены на #main), и остались ссылки вида "block/dark_concrete" на чужой namespace
ванильных текстур, которых в ваниле нет (у мода те же файлы лежат в yuushya:block/).
Четыре *_hitbox.json -- невидимые модели коллизий с пустыми гранями {}; в 1.21.1 загрузчик
моделей требует у каждой грани строку texture, поэтому пустые грани вырезаются.

EliteX: TACZ-загрузчик исполняет все .lua из scripts/ ганпака, включая *_backup*.lua без
объявления M -- на каждый файл WARN при каждой перезагрузке ресурсов. Удаление файла на
сервере ненадёжно: обновление ганпака вернёт его, поэтому исходники вычищаются при сборке.
Benelli ссылается на отсутствующий super_nova_fire_3p; используем существующий звук выстрела.
Версия 4.2.1, а не 5.1: 5.1 переделала звуки и анимации AK47/M4 SOPMOD/AKX105/RO635. Отличия версий --
docs/elitex-4.2-vs-5.1.md в корне рабочей папки. Id elitex:ak47x из 4.2 отдаётся под именем elitex:ak47
(так его знают классы, тир и статистика), mp7r в 4.2 нет.
gun_weights: у большинства стволов EliteX вес не задан (0 кг) или случайный (M1911 0.2 кг, Vector 1 кг),
а вес TACZ замедляет ходьбу и ускоряет расход стамины на бег. Веса выставлены по шкале
стандартного ганпака TACZ (пистолет ~1.1, SMG ~2.5, винтовка ~3.5, ручной пулемёт 8).

Cozy Home: в inset_flat_11/13 (подмодели воды в раковинах) и в item/falling_liquid
битые ссылки -- texture воды переехала в block/liquid/, а block/falling_liquid не
существует (нужен stream_water_falling). Проявлялось как magenta-вода в mud_sink
на картах и "Unable to load model" в клиентском логе.

Yuushya: при каждом входе сервер шлёт в чат длинное "[Notice]" про перепродажу мода --
конфигом не отключается. В Yuushya.init регистрация PLAYER_JOIN (getstatic + invokedynamic +
invokeinterface register, 13 байт) заменяется на nop'ы.

add: новые записи, дописываемые в jar (формат "entry: текст файла").
patch_bytes: байтовые замены в бинарных записях (class), каждая обязана сработать ровно раз.

prune_sounds: sounds.json, из которых выкидываются ссылки на файлы, удалённые через remove (событие, у которого
не осталось звуков, остаётся с пустым списком: так клиент не пишет "Missing sound for event").

Vanilla Backport: в jar 35 МБ ванильной музыки новых версий и 5 МБ пластинок, добавленных в ванильные
music.game/music.menu/музыку биомов. У нас играет свой саундтрек (docs/MUSIC.md глушит ванильный
MusicManager), пластинки не выдаются (лут выключен в конфиге) -- это только вес клиентского пака.

replace: точечные замены текста внутри записей jar (формат "entry: старое -> новое" --
список кортежей). fix_empty_faces: список записей, где из элементов моделей вырезаются
грани без texture. Ровность соответствий проверяется: если замена ни разу не сработала,
сборка падает.

Для обработки иконок нужен ImageMagick 7 (magick).
Запуск из корня модпака: python3 scripts/patch-cosmetic-jars.py
"""
import fnmatch
import io
import json
import re
import sys
import subprocess
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

JARS = [
{
        "upstream_name": "yuushya-1.21.0-fabric-2.3.0.jar",
        "url": "https://cdn.modrinth.com/data/lfVtagkm/versions/dVVX3bpL/yuushya-1.21.0-fabric-2.3.0.jar",
        "sha512": "d4336ad28055e2bc62b4fe110da1c085347ea7b40f77ede4009256417a67ff59ec13ecf8af81abf052a67d52a8ae60f4627d9732e4474f72abbde400c65779f3",
        "out": "files/mods/yuushya-1.21.0-fabric-2.3.0-bf1.jar",
        "remove": ["data/yuushya/recipe/painter_gift_box.json"],
        "patch_bytes": [
            ("com/yuushya/Yuushya.class", bytes.fromhex("b20063ba006d0000b900600200"), bytes(13)),
        ],
        "replace": [
            ("assets/yuushya/models/extra_building_material/sign/sign_14.json",
             "yuushya:0extra_building_material/preview/poster/poster_large",
             "yuushya:0extra_building_material/preview/randomized_poster/5"),
            ("assets/yuushya/models/extra_building_material/sign/sign_14.json",
             '"particle": "yuushya:0extra_building_material/preview/randomized_poster/5"',
             '"particle": "#0"'),
            ("assets/yuushya/models/extra_building_material/butterfly_0.json",
             "yuushya:0extra_building_material/preview/character_black_blur", "#main"),
            ("assets/yuushya/models/item/butterfly.json",
             "yuushya:0extra_building_material/preview/character_black_blur", "#main"),
        ],
        "replace_all": [
            ("assets/yuushya/models/", '"block/grass"', '"yuushya:block/grass/grass"'),
            ("assets/yuushya/models/", '"block/dark_concrete"', '"yuushya:block/dark_concrete"'),
            ("assets/yuushya/models/", '"block/black_concrete_"', '"yuushya:block/black_concrete_"'),
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
    import hashlib
    return hashlib.sha512(data).hexdigest()


def prune_sounds(text, gone):
    """sounds.json без ссылок на удалённые файлы: (json, сколько ссылок выкинуто)."""
    data = json.loads(text)
    pruned = 0
    for event in data.values():
        keep = []
        for sound in event.get("sounds", []):
            entry = sound if isinstance(sound, dict) else {"name": sound}
            namespace, _, path = entry["name"].rpartition(":")
            if entry.get("type", "file") == "file" and f"assets/{namespace or 'minecraft'}/sounds/{path}.ogg" in gone:
                pruned += 1
            else:
                keep.append(sound)
        event["sounds"] = keep
    return json.dumps(data, ensure_ascii=False, indent=2), pruned


def apply_replacements(name, text, spec):
    hits = 0
    for old, new in spec:
        count = text.count(old)
        if count == 0:
            sys.exit(f"{name}: замена не нашла {old!r} -- паттерн устарел?")
        text = text.replace(old, new)
        hits += count
    return text, hits


def set_gun_weight(name, text, weight):
    """Вес TACZ (кг) верхнего уровня: заменить существующий или вставить после ammo_amount.
    Файлы ганпака не строгий JSON (комментарии), поэтому правка текстовая; вложенные "weight"
    обвесов (отступ глубже двух пробелов) не трогаются."""
    # Ганпак в CRLF: сохраняем исходный перевод строки.
    text, count = re.subn(r'^  "weight": [0-9.]+,(\r?\n)', lambda m: f'  "weight": {weight},{m.group(1)}', text, flags=re.M)
    if count == 0:
        text, count = re.subn(r'^(  "ammo_amount": \d+,(\r?\n))',
                              lambda m: f'{m.group(1)}  "weight": {weight},{m.group(2)}', text, flags=re.M)
    if count != 1:
        sys.exit(f"{name}: вес не установлен (совпадений {count}) -- формат изменился?")
    return text


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


def brand_hud_icon(payload):
    size = subprocess.run(['magick', 'png:-', '-format', '%wx%h', 'info:'], input=payload,
                          capture_output=True, check=True, timeout=30).stdout.decode()
    return subprocess.run(['magick', 'png:-', '-alpha', 'extract', '-write', 'mpr:mask', '+delete',
                           '-size', size, 'gradient:#F5A524-#F3E7D0', 'mpr:mask', '-alpha', 'off',
                           '-compose', 'CopyOpacity', '-composite', '-strip', 'png:-'],
                          input=payload, capture_output=True, check=True, timeout=30).stdout


def slot_icon(index):
    # ImageGen's row spacing is not exact; these gutters were measured on the retained 1161x1355 atlas.
    rows = (0, 185, 370, 560, 737, 923, 1121, 1355)
    row, col = divmod(index, 6)
    left, right = round(col * 1161 / 6), round((col + 1) * 1161 / 6)
    crop = f'{right-left}x{rows[row+1]-rows[row]}+{left}+{rows[row]}'
    return subprocess.run(['magick', str(ROOT / 'assets/elitex-slot-atlas.png'), '-crop', crop, '+repage',
                           '-trim', '+repage', '-resize', '56x56', '-gravity', 'center', '-background', 'none',
                           '-extent', '64x64', '-strip', 'png:-'],
                          capture_output=True, check=True, timeout=30).stdout


def main():
    for spec in JARS:
        out_path = ROOT / spec["out"]
        local = ROOT / "files" / "mods" / spec["upstream_name"]
        if local.is_file():
            data = local.read_bytes()
        else:
            print(f"скачиваю {spec['upstream_name']}")
            data = get_bytes(spec["url"])
        actual = sha512(data)
        if actual != spec["sha512"]:
            sys.exit(f"Апстрим изменился: {spec['upstream_name']} sha512 {actual}")

        source = zipfile.ZipFile(io.BytesIO(data))
        for entry in spec.get("required_entries", []):
            if entry not in source.namelist() or not source.read(entry):
                sys.exit(f"{spec['upstream_name']}: отсутствует обязательный ресурс {entry}")
        slots = sorted(n for n in source.namelist() if spec.get("slot_icons_prefix")
                       and n.startswith(spec["slot_icons_prefix"]) and n.endswith(".png"))
        if slots and len(slots) != 38:
            sys.exit("EliteX slot list changed; update the 6x7 atlas mapping")
        replacements = dict.fromkeys(spec.get("replace", []), 0)
        replace_all = dict.fromkeys(spec.get("replace_all", []), 0)
        emptied = dict.fromkeys(spec.get("fix_empty_faces", []), 0)
        added = dict.fromkeys(spec.get("add", []), 0)
        removed = []
        weighted = set()
        gone = {n for n in source.namelist() if any(fnmatch.fnmatch(n, pattern) for pattern in spec.get("remove", []))}
        sounds_pruned = dict.fromkeys(spec.get("prune_sounds", []), 0)
        with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as target:
            for info in source.infolist():
                name = info.filename
                if any(fnmatch.fnmatch(name, pattern) for pattern in spec.get("remove", [])):
                    removed.append(name)
                    continue
                payload = source.read(name)
                if spec.get("brand_hud_icons") and name.startswith(spec["brand_hud_icons"]) and name.endswith(".png"):
                    payload = brand_hud_icon(payload)
                if name in slots:
                    payload = slot_icon(slots.index(name))
                for entry, old, new in spec.get("patch_bytes", []):
                    if entry == name:
                        if payload.count(old) != 1 or len(old) != len(new):
                            sys.exit(f"{name}: байтовый патч не подходит -- апстрим изменился?")
                        payload = payload.replace(old, new)
                        added[f"{name} (patch_bytes)"] = 1
                for entry, old, new in spec.get("replace", []):
                    if entry == name:
                        text, hits = apply_replacements(name, payload.decode("utf-8"), [(old, new)])
                        payload = text.encode("utf-8")
                        replacements[(entry, old, new)] = hits
                for prefix, old, new in spec.get("replace_all", []):
                    if name.startswith(prefix) and name.endswith(".json"):
                        text = payload.decode("utf-8")
                        count = text.count(old)
                        if count:
                            payload = text.replace(old, new).encode("utf-8")
                            replace_all[(prefix, old, new)] += count
                gun = name.removeprefix(spec.get("gun_weights_dir", "\0")).removesuffix("_data.json")
                if gun in spec.get("gun_weights", {}) and name.endswith("_data.json"):
                    payload = set_gun_weight(name, payload.decode("utf-8"), spec["gun_weights"][gun]).encode("utf-8")
                    weighted.add(gun)
                if name in sounds_pruned:
                    text, sounds_pruned[name] = prune_sounds(payload.decode("utf-8"), gone)
                    payload = text.encode("utf-8")
                if name in spec.get("fix_empty_faces", []):
                    text, faces = strip_empty_faces(payload.decode("utf-8"), name)
                    payload = text.encode("utf-8")
                    emptied[name] = faces
                target.writestr(info, payload)
            for entry, content in spec.get("add", {}).items():
                # Фиксированная дата записи: иначе sha512 jar'а меняется при каждом запуске
                # и расходится с хешем в mods/*.pw.toml.
                fixed = zipfile.ZipInfo(entry, date_time=(1980, 1, 1, 0, 0, 0))
                fixed.compress_type = zipfile.ZIP_DEFLATED
                target.writestr(fixed, content.encode("utf-8"))
                added[entry] = 1
        for entry, _, _ in spec.get("patch_bytes", []):
            if f"{entry} (patch_bytes)" not in added:
                sys.exit(f"{entry}: запись для байтового патча не найдена")
        missing = set(spec.get("gun_weights", {})) - weighted
        if missing:
            sys.exit(f"{spec['upstream_name']}: не найдены данные стволов для веса: {sorted(missing)}")
        if weighted:
            print(f"  ~ вес стволов: {len(weighted)}")
        if not removed and not replacements and not replace_all and not emptied and not added:
            sys.exit(f"Ничего не изменено в {spec['upstream_name']}: паттерны устарели?")
        for name in removed:
            print(f"  - {name}")
        for (entry, old, new), hits in replacements.items():
            if hits == 0:
                sys.exit(f"{entry}: запись для замены не найдена")
            print(f"  ~ {entry}: {old.split('/')[-1]} -> {new} (x{hits})")
        for (prefix, old, new), hits in replace_all.items():
            if hits == 0:
                sys.exit(f"{prefix}*: замена не нашла {old!r} -- паттерн устарел?")
            print(f"  ~ {prefix}* : {old} -> {new} (x{hits})")
        for entry, faces in emptied.items():
            if faces == 0:
                sys.exit(f"{entry}: фикс пустых граней не сработал -- устарел?")
            print(f"  ~ {entry}: вырезано пустых граней {faces}")
        for entry, pruned in sounds_pruned.items():
            if pruned == 0:
                sys.exit(f"{entry}: не выкинуто ни одной ссылки на удалённые звуки -- устарело?")
            print(f"  ~ {entry}: выкинуто ссылок на удалённые звуки {pruned}")
        for entry in added:
            print(f"  + {entry}")
        print(f"{out_path.relative_to(ROOT)} <- {spec['upstream_name']} (sha512 {sha512(out_path.read_bytes())})")


if __name__ == "__main__":
    main()
