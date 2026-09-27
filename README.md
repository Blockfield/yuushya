# Blockfield — yuushya

Private patch recipe and pinned release artifacts. Imported from `netherg-io/blockfield-modpack` at `19cdb35`.

Upstream: https://cdn.modrinth.com/data/lfVtagkm/versions/dVVX3bpL/yuushya-1.21.0-fabric-2.3.0.jar

The modpack consumes release assets by URL and hash; JARs are not stored in Git. Existing third-party rights are unchanged.

Build: create `files/mods`, then run `python3 scripts/patch.py` (ImageMagick 7 is needed for icon patches).

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
