FROM tcf-ref:jdk21
RUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*
COPY server.py LICENSE NOTICE /vendor/agent-cutoff-bench/
