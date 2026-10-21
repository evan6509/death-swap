# Fresh-seed Death Swap round contract

The optional Docker deployment runs one Velocity proxy and two or more Fabric
backends. Only the proxy port is published. The proxy and backends communicate
through atomic JSON files on one shared volume, not client plugin messages.

## Paths and identity

The shared root is `/deathswap` by default. Backend `a` owns
`/deathswap/server-a`; the proxy owns `/deathswap/proxy`. Backend protocol files
live in `server-a/config/deathswap/`. Every protocol object has `schemaVersion: 1`.
Server IDs match `[a-z][a-z0-9-]*` and must be unique.

The supervisor maintains `server-a/world-generation.json` with `serverId`,
`generationId` (UUID), and `seed` (signed 64-bit integer). This identity survives
ordinary process restarts and changes only after an authorized world reset.
Backend `network.json` contains `enabled`, `serverId`, `generationId`,
`swapIntervalSeconds` (10–86400), and `startCountdownSeconds` (0–60).

## Backend heartbeat: status.json

Fields: `schemaVersion`, `serverId`, `generationId`, `bootId` (UUID for this
process), `updatedAt` (Unix milliseconds), `state`, `playerCount`,
`onlinePlayers` (UUID strings), `roundId` (nullable UUID), `participants`
(UUID strings), `endedRoundId` (nullable UUID), `commandId` (nullable UUID),
and `resetRequestId` (nullable UUID).

States:

- `ready`: untouched generation, no players; eligible as a fresh destination.
- `waiting`: initial lobby, waiting for at least two survival players and the
  configured start countdown.
- `prepared`: destination reserved for a round; hold arrivals in spectator
  until every expected online participant arrives.
- `running`: a Death Swap match is active.
- `ended`: first participant death accepted; swaps are frozen and everyone is
  held in spectator (dead players are respawned on a later server tick).
- `resetting`: terminal state; reject new logins, acknowledge a matching reset
  request only while all players have left.
- `stopped`: process shutting down; never a transfer destination.

Heartbeat is refreshed at least once per second. The proxy treats status older
than five seconds, wrong IDs/schema, or future timestamps as unavailable.
`used.json` persists round/state identity outside the world so restarting a
used world never makes it `ready` again. Restarting an interrupted active match
ends that round rather than falsely presenting its old world as fresh.

## Proxy commands: control.json

Fields: `schemaVersion`, `serverId`, `generationId`, `commandId` (UUID),
`action`, `roundId` (UUID), `participants` (UUID strings), and
`swapIntervalSeconds`. The backend ignores wrong generations and acknowledges
accepted commands in `status.commandId`. Repeated IDs are idempotent.

- `activate`: claim an untouched world as the initial lobby. It becomes
  `waiting`; arrivals enter survival and a match starts after the countdown.
- `prepare`: reserve an untouched generation for `roundId` and a roster before
  anyone transfers. It becomes `prepared`. The same round can be updated while
  prepared to remove players who disconnected during transfer; a different
  round cannot claim a used world.

If the destination leaves `prepared` before a roster update arrives, the proxy
reconciles the same reserved round and generation against the heartbeat's
acknowledged command and actual roster. Running participants remain in that
round and can reconnect to resume it; a late update cannot strand the transfer.

The proxy waits for destination acknowledgment before creating connection
requests. All connected players on the source (including observers) move
together and join the next round. Late joiners in a running world observe until
the next round. Disconnects alone do not trigger a fresh seed; a running round
pauses until its participants reconnect. A first participant death persists
one `endedRoundId`; later deaths cannot trigger another rotation for that round.

## Transfer and reset

One transfer transaction at a time is persisted in
`proxy/plugins/deathswap-network/state.json`: the active backend/generation,
source ended-round identity, reserved destination/generation, new round ID,
and roster. This survives proxy restarts and prevents duplicate round events.
Failed or partial connection requests leave the source intact and retry; a
backend with players or a used/stale generation is never a fresh destination.
Players continue using one proxy address. Reconnecting players are routed to
the transaction's destination while a transfer is underway.

Only after everyone has left the source does the proxy atomically write
`reset.request.json`: `schemaVersion`, `serverId`, `generationId`, `requestId`
(UUID), `requestedAt` (Unix milliseconds). The backend must be `ended`, have
zero players, and match server/generation before entering terminal `resetting`
and reporting the exact `resetRequestId`.
The proxy persists the request timestamp with its ID and replays the identical
request on retries and proxy restarts so a durable authorization remains valid.

The supervisor requires a fresh zero-player `resetting` heartbeat with that
exact request ID and generation, and persists `reset.authorized.json` before
stopping the JVM. It waits until the process exits before deleting only the
resettable world directories and any world-local player data. A pending request
alone never authorizes deletion at container startup. A matching durable
authorization receipt permits recovery if the container stopped between
authorization, JVM shutdown, deletion, and generation update.

After reset, the supervisor writes a new generation UUID and a different random
seed, removes matching transaction files, and boots a fresh world. Config,
operators, whitelist, and proxy data are retained. Docker's restart policy
restarts that backend while the group plays on its other server.

## Artifacts

The root Gradle project still produces `build/libs/death-swap-1.0.0+mc26.2.jar`.
The new `velocity-plugin` subproject produces
`velocity-plugin/build/libs/death-swap-proxy-1.0.0.jar`. FabricProxy-Lite is
installed separately by the Docker image, so the ordinary mod keeps working
without a proxy dependency. Online authentication stays enabled at the proxy;
modern forwarding uses an operator-provided secret, and backends remain on the
private Compose network.
