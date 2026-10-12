# Death Swap

A server-side Fabric minigame for **Minecraft 26.2** and **Java 25**. Build traps,
swap locations, and try to be the last player alive.

Install `death-swap-1.0.0+mc26.2.jar` and Fabric API `0.154.2+26.2` on a Fabric
server with Loader `0.19.3` or newer. Players can connect without installing
Death Swap on their clients. For a singleplayer/LAN world, install the mod and
Fabric API on the host. A match needs at least two players.

## Playing

Prepare a survival world and place players at their starting locations. The mod
uses their current locations and inventories; it does not reset the world,
spread players, refill health, or change gamerules or PvP settings.

1. Put participants in survival mode.
2. Optionally run `/deathswap interval 300` to choose the time between swaps.
3. Run `/deathswap start` to include every online, living survival player, or
   select participants with `/deathswap start @a[gamemode=survival]`. Selectors
   can narrow the match to particular players or scoreboard teams.
4. Build a trap before the next swap. A chat countdown announces the last ten
   seconds. The default interval is five minutes, measured in server ticks.
5. On each swap, everyone receives another surviving player's exact location
   and facing direction. With two players, their positions exchange. With more
   players, a shuffled cycle ensures everyone moves and each destination is
   used once. Swaps work across the Overworld, Nether, and End.

Death, disconnecting, or leaving survival mode eliminates a participant.
Eliminated players spectate until the match finishes; a dead player enters
spectator after respawning. The last remaining player wins. If every remaining
player dies in the same server tick, the match ends in a draw. Late joiners do
not enter an existing match, and eliminated players cannot rejoin it.

Players keep their own inventory, health, hunger, and effects when swapping.
Swaps dismount and wake players, reset movement and accumulated fall distance,
and preserve dangerous destinations. Teleport failure stops the match after
attempting to restore the original positions. If a player is transferring
dimensions, the swap waits for that transfer to finish.

The mod restores players it placed in spectator to survival when a match ends
or stops. Offline players recover on their next join. Recovery is recorded in
the world's `deathswap-spectators.txt`, so it also works after a restart. Matches
themselves do not resume after a restart.

## Commands

| Command | Purpose | Permission |
| --- | --- | --- |
| `/deathswap` or `/deathswap status` | Show survivors, time remaining, and swaps | Everyone |
| `/deathswap start [players]` | Start with all survival players or a selector | Operator level 2 |
| `/deathswap stop` | Cancel the match and restore spectators | Operator level 2 |
| `/deathswap swap` | Force a swap and reset the timer | Operator level 2 |
| `/deathswap interval <seconds>` | Set the next match's interval, 10–86400 seconds | Operator level 2 |

Stop the current match before changing its interval. The interval is held in
memory and resets to 300 seconds when the server restarts.

## Building and checking

Use a JDK 25 installation, as described in the [Fabric setup guide](https://docs.fabricmc.net/develop/getting-started/setting-up).
The repository includes the Gradle wrapper; a separate Gradle installation is
not required.

```sh
./gradlew --no-daemon build runGametest
python3 scripts/verify_mod_jar.py
```

The distributable is `build/libs/death-swap-1.0.0+mc26.2.jar`. Do not install the
sources JAR. The build runs standalone match-rule and spectator-recovery
checks. GameTests exercise real server players over mock network connections,
including location snapshots, dimensions, inventories, timers, elimination,
recovery, and command permissions. These checks do not establish human
multiplayer gameplay validation. The dedicated-server tests load the mod in
Fabric's server environment; the production JAR is also checked for accidental
client-class references and test code.

## Git workflow

`main` is the integration branch. `codex.development` is the long-lived working
branch, using the exact spelling with a dot. Routine development is committed
there and reviewed in a pull request into `main`. Merging and publication need
explicit approval. The included CI workflow builds and tests development
pushes and pull requests without publishing releases.

License: MIT.
