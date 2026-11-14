# Irai Hybrid Android — implementation branch

Current status: **integrated source, not yet verified with a successful APK build or on-device benchmark.**

## Actual project changes

- `irai-android/app/build.gradle.kts`: adds LiteRT-LM 0.18.0 and coroutines.
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
- Local model import currently relies on the user choosing a trusted `.litertlm` file. Before wide release add a vetted model manifest, source URL, hash validation, license display, resumable download, and model-specific compatibility tests.
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
