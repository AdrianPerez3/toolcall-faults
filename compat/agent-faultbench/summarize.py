"""Check the controls and render each repeated compatibility cell."""
import json
import sys
from collections import defaultdict
from pathlib import Path

rows = [json.loads(line) for line in Path(sys.argv[1]).read_text().splitlines()]
expected = {(target, "chat-" + mode, streaming, run) for target in ("spring-ai", "langchain4j")
            for mode in ("complete", "length", "drop", "reset") for streaming in (True, False) for run in (1, 2, 3)}
actual = [(row["framework"], row["variant"], row["streaming"], row["run"]) for row in rows]
if len(actual) != len(expected) or set(actual) != expected:
    raise SystemExit("Missing or duplicate compatibility run")
for row in rows:
    if row["variant"] == "chat-complete" and not (row["tool_called"] and row["disk"]["complete"]):
        raise SystemExit("Compatibility control did not write the complete file")
groups = defaultdict(list)
for row in rows:
    groups[row["framework"], row["variant"], row["streaming"]].append(row)
lines = ["# JVM compatibility observations", "", "Unmodified pinned agent-cutoff-bench server; values list repetitions 1, 2, 3 in order.", "",
         "| Target | Mode | Streaming | tool_called | disk.lines | disk.bytes | requests | error |",
         "|---|---|---|---|---|---|---|---|"]
def display(values):
    unique = list(dict.fromkeys(str(value) for value in values))
    value = unique[0] if len(unique) == 1 else "; ".join(str(value) for value in values)
    return value.replace("|", "\\|").replace("\n", " ")
for (target, mode, streaming), runs in sorted(groups.items()):
    runs.sort(key=lambda row: row["run"])
    fields = [[row["tool_called"] for row in runs], [row["disk"].get("lines", 0) for row in runs],
              [row["disk"].get("bytes", 0) for row in runs], [row["requests"] for row in runs],
              ["none" if row["error"] is None else row["error"] for row in runs]]
    lines.append("| " + " | ".join([target, mode, str(streaming)] + [display(v) for v in fields]) + " |")
Path(sys.argv[2]).write_text("\n".join(lines) + "\n")
