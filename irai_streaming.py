"""RunPod queue-based streaming bridge for Irai's Flask backend.

The RunPod worker must yield incremental outputs for token-level streaming.
Non-streaming workers remain compatible through the final /status response.
"""
import json
import re
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError


def _request_json(url, key, payload=None, timeout=20):
    headers = {"Authorization": "Bearer " + key, "Accept": "application/json"}
    if payload is not None:
        headers["Content-Type"] = "application/json"
    req = Request(
        url,
        data=json.dumps(payload).encode("utf-8") if payload is not None else None,
        headers=headers,
        method="POST" if payload is not None else "GET",
    )
    with urlopen(req, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def _text_from_output(output):
    if isinstance(output, str):
        return output
    if isinstance(output, dict):
        for key in ("response", "token", "delta", "text"):
            value = output.get(key)
            if isinstance(value, str):
                return value
            if isinstance(value, list):
                return "".join(item for item in value if isinstance(item, str))
    return ""


def _event(event, **fields):
    return "event: " + event + "\ndata: " + json.dumps(fields, ensure_ascii=False) + "\n\n"


def stream_runpod(messages, endpoint, api_key, timeout=120, poll_interval=0.4):
    """Yield SSE frames; never expose the API key or raw provider errors."""
    base = "https://api.runpod.ai/v2/" + endpoint
    job_id = None
    finished = False
    yielded = False
    seen = 0
    started = time.monotonic()
    try:
        submitted = _request_json(base + "/run", api_key, {"input": {"messages": messages, "stream": True}})
        job_id = submitted.get("id") if isinstance(submitted, dict) else None
        if not isinstance(job_id, str) or not re.fullmatch(r"[a-zA-Z0-9-]{1,100}", job_id):
            raise ValueError("RunPod did not return a valid job id")
        yield _event("ready")
        while time.monotonic() - started < timeout:
            chunks = _request_json(base + "/stream/" + job_id, api_key)
            # RunPod's queue endpoint returns an array of output records.
            if isinstance(chunks, list):
                for item in chunks[seen:]:
                    part = _text_from_output(item.get("output") if isinstance(item, dict) else item)
                    if isinstance(item, dict) and isinstance(item.get("output"), dict) and item["output"].get("error"):
                        finished = True
                        yield _event("error", message="Irai could not finish that response.")
                        return
                    if part:
                        yielded = True
                        yield _event("delta", text=part)
                seen = len(chunks)
            status = _request_json(base + "/status/" + job_id, api_key)
            state = status.get("status") if isinstance(status, dict) else None
            if state == "COMPLETED":
                if not yielded:
                    final = _text_from_output(status.get("output"))
                    if final:
                        yield _event("delta", text=final)
                        yielded = True
                finished = True
                if yielded:
                    yield _event("done")
                else:
                    yield _event("error", message="Irai returned an empty response.")
                return
            if state in ("FAILED", "CANCELLED", "TIMED_OUT"):
                finished = True
                yield _event("error", message="Irai could not finish that response.")
                return
            time.sleep(poll_interval)
        yield _event("error", message="Irai took too long to respond.")
    except (HTTPError, URLError, OSError, ValueError, TypeError, KeyError):
        yield _event("error", message="Irai's AI connection is temporarily unavailable.")
    finally:
        # Cancel unnecessary GPU work on disconnect or timeout.
        if job_id and not finished:
            try:
                request = Request(
                    base + "/cancel/" + job_id,
                    data=b"",
                    headers={"Authorization": "Bearer " + api_key},
                    method="POST",
                )
                with urlopen(request, timeout=5):
                    pass
            except (HTTPError, URLError, OSError):
                pass
