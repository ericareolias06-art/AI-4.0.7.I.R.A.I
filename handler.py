import os
import subprocess
import time
import json

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

    response = requests.post(
        HOST + "/api/pull", json={"name": MODEL, "stream": False}, timeout=1800
    )
    response.raise_for_status()


def _input_messages(payload):
    messages = payload.get("messages")
    if isinstance(messages, list) and messages:
        return messages
    prompt = payload.get("prompt", "")
    if isinstance(prompt, str) and prompt.strip():
        return [{"role": "user", "content": prompt}]
    return None


def _ollama_payload(messages, streaming):
    return {
        "model": MODEL,
        "messages": messages,
        "stream": streaming,
        "options": {"num_predict": 220},
    }


def _stream_chat(messages):
    # Generator is used ONLY for opt-in streaming jobs; older /runsync
    # requests still get the original {"response": "..."} contract.
    try:
        with requests.post(
            HOST + "/api/chat",
            json=_ollama_payload(messages, True),
            stream=True,
            timeout=(15, 240),
        ) as response:
            response.raise_for_status()
            for line in response.iter_lines(decode_unicode=True):
                if not line:
                    continue
                chunk = json.loads(line)
                if chunk.get("error"):
                    raise RuntimeError("Ollama generation error")
                token = (chunk.get("message") or {}).get("content", "")
                if token:
                    yield {"token": token}
    except (requests.RequestException, ValueError, RuntimeError):
        # Avoid leaking server internals into Android responses.
        yield {"error": "Irai could not complete the response."}


def handler(job):
    # RunPod checks whether the handler FUNCTION is a generator, not whether
    # its return value happens to be one.
    payload = job.get("input") or {}
    messages = _input_messages(payload)
    if messages is None:
        yield {"error": "Provide messages or a prompt"}
        return
    if payload.get("stream") is True:
        yield from _stream_chat(messages)
        return
    try:
        response = requests.post(
            HOST + "/api/chat",
            json=_ollama_payload(messages, False),
            timeout=240,
        )
        response.raise_for_status()
        result = response.json()
        yield {"response": result.get("message", {}).get("content", "")}
    except requests.RequestException:
        yield {"error": "Ollama request failed"}


initialize()
runpod.serverless.start({"handler": handler, "return_aggregate_stream": True})
