# freepsvoice

Minimal native Android app: speak (or type) a German command, an LLM picks a
smart-home flow from a [freeps](#freeps-endpoints) flow-builder server, the app
fires it and shows the reply on screen.

## Two variants

| Flavor | Application id | For |
|---|---|---|
| `phone` | `eu.rauhe.freepsvoice` | Normal app on a regular phone. |
| `wall` | `eu.rauhe.freepsvoice.wall` | Wall panel: device-owner kiosk (Moto G5). |

Both share one code base; `wall` only adds an id suffix, so the two install side by
side. The device-owner commands below always use the **suffixed** id.

```
mic ──WAV──▶ Mistral Voxtral (STT) ──text──▶ Mistral LLM (pick flow)
                                                    │
phone screen ◀── reply ◀──── POST /flow/<name> ─────┘
```

No accounts, no backend of its own, no other services. Only two endpoints are
contacted: the **Mistral API** (STT + LLM) and the **freeps server** on the LAN.
The Mistral key lives on the device (local settings screen).

## No speech output

The reply is **text only**. The target device (Moto G5, LineageOS 11) has the
Android TTS subsystem compiled out entirely: `android.software.text_to_speech`
is not in `pm list features`, the `texttospeech` service does not exist, and
`TextToSpeechManagerService` is absent from `services.jar`. Sideloading an
engine (RHVoice, Piper, …) cannot help — those are plugins for a service that
isn't there. Speech would mean cloud TTS (`POST /v1/audio/speech`), which costs
money per reply; deliberately not wired up.

## Why native Android (not a PWA)

- freeps is plain **HTTP on the LAN** with no CORS headers — an HTTPS-served
  web app cannot call it (mixed content + CORS). A native app can.
- The Mistral API has no browser CORS and the key should not sit in JS.

## API contracts (the whole point — self-contained, no other repo needed)

### Mistral STT
```
POST {mistral_base}/audio/transcriptions
Authorization: Bearer <key>
multipart/form-data: file=command.wav (audio/wav, 16 kHz mono PCM16),
                     model=voxtral-mini-2602, language=de
→ 200 {"text": "transcribed command"}
```

### Mistral LLM (OpenAI-compatible)
```
POST {llm_base}/chat/completions
Authorization: Bearer <key>
json: {model, temperature: 0, response_format: {"type": "json_object"},
       messages: [system, user]}
→ choices[0].message.content is a JSON string:
  {"flow": "<exact flow name or null>", "reply_ok": "...", "reply_error": "..."}
```
The picked name is validated against the catalogue — a hallucinated name never
reaches `runFlow`.

### Mistral model catalogue
```
GET {mistral_base}/models
→ {"data": [{"id": ..., "name": ..., "capabilities": {"completion_chat": bool,
     "audio_transcription": bool, ...}, "deprecation": ...}]}
```
Drives the two model pickers in the settings screen. Deprecated entries are
dropped; `completion_chat` fills the LLM picker, `audio_transcription` the STT
one. A docs **slug** is not the API **id** (`voxtral-mini-transcribe-26-02` vs
`voxtral-mini-2602`), so ids are never hardcoded from documentation.

### freeps endpoints
```
GET  {freeps}/flowbuilder/ListFlows
→ {name: {"DisplayName": ..., "Description": ..., "Kind": ..., "Tags": ...}, ...}

POST {freeps}/flow/<url-encoded name>   (no body)
→ 2xx = flow started
```

## Repo map

| File | Role |
|---|---|
| `MainActivity.kt` | Compose UI (talk button, typed input) + `AppViewModel` + settings screen |
| `VoiceController.kt` | one turn: STT → list → pick → run → reply; every stage degrades to a shown line |
| `FlowPicker.kt` | system prompt + catalogue formatting + tolerant JSON extraction |
| `Mistral.kt` | OkHttp calls: `transcribe(wav)`, `chat(system, user)`, `listModels()` |
| `Freeps.kt` | `listFlows()`, `runFlow(name)` |
| `Recorder.kt` | `AudioRecord` 16 kHz mono PCM16 → WAV bytes, 15 s cap |
| `Wav.kt` | PCM → WAV header (pure, unit-tested) |
| `Settings.kt` | SharedPreferences: key, URLs, model ids, kiosk schedule |
| `Kiosk.kt` | device owner: lock task, on/off schedule, approach-wake |

Built for the wall mount in **landscape** (`fullSensor`, adaptive: talk button beside
the output/typing column; portrait keeps the stacked layout). Rotation doesn't
recreate the activity, so a turn in progress survives it.

## Build & run

No Android SDK on the host — everything runs in Docker:

```sh
./tools/build.sh                 # all debug variants (builds the image on first run)
./tools/build.sh assembleWallDebug
./tools/build.sh assemblePhoneDebug
./tools/build.sh testDebugUnitTest
./tools/build.sh shell           # bash in the container
adb install -r app/build/outputs/apk/wall/debug/app-wall-debug.apk
```

The image is `gradle:8.11.1-jdk17` plus SDK 35 / build-tools 35.0.0. Gradle
cache and the debug keystore live in named volumes (`freeps-gradle-home`,
`freeps-android-home`) — deleting the latter changes the debug signature and
requires `adb uninstall` before the next install.

On first start: grant the microphone, open **Einstellungen**, set the Mistral
key and the freeps URL, save. Defaults: `llm_model=ministral-14b-2512`,
`stt_model=voxtral-mini-2602`, base `https://api.mistral.ai/v1`,
`freeps=http://raspi.fritz.box:8080`.

Usage: tap **Sprechen**, talk, tap **Stopp** → transcript and reply shown, flow
fired. Or type the command instead. **Verbindung testen** in the settings screen
reports freeps flow count and Mistral reachability — useful once the phone is
mounted on a wall and has no logcat.

CI (`.github/workflows/build.yml`) builds both debug variants with the committed
wrapper, uploads them as workflow artifacts on push/PR, and publishes them as
release assets on `v*` tags.

## Wall mode (kiosk)

**Wandbetrieb** in the settings screen enables lock-task (no status/nav bar, no
escape, other apps can't launch) plus a daily schedule: a wake alarm turns the
screen on at **An** (default 06:00), a sleep alarm (`lockNow`) turns it off at
**Aus** (default 01:00); alarms re-arm themselves after every firing. With
**Wach, wenn Hand vor dem Sensor** the panel sleeps 2 min after you walk away
and wakes when a hand passes the top bezel — the G5 proximity sensor is an
ear-proximity type (few cm range), so it detects a hand wave or picking the
panel up, *not* someone walking near. Never wakes outside the schedule window. The power
button keeps working — no wake lock is ever held. (If the button seems dead
while the screen is off: the ROM ignores it while the sensor reads "near" —
keep the top bezel clear.)

All of this needs **device owner**, which can only be granted over adb while the
device has no accounts (remove them in Settings first, `pm clear` is not enough):

```sh
adb shell dpm set-device-owner --name kiosk \
  eu.rauhe.freepsvoice.wall/eu.rauhe.freepsvoice.KioskAdmin
```

Undo with `adb shell dpm remove-active-admin \
  eu.rauhe.freepsvoice.wall/eu.rauhe.freepsvoice.KioskAdmin`.

**adb after a reboot:** the "Allow USB debugging?" dialog cannot appear while
lock-task is active (it blocks activities from other packages), so adb stays
`unauthorized` until you either ticked *Always allow from this computer* once
(the key then survives reboots), or briefly turn **Wandbetrieb** off in
Einstellungen → Speichern to leave lock task.

## Release signing

`./tools/make-keystore.sh` once → `freepsvoice-release.jks` +
`keystore.properties` (both gitignored — **back them up**; without the keystore
the installed app can only be replaced by uninstalling). `assembleRelease` then
signs automatically. Not needed for debug sideloads.

## Open notes / TODO

1. **Key stored in plain SharedPreferences** — switch to
   `androidx.security:security-crypto` (EncryptedSharedPreferences).
2. **Cleartext allowed globally** (`network_security_config.xml`) because the
   freeps host is user-configurable HTTP. Narrow to the configured LAN host
   once settings exist, or require HTTPS.
3. **Tap-to-start/tap-to-stop** — hold-to-talk (press & release) is nicer and
   matches the original robot UX.
4. **Flow catalogue is fetched on every turn** — cache it, refresh on open /
   pull-to-refresh / settings change.
5. **Release signing** — config + `tools/make-keystore.sh` exist; run the script
   once the app is feature-complete (see *Release signing*).
6. **Speech output** — would need cloud TTS (`POST /v1/audio/speech`,
   `voxtral-mini-tts-2603`), which needs no framework TTS and therefore works
   on this device. Costs money per reply and needs internet. Not wired up.
7. **Wall mode** — done, see *Wall mode (kiosk)*. Watch: `setKeyguardDisabled`
   and lock-task behaviour on this LineageOS build after long uptimes.
8. **Error UX** — failures are one status line; no dialogs, no retry buttons.
9. The system prompt still says "Smart-Home-Roboters" (kept verbatim from
   the original robot app for behavioral parity) — reword freely.
10. Original reference implementation was Python (push-to-talk on a robot
    antenna); this app is fully standalone and needs nothing from it.
