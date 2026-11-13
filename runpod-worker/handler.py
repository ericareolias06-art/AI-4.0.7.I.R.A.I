import os
import json
import subprocess
import time

import requests
import runpod

HOST = "http://127.0.0.1:11434"
MODEL = os.environ.get("OLLAMA_MODEL", "llama3.2:3b")


def initialize():
    subprocess.Popen(["ollama", "serve"], stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    for _ in range(90):
        try:
            if requests.get(HOST + "/api/tags", timeout=3).ok:
                break
        except requests.RequestException:
            pass
        time.sleep(2)
    else:
        raise RuntimeError("Ollama failed to start")

    # Models are baked into the Docker image. Do not download during a
    # paid Runpod cold start: a 2 GB pull can cost far more than the reply.
    response = requests.get(HOST + "/api/tags", timeout=10)
    response.raise_for_status()
    available = {
        item.get("name") or item.get("model")
        for item in response.json().get("models", [])
        if isinstance(item, dict)
    }
    if MODEL not in available:
        raise RuntimeError(
            "Model " + MODEL + " was not included in the worker image. "
            "Rebuild the Docker image with this model instead of pulling "
            "it on every billed worker startup."
        )


def handler(job):
    payload = job.get("input") or {}
    messages = payload.get("messages")
    if not isinstance(messages, list) or not messages:
        prompt = payload.get("prompt", "")
        if not isinstance(prompt, str) or not prompt.strip():
            return {"error": "Provide messages or a prompt"}
        messages = [{"role": "user", "content": prompt}]
    # Lower sampling randomness to reduce speculative or invented details.
    # The server may supply bounded tuning, but cannot inject arbitrary
    # Ollama options into the worker.
    options = {"num_predict": 220, "temperature": 0.25, "top_p": 0.85}
    supplied_options = payload.get("options")
    if isinstance(supplied_options, dict):
        for name in ("temperature", "top_p"):
            value = supplied_options.get(name)
            if type(value) in (int, float) and 0 <= value <= 1:
                options[name] = float(value)
    # RunPod detects generator handlers and publishes each yield to /stream.
    # Keep one-yield compatibility for non-streaming /runsync clients.
    streaming = payload.get("stream") is True
    try:
        if not streaming:
            response = requests.post(
                HOST + "/api/chat",
                json={"model": MODEL, "messages": messages, "stream": False, "options": options},
                timeout=240,
            )
            response.raise_for_status()
            yield {"response": response.json().get("message", {}).get("content", "")}
            return

        with requests.post(
            HOST + "/api/chat",
            json={"model": MODEL, "messages": messages, "stream": True, "options": options},
            stream=True, timeout=(10, 150),
        ) as response:
            response.raise_for_status()
            # Batch tokens into short phrases to reduce RunPod stream traffic
            # while providing text much earlier than a full-generation reply.
            pending = ""
            for line in response.iter_lines(decode_unicode=True):
                if not line:
                    continue
                item = json.loads(line)
                pending += item.get("message", {}).get("content", "")
                if pending and (
                    len(pending) >= 65
                    or (len(pending) >= 12 and pending.rstrip().endswith((".", "!", "?", "\\n")))
                    or item.get("done")
                ):
                    yield {"delta": pending}
                    pending = ""
                if item.get("done"):
                    break
            if pending:
                yield {"delta": pending}
    except (requests.RequestException, ValueError) as exc:
        yield {"error": "Ollama generation failed: " + str(exc)}


initialize()
runpod.serverless.start({"handler": handler})
