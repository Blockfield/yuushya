# Yuushya PLAYER_JOIN patch

Issue: [Blockfield/yuushya#3](https://github.com/Blockfield/yuushya/issues/3).
Pinned source: `yuushya-1.21.0-fabric-2.3.0.jar`, Modrinth version `dVVX3bpL`.
Its SHA512 remains
`d4336ad28055e2bc62b4fe110da1c085347ea7b40f77ede4009256417a67ff59ec13ecf8af81abf052a67d52a8ae60f4627d9732e4474f72abbde400c65779f3`.

## Exact target

`javap -v -p` on the pinned class locates this sequence in
`com/yuushya/Yuushya.init:()V` at original offsets 45, 48 and 53:

```text
GETSTATIC dev/architectury/event/events/common/PlayerEvent.PLAYER_JOIN
  Ldev/architectury/event/Event;
INVOKEDYNAMIC join:()Ldev/architectury/event/events/common/PlayerEvent$PlayerJoin;
INVOKEINTERFACE dev/architectury/event/Event.register:(Ljava/lang/Object;)V
```

The invokedynamic bootstrap is `java/lang/invoke/LambdaMetafactory.metafactory`
with descriptor
`(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;`.
Its three arguments are the callback type `(Lnet/minecraft/class_3222;)V`, an
`H_INVOKESTATIC` handle to
`com/yuushya/Yuushya.lambda$init$4:(Lnet/minecraft/class_3222;)V`, and the same
callback type. The helper must be private, static and synthetic.

The `SERVER_STARTED` registration at offsets 32–40 remains. The patch keeps the
notice helper, every other method, all resources and the original constant pool.
ASM reserializes labels, line-number offsets and instruction lengths after the
removal. It does not recompute frames using Minecraft classes.

## Local verification, 2026-09-30

Before edits, the recipe at source commit
`80fb15fa68b85be5cca60608b761ed093b36fb08` reproduced the published bf1 exactly.
Two compressor baselines were saved before replacing the raw byte patch:

| Artifact | SHA256 |
| --- | --- |
| Published bf1 / old zlib-ng build | `0dd92b2484dd7559084ae2808e93908d5db246c386366721307a6b8bd4f1ad5b` |
| Old recipe / canonical zlib 1.3.2 | `b87b4768510da4649d04711ca0a843b1cf906fececbd1281334356873a7f2adb` |
| ASM bf2 candidate / canonical zlib 1.3.2 | `de7bfa541fc549edbd6926ee9b36cc27b41a7add33095ef34b380b9b6a1f4d7b` |

The two full candidate builds have identical SHA256. Both candidate and
canonical baseline have 54,700 entries in the same order. All 54,699 entries
other than `com/yuushya/Yuushya.class` have identical decompressed payload,
compression size, CRC and ZIP entry metadata. The class shrinks from 5,125 to
5,112 bytes: the legacy recipe inserted 13 NOP bytes; ASM removes those three
instructions entirely. Compressed class size changes from 2,247 to 2,243 bytes,
and following ZIP offsets change accordingly. ASM traces of every method match
the legacy output after ignoring its inserted NOPs and debug offsets.

The historical and canonical **old** bf1 contain identical decompressed bytes
for every entry; 30,540 compressed entry sizes differ because zlib-ng and zlib
1.3.2 produce different ZIP streams. This is separate from the class change.
Published artifact hashes and game distribution pins were not updated.

`just test` covers:

- Pinned-upstream success and deterministic class output.
- Missing, duplicate, unexpected field/call/lambda/method registrations and
  repeated application, all rejected.
- Constant-pool reordering that defeats the old hex signature, while preserving
  an extra unrelated NOP.
- ASM structural/data-flow validation and JVM definition/resolution with
  `-Xverify:all`; the class is not initialized by this standalone check.
- Corrupted upstream, corrupted ASM cache and bad downloads; no damaged
  downloads are cached and an existing output survives checksum failure.
- The zlib guard before any network or output operation.

Builds, baseline JARs, disassembly, fixtures and JSON results are retained under
`~/.local/state/blockfield/audit/2026-09-30/issues/yuushya/` on the audit machine.

## Game loading verification, 2026-09-30

The coordinator installed the exact candidate SHA256 `de7bfa541fc549edbd6926ee9b36cc27b41a7add33095ef34b380b9b6a1f4d7b`
on both its dedicated server and client in the isolated
`blockfield-workspace/temp/issues-2026-09-30/` stand, with the real Fabric/mod
dependencies. Both logs report `Yuushya has been loaded successfully!` and have
no `VerifyError`, `LinkageError` or `NoClassDefFoundError`.

Client `IssueAudit` connected to `127.0.0.1:25599`. Its captured messages contain
only the test-profile notification, with no upstream join notice. RCON
`give IssueAudit yuushya:asphalt 1` and
`setblock -278 -39 607 yuushya:asphalt` succeeded. Testbot reported
`Block{yuushya:asphalt}` at that position, and the saved screenshot shows the
placed block with its texture. This confirms runtime item/block registration
and client rendering in addition to standalone bytecode verification.

Evidence is retained under the audit directory's `game/`:
`result.json`, `server-latest.log`, `play-latest.log` and
`yuushya-bf2-loaded.png`. The authorized isolated stand/container was stopped
and its ports were closed after checking. Published artifacts, distribution
pins and production were unchanged. This completes local game-loading
acceptance; it does not constitute a release or deployment.

Any future release uses a new asset and tag, never an overwritten bf1 asset.
For a paired release, prepare release notes and both candidate pins, get the
owner's tag/deploy authorization, release/deploy the server first, then the
compatible client. There is no published bf2 release from this recipe change.
