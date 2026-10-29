# Anonymous model ratings: what a report holds

Enginehost can share how people rate the voice and translation models they use, so others can
choose (Droidtop/tracker#491). This page is the whole of what is sent, published before the first
report is.

**It is built and switched off** (owner, 2026-10-28: "build the system, but don't enable it yet").
`SharedStats.ENDPOINT` is empty, so no part of it shows and nothing is sent, until the statistics
server exists (#364) and the owner chooses where it runs and how long raw reports are kept. For the
rig, `adb shell setprop debug.enginehost.stats 1` shows it. With no endpoint, a report is then only
written to the local log, marked "not sent".

## When anything is sent

Only when all of these hold:

1. The person chose **Share** when asked, or turned on Settings > Privacy > "Share model ratings
   anonymously". They are asked once, the first time they set up a translator or turn on read
   aloud, and "Don't share" leads. With sharing off, nothing is sent, kept or queued.
2. They **rated a model**: Settings > Reading and translation > "Rate a voice or translator". One
   rating makes one report. Nothing is sent on a timer or in the background.
3. There is an endpoint.

Every report, sent or not, is in Settings > Privacy > "What was shared", exactly as it went
(`shared-stats-log.json` in the app's files, the newest 50).

## The report (schema 1)

One JSON object, POSTed to the endpoint over HTTPS, with no cookies. The User-Agent is
`enginehost-stats/1`, with no app version.

```json
{
  "schema": 1,
  "kind": "translation",
  "model": {"id": "gpt-4o-mini"},
  "backend": "openai",
  "language": "English",
  "purpose": "visual_novels",
  "rating": 4,
  "comment": "Keeps names right",
  "device": {"ramTier": "8", "socFamily": "qualcomm"},
  "speed": "1-3s"
}
```

| Field | What it is |
|---|---|
| `schema` | 1 |
| `kind` | `translation` or `voice` |
| `model.id` | Translation: the model the person named in the translator settings. Voice: the speech engine's package (`com.google.android.tts`). At most 80 characters |
| `model.version` | Voice only: the speech engine's version |
| `backend` | Voice: `on-device`. Translation: a known service by name (`openai`, `openrouter`, `deepseek`, `mistral`, `groq`, `together`, `google`), `local-network` for an address on the person's own network or device, `other-service` for anything else. Never the address: a server's name can identify its owner |
| `language` | Translation: the language translated into, as the person named it. Voice: the device's language (`en`) |
| `purpose` | Optional, from a fixed list: `visual_novels`, `rpg_text`, `accessibility`, `language_learning`, `other`. No free text, so no game is named here |
| `rating` | 1 to 5 |
| `comment` | Optional, the person's own words, one line of at most 200 characters |
| `device.ramTier` | `<=4`, `6`, `8` or `12+` (GB) |
| `device.socFamily` | The chip's maker in lower case (`qualcomm`, `mediatek`), or `unknown`. Never the model or board |
| `speed` | Translation only: the typical answer time, as `<1s`, `1-3s`, `3-10s` or `>10s` |

**Never in a report:** a game's name or text, an account, a key, the service's address, a device or
install id, or the app's version. One report per rating needs no de-duplication, so there is no id
at all. The endpoint is to drop the sender's IP address on arrival.

**Delivery.** A report that cannot be delivered is tried again when Settings opens: at most three
tries within a day, then it is dropped, and the log says so. Turning sharing off drops what waits.

## The aggregate

What the model picker shows comes from one published file (`SharedStats.AGGREGATE_URL`, also empty
for now), fetched at most once a day and kept only when this build can read it:

```json
{"schema": 1, "models": [
  {"kind": "translation", "model": "gpt-4o-mini", "backend": "openai",
   "count": 37, "average": 4.2, "popularFor": "visual_novels"}
]}
```

A model is shown only with at least 10 ratings, so a handful of reports never stands for a verdict.
The Translator row then reads, for example, "api.openai.com, into English · rated 4.2 by 37 ·
popular for Visual novels".

## Still to decide (with the server, #364)

- Where the endpoint runs. The recommendation is the Oracle VM.
- How long raw reports are kept before only the aggregate remains. The recommendation is 30 days.
