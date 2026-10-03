# Yuushya — патч для Fabric 1.21.1

Patch for Yuushya 2.3.0 on Fabric 1.21.1. Repairs resource references, removes the join notice registration, and makes window-door shapes follow the existing collision resources.

The recipe was extracted from `netherg-io/blockfield-modpack` at `19cdb35`. Release assets are used by the Blockfield client and server.

Upstream: https://cdn.modrinth.com/data/lfVtagkm/versions/dVVX3bpL/yuushya-1.21.0-fabric-2.3.0.jar

The modpack consumes release assets by URL and hash; JARs are not stored in Git. Existing third-party rights are unchanged.

## Build and checks

Needs native Python 3.12 or later, Node.js 22, Git, Just 1.57.0 and JDK 21 (`JAVA_HOME` or
`PATH`). The project selects Python 3.12.14 with standard zlib 1.3.2 and caches
the checksum-pinned standalone interpreter when the host version differs.
Ruff 0.15.7, actionlint 1.7.12 and Prettier 3.8.3 are pinned in `scripts/toolchain.py`; fetched
tools stay inside `.cache/quality/`.

```sh
just check
just build
just verify
```

`just format` applies Ruff and Google Java Format 1.36.1 (AOSP style).
The same check commands run in GitHub Actions. Compiler checks use
`javac --release 21 -Xlint:all -Werror`; each Java process is limited to 128 MB.
Tasks use isolated UTF-8 Python and resolve child Python through that same
executable. Child tools remove inherited Python/JVM wrapper options while
retaining the project JDK. These commands work without shell-specific syntax;
CI separately checks Linux and Windows, including the canonical build checksum.

The first build downloads the exact upstream into `files/mods/` and verifies its
SHA512 before use. Every cached artifact is checked again on every build. ASM
9.8 (`asm`, `asm-tree`, `asm-analysis`, `asm-util`) and the Java formatter are
downloaded from Maven Central into `files/tools/`, with literal SHA256 pins in
the scripts. A checksum mismatch fails before the candidate JAR is replaced;
delete a damaged cache file explicitly to download it again. These JARs are
build tools and are not included in the output mod.

The recipe produces **a local bf3 candidate**, not a release, and leaves the
published `SHA256SUMS` unchanged. `BUILD-SHA256SUMS` checks the candidate produced
with Python 3.12.14 and zlib compile/runtime **1.3.2/1.3.2**. The recipe rejects a
different compressor before downloading or writing anything. Earlier bf1 was
built with zlib-ng: decompressing its entries gives the same old recipe result,
but ZIP compression differs. Its published hash remains valid for that published
artifact and must not be replaced with a rebuild hash.

## Join notice patch

The join patch transforms `com/yuushya/Yuushya.class`. `scripts/PatchYuushya.java`
identifies the exact `init()V` registration structurally, including the event
owner, descriptors, lambda bootstrap and `lambda$init$4` callback. It removes
three instructions rather than matching constant-pool indices in raw bytes.
Zero matches, multiple matches, an unexpected registration shape and repeated
application fail. The notice helper remains present; `SERVER_STARTED` and all
other methods remain unchanged. ASM checks the output's structure and data flow.

The pinned-upstream regression checks also run JVM verification with
`-Xverify:all`, compare all methods with the legacy patch, and vary the constant
pool while adding an unrelated instruction. The previous bf2 candidate also passed
dedicated-server/client loading and join/resource checks on the coordinator's
isolated stand on 2026-09-30. The protocol is in [docs/ASM-PATCH.md](docs/ASM-PATCH.md).

Resource repairs remain in Python: remove the obsolete encyclopedia recipe,
repair sign/butterfly/grass/concrete texture references and remove empty faces
from four invisible hitbox models. No ImageMagick is needed for this recipe.

If a future upstream provides a supported switch for the join notice, remove
the transformer instead of extending it. Updating upstream requires reviewing
the registration, replacing its checksum deliberately, rerunning negative and
resource-parity checks, and producing a new candidate version.

## Window door shapes

The bf3 candidate additionally transforms
`com/yuushya/block/YuushyaBlockFactory$3.class`, the vanilla-door factory used by
the six window doors and their six sliding variants. Upstream packages their
state-specific collision JSON but this subclass inherits vanilla swinging-door
shapes instead of consulting the existing Yuushya shape map.

The added outline-shape method reads `getYuushyaVoxelShapes()` by the current
state's string key. A missing mapping first invokes the existing lazy collision
loader with the state and registry block ID, then repeats the lookup and delegates
to the original `DoorBlock` method if no mapping exists. This is the same path as
upstream `BlockWithClassType`: `readAllCollision()` skips eager loading whenever
any unrelated shape has already populated the global map. `dynamicShape()` is
applied to settings immediately before the base
constructor, so Minecraft does not permanently cache the fallback collision
before the datapack collision loader runs. Inherited collision handling then
uses the same shape as the outline. The upstream loader, JSON, tooltip, block
IDs, door placement and opening methods are unchanged.

The ASM transformer rejects changed class/constructor layouts, an existing
shape override or dynamic settings, and repeated application. Regression
fixtures execute map lookup, vanilla fallback, lazy loading with a partially
populated global map and mappings added/removed after construction; they also
verify that dynamic settings reach the base constructor
and that other methods remain unchanged. Full client/server door acceptance is
a separate game check; the earlier bf2 loading check does not cover bf3 shapes.
The exact descriptors are documented in [docs/ASM-PATCH.md](docs/ASM-PATCH.md).

Yuushya is shared between client and server. Publishing bf3 requires a new
release asset/tag, release notes, verified loading on both sides, updated pins
on both sides and a paired contract release with the server first. This recipe
does not update live pins or publish anything.

## Описание релиза

Файл `release-notes/<тег>.md` хранится в этом репозитории. Его содержимое используется
как тело GitHub Release и как вложение `release-notes.md`.
[Формат и подготовка](https://github.com/Blockfield/blockfield-client/blob/main/docs/RELEASE-NOTES.md)
описаны в клиентском репозитории; используйте его `scripts/release-notes-tool.py`
из проверенного клона для команд `skeleton` и `check` с параметрами
`--repository Blockfield/yuushya`, `--version <тег>` и `--kind mod`.
Укажите `backfilled: false`, а в `previous_version` — последнюю опубликованную
версию этой линии.

Публикуйте новый артефакт с контрольными суммами и описанием; старые релизы
не перезаписывайте. После выпуска обновите соответствующие пины сборки.

Условия использования: [LICENSE](LICENSE).
