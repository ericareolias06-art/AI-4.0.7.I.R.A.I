import os
import subprocess
import time

import requests
import runpod

HOST = "http://127.0.0.1:11434"
MODEL = os.environ.get("OLLAMA_MODEL", "llama3.1:8b")


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


def handler(job):
    payload = job.get("input") or {}
    messages = payload.get("messages")
    if not isinstance(messages, list) or not messages:
        prompt = payload.get("prompt", "")
        if not isinstance(prompt, str) or not prompt.strip():
            return {"error": "Provide messages or a prompt"}
        messages = [{"role": "user", "content": prompt}]
    try:
        response = requests.post(
            HOST + "/api/chat",
            json={"model": MODEL, "messages": messages, "stream": False},
            timeout=240,
        )
        response.raise_for_status()
        result = response.json()
        return {"response": result.get("message", {}).get("content", "")}
    except requests.RequestException as exc:
        return {"error": "Ollama request failed: " + str(exc)}


initialize()
runpod.serverless.start({"handler": handler})
