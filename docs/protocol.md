# AgentCraft Server protocol v1

> GENERATED from `protocol/src/*.ts` by `npm run gen:protocol`. Do not edit by hand.

## Transport

- The **foreman** listens on `ws://127.0.0.1:7878` (configurable, private networks only). The **plugin** connects as client.
- The plugin sends `Authorization: Bearer <token>` on the upgrade request and repeats the token in `hello`. Wrong token: HTTP 401 / `error {code: "unauthorized"}` and close.
- Upgrade requests with an `Origin` header (browsers) are rejected with HTTP 403.
- One JSON object per text frame: `{ "v": 1, "type": "<type>", "id"?: "<correlation id>", ...payload }`.
- A message with an `id` is answered with `ack { re: id, ok }` (except `tool.call`, answered with `tool.result`).
- Timestamps are epoch milliseconds. Optional fields are omitted, never `null`. Receivers ignore unknown fields.
- `*.upsert` replaces the whole entity by id; `feed.add` appends. After reconnecting, the plugin sends `hello` again and the `snapshot` rebuilds everything.

## World tools

`tool.call.call.tool` is one of: `world.inspect`, `world.preview`, `world.place`, `world.undo`, `server.command`, `server.logs`, `server.metrics`, `player.simulate`, `body.move`, `body.say`.

Staging only (refused with `not_staging` when the plugin's configured role is production): `player.simulate`.

The plugin validates every call (region, block blacklist, command whitelist, volume, rate limit) and never trusts the arguments.

## Plugin → foreman

### `hello`

First message after connecting. The foreman answers with `snapshot`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `token` | string | yes | shared secret; also sent as `Authorization: Bearer` |
| `pluginVersion` | string | yes |  |
| `server` | object | yes |  |
| `capabilities` | object | yes |  |
| `limits` | object | yes |  |

```json
{"v":1,"type":"hello","token":"example-token-not-a-secret-0001","pluginVersion":"0.1.0","server":{"id":"staging","role":"staging","mcVersion":"26.2","software":"Purpur 26.2 build 2633"},"capabilities":{"citizens":true,"worldguard":true,"worldedit":true,"luckperms":true,"spark":false},"limits":{"blocksPerTick":200,"maxJobVolume":200000,"maxInspectVolume":65536,"toolCallsPerMinute":60}}
```

### `goal.submit`

New goal for the lead (`/agent goal <text>`).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `actor` | object | yes | the player who asked; the plugin has already checked their permission, the foreman logs it |
| `text` | string | yes |  |

```json
{"v":1,"type":"goal.submit","id":"c1","actor":{"uuid":"2f8a3c1e-4b5d-4e6f-8a9b-0c1d2e3f4a5b","name":"Zogorat"},"text":"Trova e correggi i bug di EconomyPlus"}
```

### `user.message`

Message to one agent or to everyone (`/agent @<name> ...`).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `actor` | object | yes | the player who asked; the plugin has already checked their permission, the foreman logs it |
| `to` | string | yes | agent id or "all" |
| `text` | string | yes |  |

```json
{"v":1,"type":"user.message","id":"c2","actor":{"uuid":"2f8a3c1e-4b5d-4e6f-8a9b-0c1d2e3f4a5b","name":"Zogorat"},"to":"briciola","text":"Prova anche con 0 oggetti"}
```

### `decision.answer`

Answer to an open decision (`/agent answer`, approve, reject).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `actor` | object | yes | the player who asked; the plugin has already checked their permission, the foreman logs it |
| `decisionId` | string | yes | stable identifier, e.g. "t3", "d4", "bussola" |
| `option` | string | no |  |
| `text` | string | no |  |

```json
{"v":1,"type":"decision.answer","id":"c3","actor":{"uuid":"2f8a3c1e-4b5d-4e6f-8a9b-0c1d2e3f4a5b","name":"Zogorat"},"decisionId":"d3","option":"Merge"}
```

### `task.action`

Steer a task (`/agent task <id> cancel|retry|reassign <name>`).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `actor` | object | yes | the player who asked; the plugin has already checked their permission, the foreman logs it |
| `taskId` | string | yes | stable identifier, e.g. "t3", "d4", "bussola" |
| `action` | `cancel` \| `retry` \| `reassign` \| `prioritize` | yes |  |
| `agentId` | string | no | reassign target |
| `priority` | integer | no |  |

```json
{"v":1,"type":"task.action","id":"c4","actor":{"uuid":"2f8a3c1e-4b5d-4e6f-8a9b-0c1d2e3f4a5b","name":"Zogorat"},"taskId":"t4","action":"reassign","agentId":"lucciola"}
```

### `agent.action`

Steer an agent (`/agent pause|resume|stop|spawn`).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `actor` | object | yes | the player who asked; the plugin has already checked their permission, the foreman logs it |
| `agentId` | string | yes | agent id, or "all" for pause/resume/stop |
| `action` | `pause` \| `resume` \| `stop` \| `spawn` | yes |  |
| `role` | `lead` \| `developer` \| `qa` \| `architect` \| `builder` \| `analyst` | no | spawn: role of a new agent |

```json
{"v":1,"type":"agent.action","id":"c5","actor":{"uuid":"2f8a3c1e-4b5d-4e6f-8a9b-0c1d2e3f4a5b","name":"Zogorat"},"agentId":"mattone","action":"pause"}
```

### `diff.request`

Ask for the diff of a task (`/agent diff <task>`).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `actor` | object | yes | the player who asked; the plugin has already checked their permission, the foreman logs it |
| `taskId` | string | yes | stable identifier, e.g. "t3", "d4", "bussola" |

```json
{"v":1,"type":"diff.request","id":"c6","actor":{"uuid":"2f8a3c1e-4b5d-4e6f-8a9b-0c1d2e3f4a5b","name":"Zogorat"},"taskId":"t2"}
```

### `repo.add`

Register a plugin repository (`/agent repo add <path>`).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `actor` | object | yes | the player who asked; the plugin has already checked their permission, the foreman logs it |
| `path` | string | yes |  |

```json
{"v":1,"type":"repo.add","id":"c7","actor":{"uuid":"2f8a3c1e-4b5d-4e6f-8a9b-0c1d2e3f4a5b","name":"Zogorat"},"path":"/srv/agentcraft/repos/castigo-plugins"}
```

### `tool.result`

Answer to `tool.call`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `re` | string | yes | id of the tool.call |
| `ok` | boolean | yes |  |
| `result` | any | no | tool-specific result when ok |
| `error` | object | no |  |

```json
{"v":1,"type":"tool.result","re":"f12","ok":false,"error":{"code":"out_of_region","message":"block 120,64,-30 is outside region \"piazza\""}}
```

### `job.progress`

Progress of a long world job (world.place, world.undo).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `jobId` | string | yes | stable identifier, e.g. "t3", "d4", "bussola" |
| `state` | `queued` \| `running` \| `paused` \| `done` \| `failed` \| `cancelled` \| `undone` | yes |  |
| `done` | integer | yes |  |
| `total` | integer | yes |  |
| `message` | string | no |  |

```json
{"v":1,"type":"job.progress","jobId":"j7","state":"running","done":1400,"total":5200}
```

### `world.event`

Something agents may care about (already filtered by the plugin).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `kind` | `console_error` \| `plugin_error` \| `player_join` \| `player_quit` \| `region_enter` \| `tps_low` | yes |  |
| `text` | string | yes |  |
| `ts` | integer | yes | epoch milliseconds |
| `player` | string | no | Minecraft player UUID |

```json
{"v":1,"type":"world.event","kind":"plugin_error","text":"[EconomyPlus] NullPointerException in ShopCommand","ts":1762862400000}
```

## Foreman → plugin

### `snapshot`

Full state, sent after a valid `hello`. Replaces everything the plugin knows.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `foreman` | object | yes |  |
| `agents` | object[] | yes |  |
| `tasks` | object[] | yes |  |
| `decisions` | object[] | yes |  |
| `goals` | object[] | yes |  |
| `feed` | object[] | yes | most recent last |

```json
{"v":1,"type":"snapshot","foreman":{"version":"0.1.0","backend":"sim","auth":"ok","paused":false,"budget":{"spentTodayUsd":1.2,"dailyUsd":15,"spentMonthUsd":31.5,"monthlyUsd":300}},"agents":[{"id":"briciola","name":"Briciola","role":"qa","title":"Cacciatrice di bug","color":"#8fa98b","state":"testing","activity":"testing EconomyPlus","station":"testbench","taskId":"t4","paused":false,"active":true,"model":"Sonnet 5.5","costUsd":0.42}],"tasks":[{"id":"t4","title":"Test del comando /shop sell","kind":"qa","status":"doing","assignee":"briciola","deps":["t2"],"repoId":"castigo-plugins","goalId":"g1","priority":0,"branch":"agentcraft/briciola/t4-shop-sell-tests","ci":"running","createdBy":"bussola","createdAt":1762862400000,"updatedAt":1762862460000}],"decisions":[{"id":"d3","agentId":"bussola","kind":"merge","question":"Unire la correzione di /shop sell in main?","options":["Merge","Request changes","Reject"],"context":"2 file, +41 -7. Test: 18 passati.","status":"open","taskId":"t2","createdAt":1762862520000}],"goals":[{"id":"g1","text":"Trova e correggi i bug di EconomyPlus","status":"active","progress":0.4,"createdBy":"user:Zogorat","createdAt":1762862400000,"updatedAt":1762862400000}],"feed":[{"ts":1762862400000,"kind":"goal","text":"Nuovo obiettivo da Zogorat","to":"all"}]}
```

### `agent.upsert`

Replace one agent by id.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `agent` | object | yes |  |

```json
{"v":1,"type":"agent.upsert","agent":{"id":"briciola","name":"Briciola","role":"qa","title":"Cacciatrice di bug","color":"#8fa98b","state":"testing","activity":"testing EconomyPlus","station":"testbench","taskId":"t4","paused":false,"active":true,"model":"Sonnet 5.5","costUsd":0.42}}
```

### `task.upsert`

Replace one task by id.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `task` | object | yes |  |

```json
{"v":1,"type":"task.upsert","task":{"id":"t4","title":"Test del comando /shop sell","kind":"qa","status":"doing","assignee":"briciola","deps":["t2"],"repoId":"castigo-plugins","goalId":"g1","priority":0,"branch":"agentcraft/briciola/t4-shop-sell-tests","ci":"running","createdBy":"bussola","createdAt":1762862400000,"updatedAt":1762862460000}}
```

### `decision.upsert`

Replace one decision by id.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `decision` | object | yes |  |

```json
{"v":1,"type":"decision.upsert","decision":{"id":"d3","agentId":"bussola","kind":"merge","question":"Unire la correzione di /shop sell in main?","options":["Merge","Request changes","Reject"],"context":"2 file, +41 -7. Test: 18 passati.","status":"open","taskId":"t2","createdAt":1762862520000}}
```

### `goal.upsert`

Replace one goal by id.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `goal` | object | yes |  |

```json
{"v":1,"type":"goal.upsert","goal":{"id":"g1","text":"Trova e correggi i bug di EconomyPlus","status":"active","progress":0.5,"createdBy":"user:Zogorat","createdAt":1762862400000,"updatedAt":1762862700000}}
```

### `feed.add`

Append to the activity feed.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `item` | object | yes |  |

```json
{"v":1,"type":"feed.add","item":{"ts":1762862400000,"kind":"ci","text":"Test di t2: 18 passati","agentId":"lucciola"}}
```

### `foreman.status`

Backend, auth, pause and budget status.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `status` | object | yes |  |

```json
{"v":1,"type":"foreman.status","status":{"version":"0.1.0","backend":"claude","auth":"ok","paused":false}}
```

### `notify`

Tell admins something (clickable chat message, optional Discord).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `level` | `info` \| `warn` \| `need_user` | yes |  |
| `text` | string | yes |  |
| `decisionId` | string | no | makes the chat message clickable |

```json
{"v":1,"type":"notify","level":"need_user","text":"Bussola chiede di unire t2","decisionId":"d3"}
```

### `diff`

Answer to `diff.request`: short summary for chat plus where the full diff is.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `taskId` | string | yes | stable identifier, e.g. "t3", "d4", "bussola" |
| `summary` | string | yes |  |
| `files` | integer | yes |  |
| `additions` | integer | yes |  |
| `deletions` | integer | yes |  |
| `path` | string | no | path of the full .diff file on the foreman machine |

```json
{"v":1,"type":"diff","taskId":"t2","summary":"Corretto il calcolo del prezzo di vendita","files":2,"additions":41,"deletions":7,"path":"/srv/agentcraft/diffs/t2.diff"}
```

### `tool.call`

Ask the plugin to run a world tool. Always answered with `tool.result`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | stable identifier, e.g. "t3", "d4", "bussola" |
| `agentId` | string | yes | stable identifier, e.g. "t3", "d4", "bussola" |
| `taskId` | string | no | stable identifier, e.g. "t3", "d4", "bussola" |
| `requestedBy` | string | yes | who started the work: "user:<name>" or an agent id |
| `deadlineMs` | integer | yes |  |
| `call` | object \| object \| object \| object \| object \| object \| object \| object \| object \| object | yes |  |

```json
{"v":1,"type":"tool.call","id":"f12","agentId":"mattone","taskId":"t9","requestedBy":"user:Zogorat","deadlineMs":30000,"call":{"tool":"world.place","args":{"jobId":"j7","approvalId":"d5","region":"piazza","anchor":{"world":"world","x":100,"y":64,"z":-40},"rotation":0,"ops":[{"op":"fill","from":[0,0,0],"to":[8,0,8],"block":"minecraft:stone_bricks"}]}}}
```

### `job.control`

Pause, resume or cancel a running world job.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `jobId` | string | yes | stable identifier, e.g. "t3", "d4", "bussola" |
| `action` | `pause` \| `resume` \| `cancel` | yes |  |

```json
{"v":1,"type":"job.control","jobId":"j7","action":"pause"}
```

### `ack`

Answer to any message that carried an `id`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `re` | string | yes | stable identifier, e.g. "t3", "d4", "bussola" |
| `ok` | boolean | yes |  |
| `error` | string | no |  |
| `result` | any | no |  |

```json
{"v":1,"type":"ack","re":"c1","ok":true,"result":{"goalId":"g1"}}
```

### `error`

Protocol error (invalid message, bad token, version mismatch).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | correlation id; messages that carry one are answered with `ack` |
| `code` | `invalid_message` \| `unauthorized` \| `version_mismatch` \| `internal` | yes |  |
| `message` | string | yes |  |

```json
{"v":1,"type":"error","code":"unauthorized","message":"invalid token"}
```
