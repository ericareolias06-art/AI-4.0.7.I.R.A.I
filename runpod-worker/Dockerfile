FROM ollama/ollama:latest

RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip curl && rm -rf /var/lib/apt/lists/*
RUN python3 -m pip install --break-system-packages --no-cache-dir runpod requests

ENV OLLAMA_HOST=127.0.0.1:11434
ENV OLLAMA_MODEL=llama3.2:3b
COPY handler.py /worker/handler.py
WORKDIR /worker
CMD ["python3", "-u", "handler.py"]
