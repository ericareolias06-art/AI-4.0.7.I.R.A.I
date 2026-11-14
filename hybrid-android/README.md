# Irai hybrid AI integration (work branch)

This directory adds an **interface-based routing layer**, not a built APK.
The existing Android project layout has not yet been identified, so no
existing application code, assets, build scripts, or voice implementation
were changed.

## What's here
- `IraiHybridEngine.kt`: routes to local inference first when ready, and
  falls back to cloud only **before any output token**. This avoids
  duplicated speech or corrupted response text.
- Streams incremental text to your UI and complete sentences to your TTS.
- Injects the *same* personality and history for either backend.
- Persists only fully completed assistant turns.

## Required adapters for production
1. **Local**: implement `IraiBackend` with Android-compatible
   LiteRT-LM or llama.cpp using a downloaded, verified quantized model.
   Loading a model and model availability checks are **not yet implemented**.
2. **Cloud**: implement `IraiBackend` with your existing authenticated,
   HTTPS streaming server. Do not put cloud provider API keys in the APK.
   Add connect/read timeouts, cancellation, server-side quotas and budgets.
3. **Memory**: implement `IraiMemory` with your app's per-user database.
   Encrypt sensitive data, limit history, and provide deletion controls.
4. **Voice**: implement `IraiVoice` using Irai's existing TTS system.
5. **UI**: forward streamed tokens to your main-thread UI. Speech-to-text
   stays unchanged.
6. **Testing**: check offline local run, cloud-only run, failure before
   first token, failure after first token (must NOT silently retry),
   cancellation, model download/storage, and low-memory devices.

## Warning / limitations
- Kotlin code is a portable starting point, not yet compiled against
  the original Android project.
- The code's callbacks are serialized by a lock; adapters must never
  block the callback with expensive work. Dispatch UI events to main.
- Real performance and RAM compatibility depend on a device benchmark.
- Local models can hallucinate. Cloud fallback should be based on
  explicit capabilities, failure, or user preference—not imagined
  confidence scores.

Branch: `feature/irai-hybrid-ai`; existing `main` intentionally unchanged.
