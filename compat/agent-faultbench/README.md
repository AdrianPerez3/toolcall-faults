# JVM compatibility harness

This wrapper reuses the server and the `complete`, `length`, `drop` and `reset` modes of [agent-cutoff-bench](https://github.com/simin-yuan/agent-cutoff-bench) unmodified, fetched at commit `6a575c59fae1bf593554d78b88b22986e72064fa`. That project is licensed under Apache-2.0 and is now incorporated into [agent-faultbench](https://github.com/simin-yuan/agent-faultbench).

Run `sh compat/agent-faultbench/run.sh`. It verifies the vendor commit and source, builds the local images, and runs the JVM adapters and neighbour server in one container with `--network none`. Each cell starts a fresh server and uses an isolated work directory. Both `loop-stream` and `loop-call` are exercised, with three repetitions of each mode. No provider credentials are used.

The tool writes only the basename supplied by the synthetic server. The harness reads `report.txt` back independently, records its bytes, newline count, final line and SHA-256, and checks that every complete control wrote the complete report. Results use the neighbour's record fields, with a `streaming` flag and one `version_<groupId:artifactId>` field per watched Maven coordinate; the full coordinate distinguishes Jackson artifacts with the same name.

Raw observations and the table are generated locally at `results/runs.jsonl` and `rows.md`, with per-cell logs retained under the ignored local directory. Generated observations are excluded from Git pending the applicable disclosure steps. A failed run leaves its evidence in place and does not replace the last completed output.

Cite the archived work: [agent-cutoff-bench](https://doi.org/10.5281/zenodo.23808696) and [agent-faultbench](https://doi.org/10.5281/zenodo.24143645). The server is fetched into an ignored vendor directory and copied only into the local compatibility image, together with its LICENSE and NOTICE. No neighbour source is included in this repository.
