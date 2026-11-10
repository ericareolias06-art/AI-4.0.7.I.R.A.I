"""
Gunicorn configuration for the Irai Tec Flask API.

One process keeps the in-process admission/rate limits coherent.
Threads allow many authenticated users to wait on bounded Ollama capacity.
"""
import os

def _int_env(name, default, minimum, maximum):
    try:
        value = int(os.environ.get(name, str(default)))
    except (TypeError, ValueError):
        value = default
    return max(minimum, min(value, maximum))

workers = 1
worker_class = "gthread"
threads = _int_env("WEB_CONCURRENCY_THREADS", 80, 8, 100)
timeout = 180
graceful_timeout = 30
keepalive = 5
max_requests = 1000
max_requests_jitter = 100
