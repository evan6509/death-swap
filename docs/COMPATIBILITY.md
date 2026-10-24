# Minecraft compatibility

Death Swap builds separate Java 25 Fabric mod JARs for Minecraft 26.2 and 26.3.
The default remains 26.2. `DEATHSWAP_MINECRAFT=26.3` selects 26.3;
other target versions fail configuration. A JAR declares one exact Minecraft
version, rather than claiming that the same binary supports both.

| Minecraft | Fabric API build dependency | Embedded API minimum | Output |
| --- | --- | --- | --- |
| 26.2 | `0.154.2+26.2` | `>=0.154.2` | `build/libs/death-swap-VERSION+mc26.2.jar` |
| 26.3 | `0.165.0+26.3` | `>=0.165.0` | `build/26.3/libs/death-swap-VERSION+mc26.3.jar` |

Both declare Fabric Loader `>=0.19.3` and Java `>=25`. Keep the server,
client Minecraft version, mod artifact, and Fabric API target aligned.
See the [README build commands](../README.md#building-and-checking).
Use separate project caches and sequential builds; the shared proxy project
has one output directory. Build 26.2 first because its `clean` removes `build/`,
including any earlier 26.3 outputs. The 26.3 build isolates compiled classes,
resources, JARs, GameTest worlds, and reports under `build/26.3/`.

## Dependency and API inspection (2026-10-24)

The official [Minecraft manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
lists 26.3 as a release. Its [version metadata](https://piston-meta.mojang.com/v1/packages/61fff1316bd50312624a858bbb5eb18fa45208ed/26.3.json)
specifies Java 25 and server SHA-1
`33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c`; the server used by Loom matches.

Fabric's [Maven metadata](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml)
lists stable `0.165.0+26.3`. Its [POM](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.165.0+26.3/fabric-api-0.165.0+26.3.pom)
and downloaded JAR agree on that version. The JAR's `fabric.mod.json` declares
Minecraft `~26.3-`, Loader `>=0.19.3`, and Java `>=25`. The command, entity-event,
lifecycle, and networking modules' embedded constraints were also inspected.
Downloaded API JAR SHA-256:
`18442e2ad466d6ef41c37dbb4cb68495e6ca6658b1f1ee9be76143d155ef2c88`.

The actual mapped 26.3 classes were inspected with `javap`, including
`ServerPlayer.teleportTo(ServerLevel, double, double, double, Set<Relative>, float, float, boolean)`,
`ServerPlayer.level()`, `setGameMode(GameType)`, `Entity.stopRiding()`,
`resetFallDistance()`, `setDeltaMovement(Vec3)`, `PlayerList.respawn(...)`,
`CommonListenerCookie.createInitial(...)`, and GameTest mock-player methods.
The existing shared source compiles against 26.3 without adapters. Server
GameTests exercise those calls and Fabric event registration; a dependency
version change alone is not the compatibility evidence.

## Release and deployment scope

CI builds and verifies both targets sequentially, then stages four deliverables:
two mod JARs, the shared Velocity plugin, and the original
`death-swap-docker-VERSION+mc26.2.zip`. Release checks require both mod JARs,
verify their exact metadata, include both in checksums, and verify downloaded
bytes before publication. Release regression tests simulate GitHub operations.

Docker, FabricProxy-Lite, and the deployment instructions remain on 26.2.
No 26.3 Docker bundle or deployment compatibility is claimed. The published
v1.0.0 release is unchanged and supports only 26.2.

## Local validation (2026-10-24)

With exported `JAVA_HOME` pointing to JDK 25.0.5+8, both targets passed
`./gradlew --no-daemon --max-workers=1 --project-cache-dir .gradle/TARGET clean build runGametest`,
using `DEATHSWAP_MINECRAFT=26.3` for 26.3. The local run built 26.3 first,
preserved its completed output outside `build/` during the 26.2 clean build,
then restored it for combined packaging. CI uses the documented 26.2-first order.

| Evidence | 26.2 | 26.3 |
| --- | --- | --- |
| Clean Gradle build | Passed | Passed |
| Standalone rule/recovery checks | 3,547 passed | 3,547 passed |
| Automated server GameTests | 29 passed, zero failures/errors | 29 passed, zero failures/errors |
| Death Swap tests within that total | 28 (plus one Minecraft baseline) | 28 (plus one Minecraft baseline) |
| Proxy JUnit tests | 16 passed | 16 passed |
| Production JAR verification | Passed | Passed |
| GameTest EULA file | `eula=false` | `eula=false` |

`python3 -m unittest discover -s scripts -p 'test_*.py' -v` passed all 41 tests
under each target's `DEATHSWAP_MINECRAFT` selection.
New target/packaging tests first failed against the original implementation;
release-publication tests also failed with four deliverables before the notes
writer was updated. These checks now pass, including corrupt/missing 26.3 asset
rejection on simulated publication retries.

`python3 scripts/verify_proxy_jar.py`, `python3 docker/test_runtime.py`
(29 disposable-fixture tests), and `sh -n docker/entrypoint.sh` passed.
Local `package_release.py` and `verify_release_assets.py build/release` passed
for all four deliverables. No release, tag, or PR was created. No live server
or world was touched. No connected-client session, container deployment, or
successful GitHub Actions run is claimed by this local evidence.
