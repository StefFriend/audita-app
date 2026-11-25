# Audita (Android)

Control app for **Audita**, a hardware listening processor for the forensic transcription of interceptions
(phone calls, room recordings, prison visits, device captures). The computer plays the recording in any player;
Audita processes it in real time and sends it to the headphones. Everything is automatic: the app is for checking
what Audita is doing and for fine tuning.

## Features

- **Listen**: intensity (Low / Medium / High), A/B comparison with the untouched original, headphone volume,
  input/output meters, and what Audita detects in the recording (channels, telephone or wide band, voice, mains hum,
  GSM buzz, noise reduction, room reverb, bandwidth extension, headphone correction).
- **Tune**: follow the source or force mono, noise reduction engine (neural / classic / both), Auto / On / Off for
  every processing block, bandwidth extension envelope, limiter ceiling, what goes back to the computer over USB.
- **Headphones**: search the [AutoEq](https://github.com/jaakkopasanen/AutoEq) database (about 8,800 profiles),
  preview the correction curve and load it into Audita; or load any Equalizer APO `ParametricEQ.txt` file
  (up to 12 filters). The profile is stored on Audita and stays active without the phone.
- **Log**: the session log for the expert report: what Audita detected and every change of settings, with the time,
  plus your own notes. Kept on Audita (about the last 1,000 events) and in the app; saved as text or CSV with a
  SHA-256 fingerprint of its content.
- **Hearing protection**: estimated daily dose (80 dB for 8 hours = 100 %), sound level now, shock guard against bangs.
- **96 kHz mode**: playback of high-resolution files without processing (Audita restarts to change mode).
- **Device**: firmware, USB audio state, processing load.

The UI follows the phone's light or dark theme.

## Install

Every push to `main` builds the APK and publishes it as the release **latest**: open the release page on the phone,
download `Audita.apk` and install it (allow apps from unknown sources). Android 8.0 or later, Bluetooth LE.

## How it talks to Audita

Bluetooth LE, service `f9850001-ea54-a88c-b14f-10ded3bd6d44` (RX `…0002` write without response, TX `…0003` notify).
Each packet starts with a flag byte (`0x80` first, `0x40` last). A request is `"<id> <path>?<query>\n"` followed by an
optional body; the answer is `"<id> <status>\n"` followed by JSON. One request at a time.

| Path | Use |
|---|---|
| `/api/info` | firmware name and version |
| `/api/lstate` | full state: detection, levels, settings, overrides, headphone profile |
| `/api/lset?k=…&v=…` | `vol`, `ceil`, `ch` (auto/mono), `level` (0–2), `bypass`, `ret` (proc/out), `dn` (0 classic, 1 neural, 2 both), `bweenv` (0/1), `ov.<block>` = auto/on/off for `band hum gsm level eq denoise bwe dereverb` |
| `/api/heq` | loaded headphone profile with its filters |
| `/api/heqset?name=…` | body: `ParametricEQ.txt` text; checks, applies and stores it |
| `/api/heqon?v=0\|1`, `/api/heqdel` | correction on/off, remove profile |
| `/api/time?t=<unix>&tz=<min>` | phone clock, for the log and the daily dose |
| `/api/log?from=<seq>&n=<max>` | session log entries (up to 40 per request) |
| `/api/note` | body: a note (up to 40 characters) added to the log |
| `/api/dosereset` | resets today's hearing dose |

More `lset` keys: `ch` also `left` / `right`; `match`, `keep`, `shock`, `lb` (0/1); `sens` (dB SPL at full scale, 80–140);
`hiq` (1 = 96 kHz playback only, Audita restarts); `ov.restore`, `ov.tones`.

The same API answers on the board's serial port (`api /api/lstate`), which is how it is tested.

## Structure

- `app/src/main/assets/index.html`: the whole interface (HTML/CSS/JS). Opened in a desktop browser it runs against a
  small simulated Audita, which is handy for working on the UI.
- `MainActivity.kt`: WebView and Bluetooth LE bridge (`AuditaBridge`).
- `AutoEq.kt`: AutoEq index download (kept for 14 days), search and profile download.

## Build

`gradle assembleDebug` with JDK 17 and the Android SDK (API 34), or let GitHub Actions do it.

## Credits

Noise reduction in the firmware: RNNoise (Xiph.Org Foundation, BSD). Headphone profiles: AutoEq (Jaakko Pasanen, MIT).
Typeface: IBM Plex Sans and Mono (SIL Open Font License, see `assets/fonts`).

MIT License.
