# Blockfield — yuushya

Private patch recipe and pinned release artifacts. Imported from `netherg-io/blockfield-modpack` at `19cdb35`.

Upstream: https://cdn.modrinth.com/data/lfVtagkm/versions/dVVX3bpL/yuushya-1.21.0-fabric-2.3.0.jar

The modpack consumes release assets by URL and hash; JARs are not stored in Git. Existing third-party rights are unchanged.

## Build and checks

Needs native Python 3.12 or later, Git, Just 1.57.0 and JDK 21 (`JAVA_HOME` or
`PATH`). The project selects Python 3.12.14 with standard zlib 1.3.2 and caches
the checksum-pinned standalone interpreter when the host version differs.
Ruff 0.15.7 and actionlint 1.7.12 are pinned in `scripts/toolchain.py`; fetched
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

The recipe produces **a local bf2 candidate**, not a release, and leaves the
published `SHA256SUMS` unchanged. `BUILD-SHA256SUMS` checks the candidate produced
with Python 3.12.14 and zlib compile/runtime **1.3.2/1.3.2**. The recipe rejects a
different compressor before downloading or writing anything. Earlier bf1 was
built with zlib-ng: decompressing its entries gives the same old recipe result,
but ZIP compression differs. Its published hash remains valid for that published
artifact and must not be replaced with a rebuild hash.

## Join notice patch

Only `com/yuushya/Yuushya.class` is transformed. `scripts/PatchYuushya.java`
identifies the exact `init()V` registration structurally, including the event
owner, descriptors, lambda bootstrap and `lambda$init$4` callback. It removes
three instructions rather than matching constant-pool indices in raw bytes.
Zero matches, multiple matches, an unexpected registration shape and repeated
application fail. The notice helper remains present; `SERVER_STARTED` and all
other methods remain unchanged. ASM checks the output's structure and data flow.

The pinned-upstream regression checks also run JVM verification with
`-Xverify:all`, compare all methods with the legacy patch, and vary the constant
pool while adding an unrelated instruction. The exact bf2 candidate also passed
dedicated-server/client loading and join/resource checks on the coordinator's
isolated stand on 2026-09-30. The protocol is in [docs/ASM-PATCH.md](docs/ASM-PATCH.md).

Resource repairs remain in Python: remove the obsolete encyclopedia recipe,
repair sign/butterfly/grass/concrete texture references and remove empty faces
from four invisible hitbox models. No ImageMagick is needed for this recipe.

If a future upstream provides a supported switch for the join notice, remove
the transformer instead of extending it. Updating upstream requires reviewing
the registration, replacing its checksum deliberately, rerunning negative and
resource-parity checks, and producing a new candidate version.

Yuushya is shared between client and server. Publishing bf2 requires a new
release asset/tag, release notes, verified loading on both sides, updated pins
on both sides and a paired contract release with the server first. This recipe
does not update live pins or publish anything.

## Описание релиза (release-notes.md)

Каждый новый релиз публикуется с описанием в едином формате Blockfield: тело релиза и asset `release-notes.md` — один и тот же файл `release-notes/<тег>.md`, закоммиченный в этот репозиторий. Инструмент проверки общий, из `Blockfield/blockfield-client`:

```sh
mkdir -p release-notes rn
gh api repos/Blockfield/blockfield-client/contents/scripts/release-notes-tool.py \
  -H 'Accept: application/vnd.github.raw' > rn/release-notes-tool.py
python3 rn/release-notes-tool.py skeleton --repository Blockfield/yuushya --version <тег> \
  --previous-version <прошлый тег> --kind mod --date YYYY-MM-DD > release-notes/<тег>.md
# заполните разделы по коммитам и изменениям рецепта; чего не знаете — отметьте в «Источниках»
python3 rn/release-notes-tool.py check release-notes/<тег>.md \
  --repository Blockfield/yuushya --version <тег> --kind mod
cp release-notes/<тег>.md rn/release-notes.md
gh release create <тег> <артефакты> SHA256SUMS rn/release-notes.md \
  --notes-file rn/release-notes.md --repo Blockfield/yuushya
```

Если `check` выводит ошибки, релиз не публикуйте. После выпуска обновите пин в клиенте/сервере; их релизные описания подтянут этот файл в раздел «Обновлённые компоненты».
