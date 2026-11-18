# Toque home link: local read-only API (version 1)

Toque can expose the robot's state, cooking events and recipe library on the home network, for
Home Assistant or any other home automation system. The link is **off by default** and is
switched on in Toque › Settings › Home link.

- **Read-only.** Nothing in this API starts, changes, pauses or stops the machine.
- **Local.** Plain HTTP on the home network, port **8730** by default. No cloud, no account.
- **Paired.** Every request except `/api/info` and `/api/pair` needs a token obtained by pairing.

All bodies are JSON in UTF-8. Texts (step text, recipe titles, messages) are in the language of
the robot's interface. Errors are `{"error": "<code>", "message": "<text>"}` with an HTTP status:
`400` bad request, `401` missing or unknown token, `403` pairing refused, `404` not found,
`429` too many attempts.

## Discovery

The robot announces itself by mDNS / DNS-SD as service type `_toque._tcp` (in Home Assistant's
zeroconf terms: `_toque._tcp.local.`), with the HTTP port as the service port. The service name
is `Toque` followed by a short id.

## Pairing

1. On the robot: Settings › Home link › **Pair a device**. The screen shows a 6-digit code,
   valid for 5 minutes.
2. The client sends it once:

```
POST /api/pair
{"code": "482913", "name": "Home Assistant"}
→ 200 {"token": "9f2c…(64 hex chars)", "id": "toque-1a2b3c4d", "name": "Toque"}
```

A wrong code returns `403 {"error": "bad_code"}`; after 5 wrong attempts the code is cancelled
(`429 {"error": "too_many_attempts"}`) and a new one must be shown on the robot. With no code
on screen: `403 {"error": "pairing_closed"}`.

3. Every later request carries `Authorization: Bearer <token>`.

"Unpair all devices" on the robot revokes every token; requests then get `401`.

## GET /api/info (no token)

```
{"id": "toque-1a2b3c4d", "name": "Toque", "version": "0.6.0-iamslan", "api": 1,
 "model": "Monsieur Cuisine connect", "pairing_open": false}
```

`id` is stable for one installation of Toque and unique per robot: use it as the device's
unique id.

## GET /api/status

```
{
  "id": "toque-1a2b3c4d",
  "version": "0.6.0-iamslan",
  "time": 1794996000,
  "link": "ready",
  "mode": "hardware",
  "state": "running",
  "fault": null,
  "lid_open": false,
  "bowl_present": true,
  "bowl_sensor_reliable": true,
  "motor_on": true,
  "heater_on": true,
  "temperature": 37,
  "temperature_below_range": false,
  "target_temperature": 60,
  "speed": 2,
  "rpm": 190,
  "weight": 512,
  "weight_stable": true,
  "firmware": 4922,
  "job": {
    "kind": "manual",
    "label": "Cuisson manuelle",
    "state": "running",
    "pause_reason": null,
    "elapsed": 35,
    "total": 180,
    "remaining": 145,
    "ends_at": 1794996145,
    "phase": 1,
    "phases": 1,
    "speed": 2,
    "target_temperature": 60,
    "reverse": false,
    "accessory": "blade"
  },
  "guided": {
    "recipe_id": "web-250008",
    "recipe_title": "Beurre de cacahuètes",
    "step": 3,
    "steps": 7,
    "step_text": "Mixer 30 s à vitesse 8.",
    "step_kind": "machine",
    "step_setting": "00:30 · vitesse 8",
    "weigh_target": null,
    "waiting_for_user": false
  }
}
```

Field notes:

- `link`: `offline`, `connecting`, `ready` or `fault` (the link between the tablet and the motor
  base).
- `mode`: `hardware` (real machine), `demo` (simulated base) or `twin`.
- `state`, one word for the whole robot: `offline`, `idle`, `running`, `paused`,
  `waiting_for_user` (a guided recipe is open and the machine is waiting for the cook) or
  `fault`.
- `fault`: `null`, or `{"code": "...", "message": "..."}`.
- `temperature`: bowl temperature in °C, or `null` when unknown. The motor base measures nothing
  under 37 °C: it then reports nothing useful, `temperature` is `null` and
  `temperature_below_range` is `true` ("below 37 °C").
- `bowl_present`: `null` when `bowl_sensor_reliable` is `false`.
- `weight`: grams, may be negative after a tare.
- `job`: `null` when no program is loaded. `kind` is one of `manual`, `knead`, `steam`, `toast`,
  `turbo`. `state` is one of `starting`, `running`, `paused`, `stopping`, `finished`, `aborted`.
  Durations are in whole seconds. `ends_at` (Unix time, seconds) is set only while `running`,
  otherwise `null`; prefer it to `remaining` for a countdown, it does not change every second.
  `target_temperature` is `null` without heating.
- `guided`: `null` when no guided recipe is in progress. `step` counts from 1. `step_kind` is
  `machine` (a program to launch), `weigh` (weigh `weigh_target` grams) or `text` (something to
  do by hand). `step_setting` is a short human summary for machine steps, else `null`.
  `waiting_for_user` is `true` when the recipe is open and no program is running.

## GET /api/events (server-sent events)

A long-lived `text/event-stream` response. The client keeps it open and reconnects if it drops.
Each message is:

```
event: <name>
data: <one line of JSON>

```

A comment line (`: keep-alive`) is sent every 15 seconds.

| Event | Sent when | Data |
|---|---|---|
| `status` | On connect, then whenever something other than elapsed time changes (at most once a second), and at least every 30 seconds | the same object as `/api/status` |
| `job_started` | a program starts | `{"time", "kind", "label", "total", "guided"}` |
| `job_paused` | a program pauses | `{"time", "kind", "label", "reason"}` |
| `job_resumed` | a program resumes | `{"time", "kind", "label"}` |
| `job_finished` | a program runs to its end | `{"time", "kind", "label", "guided"}` |
| `job_aborted` | a program is stopped before its end | `{"time", "kind", "label", "reason"}` |
| `waiting_for_user` | a guided recipe step has finished and the robot waits for the cook to do the next one | `{"time", "guided"}` (`guided` describes the step now waiting) |
| `recipe_finished` | the last machine step of a guided recipe has finished | `{"time", "recipe_id", "recipe_title"}` |
| `lid_opened`, `lid_closed` | the lid changes | `{"time"}` |
| `fault` | the machine reports a fault | `{"time", "code", "message"}` |

`time` is Unix time in seconds. `guided` is the `guided` object of `/api/status`, or `null`.
`waiting_for_user` and `recipe_finished` are sent about 3 seconds after `job_finished`, once it
is known that no chained step started by itself. Events are not replayed: a client that was
disconnected reads `/api/status` (or the first `status` event) to catch up.

## Recipes

### GET /api/recipes?q=&category=&favorites=&limit=&offset=

Search the robot's library. With an empty `q`, all recipes, favourites first then by title.
`favorites=1` keeps favourites only. `limit` defaults to 50 (maximum 200), `offset` to 0.

```
{"total": 2287, "offset": 0, "limit": 50, "items": [
  {"id": "web-250008", "title": "Beurre de cacahuètes", "category": "Sauces et dips",
   "tags": ["Végan", "Végétarien"], "minutes": 3, "rating": 4.8, "rating_count": 1610,
   "favorite": false, "machine_steps": 2, "has_image": true}
]}
```

### GET /api/categories

```
{"categories": ["Desserts", "Entrées", "Plats principaux", "Sauces et dips"]}
```

### GET /api/recipes/{id}

The whole recipe, with the cook's own quantity adjustments applied:

```
{"id": "web-250008", "title": "Beurre de cacahuètes", "category": "Sauces et dips",
 "tags": ["Végan"], "servings": 2, "prep_minutes": 3, "minutes": 3, "difficulty": 1,
 "author": "Monsieur Cuisine Official", "rating": 4.8, "rating_count": 1610,
 "favorite": false, "notes": "…", "has_image": true,
 "ingredients": [
   {"group": "", "items": [
     {"quantity": "300", "unit": "g", "name": "cacahuètes grillées", "note": "non salées",
      "text": "300 g cacahuètes grillées, non salées"}
   ]}
 ],
 "steps": [
   {"text": "Mettre les cacahuètes dans le bol.", "kind": "weigh", "setting": null, "weigh_target": 300},
   {"text": "Mixer 30 s à vitesse 8.", "kind": "machine", "setting": "00:30 · vitesse 8", "weigh_target": null}
 ]}
```

`quantity` is a string because recipes contain fractions and ranges ("1/2", "2-3"); it may be
empty. `text` is the ready-to-display line for a shopping list.

### GET /api/recipes/{id}/image

The recipe's photo as `image/jpeg`, or `404` when the robot has none.
