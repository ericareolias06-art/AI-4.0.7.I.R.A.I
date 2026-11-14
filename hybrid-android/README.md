# Irai Hybrid Android — implementation branch

Current status: **integrated source, not yet verified with a successful APK build or on-device benchmark.**

## Actual project changes

- `irai-android/app/build.gradle.kts`: adds LiteRT-LM 0.16.1 and coroutines.
- `irai-android/app/src/main/java/com/iraitec/IraiLocalInference.kt`: CPU inference; dedicated single-thread worker; engine re-use; cleanup.
- `irai-android/app/src/main/java/com/iraitec/MainActivity.kt`: integrates local-first inference into existing `sendMessageToIrai`, preserves the existing Render/RunPod SSE fallback, TTS chunk playback, existing recent chat history and session cookies. Adds Settings > AI Mode and Settings > Install On-Device AI Model.
- `hybrid-android/IraiHybridEngine.kt`: earlier independent interface prototype, not active in the shipped app. The real integration is in `irai-android/`.

## How to try it

1. Build `irai-android` from branch `feature/irai-hybrid-ai` with Android SDK 36, JDK 17, Gradle wrapper, and network access to Google Maven.
2. Launch the app and sign in as normal. With no local model installed, it continues using the existing cloud endpoint.
3. Download a compatible `.litertlm` model to the phone from a trusted model publisher. Check the model license and compatibility, and leave sufficient free storage.
4. Open **Settings > Install On-Device AI Model**, select the file in the Android file picker. Irai copies it to app-private storage.
5. In **Settings > AI Mode**, pick **Local first (cloud fallback)** or **Cloud only**.
6. Speak to Irai. Successful local output uses the existing voice pipeline; if the local engine fails *before output starts*, the existing cloud request runs automatically.

## Known limitations and required production checks

- This branch has not been compiled or bench-tested here. Do **not** merge/deploy without `./gradlew assembleDebug` and physical-device testing.
- LiteRT-LM model format and Android chipset support vary; local inference on 4 GB RAM phones may be much slower than the cloud.
- Local model import is serialized behind active inference, but model format compatibility remains unverified until first generation. Currently relies on the user choosing a trusted `.litertlm` file. Before wide release add a vetted model manifest, source URL, hash validation, license display, resumable download, and model-specific compatibility tests.
- Voice recognition still uses the app's previous recognition mechanism. Chat replies still use the existing TTS service; fully offline speech depends on installed Android speech engines.
- Existing recent chat history is passed into the local prompt. This is not a new durable local conversation database; Irai's existing memory endpoints and vault are preserved.
- The cloud route still consumes account allowance and RunPod resources; local turns do not call the AI endpoint when fully successful. Local generation is not guaranteed to give high-quality responses.
- The settings screen has a **Test AI Connection** button which explicitly uses the cloud; this remains intentional.
- The current on-device engine uses CPU only for compatibility. GPU tuning requires device-specific runtime validation.
- Local failure after tokens already reached TTS **does not** trigger a cloud replay, preventing duplicate or contradictory speech.
- Protect long model imports from lifecycle interruption and run performance testing before offering this to 100 users.

## Build from local checkout

```bash
cd irai-android
./gradlew assembleDebug
```

Debug APK: `irai-android/app/build/outputs/apk/debug/app-debug.apk` after a **successful** build.

For production, deploy this behind a feature flag, measure cold start, tokens per second, time to first token, memory peaks, crashes, fallback rates, and RunPod costs.

Reference: https://developers.google.com/edge/litert-lm/android

## Candidate model for LiteRT-LM 0.16.1

The current Android build uses LiteRT-LM **0.16.1**, because Android Gradle Plugin 9.0.1 embeds a Kotlin 2.2 compiler. Recent LiteRT-LM 0.18/0.19 releases require newer Kotlin metadata, and their latest Qwen3 model exports are **not** appropriate for this runtime.

For manual testing, the publisher reports LiteRT-LM 0.16.1 Android compatibility for [LFM2.5-1.2B-Instruct](https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct). Consider the CPU-compatible `LFM2.5-1.2B-Instruct_int4.litertlm` (about 736 MB) and review its **LFM Open License v1.0** before distribution. This is a candidate, not a tested recommendation for Irai's specific phone. It may run poorly on 4 GB RAM devices. Download the model file on the phone, then import via Irai Settings.

Debug APK is from the successful run: https://github.com/ericareolias06-art/AI-4.0.7.I.R.A.I/actions/runs/43614379414 (Artifacts > IraiTec-hybrid-debug). Because debug APK signing may differ from existing installs, test on a spare device or back up data before replacing an installed version.

## Final release audit

GitHub Actions successfully built the first debug APK, but a build does not validate model behavior or response latency. The app **does not contain any model weights** and cannot run local AI until the user installs a compatible model separately. It does **not** provide offline cloud features, account login, online facts, or guaranteed offline speech recognition. An Android phone test is required. Debug builds may use a different signing certificate than current installed Irai; backing up app data is essential before replacing an installation.

The local personality prompt now mirrors the cloud identity, response tone and accuracy guidance. Cloud behavior remains unchanged. The existing history is in-memory recent history and is not a new long-term encrypted memory store. Never claim full memory persistence from this change.
