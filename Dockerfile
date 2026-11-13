FROM ollama/ollama:latest

RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip curl && rm -rf /var/lib/apt/lists/*
RUN python3 -m pip install --break-system-packages --no-cache-dir runpod requests

ENV OLLAMA_HOST=127.0.0.1:11434
ENV OLLAMA_MODEL=llama3.2:3b

# Fetch the ~2 GB model once at IMAGE BUILD TIME, not on every billed
# Serverless cold start. Runpod stages Docker image layers before starting
# the GPU worker, reducing paid startup time.
RUN set -eu; \
    ollama serve >/tmp/ollama-build.log 2>&1 & \
    server_pid=$!; \
    trap 'kill "$server_pid" 2>/dev/null || true' EXIT; \
    ready=0; \
    for attempt in $(seq 1 90); do \
        if curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then ready=1; break; fi; \
        sleep 2; \
    done; \
    if [ "$ready" != 1 ]; then cat /tmp/ollama-build.log; exit 1; fi; \
    ollama pull llama3.2:3b; \
    kill "$server_pid" 2>/dev/null || true; \
    wait "$server_pid" 2>/dev/null || true
COPY handler.py /worker/handler.py
WORKDIR /worker
ENTRYPOINT ["python3", "-u", "/worker/handler.py"]
