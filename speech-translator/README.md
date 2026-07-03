# Speech Translator (English ⇄ 日本語)

A personal, fully on-device speech translator for the Pixel 9 Pro. Hold the mic button and
speak — English comes out as spoken + written Japanese, Japanese comes out as English. The
model auto-detects which language was spoken.

## How it works

The microphone audio is fed **directly** to **Gemma 4 E2B** (no separate speech-recognition
model) running on-device via [LiteRT-LM](https://developers.google.com/edge/litert-lm/android):

```
hold mic → record 16 kHz mono WAV (≤30 s) → Gemma 4 (audio + instruction)
→ translated text → Android TTS speaks it (Japanese or English voice by output script)
```

Everything runs locally. After the one-time model download the app works in airplane mode.

## Getting the APK

Every push touching `speech-translator/` runs the **speech-translator APK** GitHub Actions
workflow. Download the `speech-translator-debug-apk` artifact from the run, unzip it, copy
`app-debug.apk` to the phone, and tap to install (allow "install unknown apps" if prompted).
You can also trigger a build manually from the Actions tab (workflow_dispatch).

To build locally instead: open `speech-translator/` in Android Studio, or run
`gradle assembleDebug` (Gradle 8.10+, JDK 17).

## First run

1. Launch on Wi-Fi. Tap **Download on Wi-Fi** — this fetches the Gemma 4 E2B model
   (`gemma-4-E2B-it.litertlm`, ~2.4 GB) from
   [litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm).
   The download is resumable and survives the app being killed. Alternatively download the
   file yourself and use **Import model file instead**.
2. Wait for "Starting the translation engine…" (engine init takes a few seconds; it tries
   GPU first and falls back to CPU).
3. Grant microphone permission on the first press of the mic button.

## Use

- **Hold** the big button, speak (up to 30 s — a countdown appears near the limit), release.
- The translation appears large on screen and is spoken aloud. **🔊 Replay** re-speaks it.
- Pressing the mic while it's still speaking cuts the speech off and starts listening.

If Japanese TTS is silent: Settings → System → Text-to-speech → Google engine → install
Japanese voice data.
