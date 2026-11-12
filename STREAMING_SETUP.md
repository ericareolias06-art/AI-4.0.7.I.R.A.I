# Irai incremental AI responses (staged rollout)

## Changes on this branch

- Existing `POST /api/ai/chat` continues to return `{"response":"..."}`.
- New `POST /api/ai/chat/stream` uses the **same login, subscription checks, message limits, prompt and history**.
- With RunPod configured, the new route sends a job to `/run`, checks `/stream/{job_id}` and `/status/{job_id}`, and forwards output as Server-Sent Events (SSE). If the worker doesn't stream, it forwards the final response after completion.
- It attempts `/cancel/{job_id}` when the client disconnects or times out.
- RunPod concurrency is separately controlled with `RUNPOD_MAX_PARALLEL` (default 12) instead of the local Ollama semaphore (default 1). The existing `AI_MAX_INFLIGHT` still applies per Render process.

## Android app integration (implemented in Kotlin, not yet deployed)

`MainActivity.kt` now calls `IraiStreamingChat.read` for spoken replies with an authenticated POST to `/api/ai/chat/stream`. Structured drafting callbacks still use the original full-response endpoint. Android falls back to the legacy endpoint on an HTTP 404 only. Rebuild and test the APK to activate this change.

Set request header `Accept: text/event-stream`; read the HTTP response body as a stream (for example, OkHttp's `ResponseBody.source()` rather than `string()`). Parse SSE frames split by an empty line:

- `event: ready` — job accepted; show the thinking indicator
- `event: delta` with `data: {"text":"..."}` — append to visible text and buffer complete sentences for TTS
- `event: done` — commit the completed assistant turn and flush any remaining TTS text
- `event: error` with `data: {"message":"..."}` — display an error; do not treat a partial answer as a complete turn

Only update Android UI on the main thread. Do not start TTS on every token: group text into complete sentences, enqueue utterances in order, and cancel the reading queue when the user interrupts. Preserve the existing voice, memory, permissions and screen-reading logic. Stop the HTTP call when the user cancels.

For HTTP 4xx/5xx, read the JSON error (subscription required, invalid auth, rate limit, etc.) exactly as the current endpoint does. The stream route is opt-in, so current app versions still work.

## RunPod worker (handler code updated, deployment still required)

The repository's `runpod-worker/handler.py` and root `handler.py` now use a generator handler with `stream: true` calling Ollama's `/api/chat` token stream. The handler uses `return_aggregate_stream=True` to preserve compatibility with `/runsync`; Render accepts aggregated output. The RunPod Python SDK supports generator handlers:

```python
def handler(job):
    messages = job["input"]["messages"]
    # Adapt your deployed model inference iterator here:
    for token in model_generate_iter(messages):
        yield {"token": token}

runpod.serverless.start({"handler": handler})
```

`model_generate_iter` above is a placeholder, **not** a runnable function. Adapt the actual deployed inference library and keep the exact persona/system prompts from Render. Confirm the chunk shape from the chosen handler. The code is updated, but this does **not** automatically build or deploy a new RunPod image. Neither RunPod nor Render has been changed by this pull request.

## Deployment and performance validation

1. Deploy to a non-production Render service first with matching RunPod staging endpoint and API key. Do not place credentials in Git.
2. Test authenticated SSE, invalid session, non-premium account, exhausted message allowance, malformed history, worker timeout and client cancellation.
3. Confirm partial chunks arrive before completion; if only one chunk arrives, update the RunPod worker handler.
4. Load-test 1, 10, 25 and 100 **concurrent requests**. `RUNPOD_MAX_PARALLEL=12` is a starting per-process gate, **not** a promise of 100 concurrent model executions. Scale RunPod max workers and Render serving threads separately from GPU capacity measurements.
5. Check response start latency, complete latency, cold-start delay, GPU utilization, worker spend, Render memory and DB connections.
6. Review accounting: the existing route consumes monthly quota before acquiring a generation slot; rejected/broken requests may count. Fix this before high-volume rollout.

### Rollback

Leave Android clients on `/api/ai/chat`, or revert the branch; do not merge or auto-deploy until the worker and Android changes have been tested.
