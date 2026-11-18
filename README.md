# toolcall-faults

what JVM LLM clients do when a streamed tool call arrives damaged

## In plain words

Applications built on large language models let the model call functions in the application's own code. Such a request is a *tool call*: the model names a function and supplies its arguments as JSON. With streaming, the call arrives in many small pieces.

Sometimes the stream is damaged before the call is complete. The token limit cuts the arguments in the middle of a string. The connection closes right after the function name. The closing events never arrive. An error appears halfway through.

The client library then has to decide what to do with a call it received only in part. This project records what it does:

- Did the application's function run?
- With which arguments?
- What did the calling code see: an error, a finish reason, or nothing?
- Did the client stop within its configured timeout?

An example. A stream names the tool `record_note` and then ends with the reason "token limit reached", before one byte of arguments has arrived. A client can refuse the call and tell the caller. It can drop the call without a signal. Or it can run `record_note` with arguments the model never sent. These are three different observations, and an application behaves differently under each.

The clients measured are Spring AI, LangChain4j, openai-java and anthropic-sdk-java, at pinned versions.

These are observations of behaviour on replayed bytes; not a ranking.

## How it works

1. A **fixture** holds the exact bytes of one provider response, damage included. Most fixtures are synthetic, constructed for this suite; a few are recordings taken from other projects.
2. A small **replay server** sends those bytes over HTTP on the local machine. No model and no provider takes part, and no API key is needed.
3. An **adapter** is a short program that calls one real client library the way an application would, with a tool whose body writes down the arguments it receives. Every run is a fresh process in a container with the network switched off and fake credentials. The client is given a timeout of 3 seconds, and a watchdog ends a run after 12.
4. The **oracle** compares what the tool received with what the bytes carried, and gives the run one **category**. Every cell is run three times.
5. A **judgement**, kept apart from the observation, says whether provider documentation supports that outcome. Where the documentation is silent or disputed, the outcome is recorded without a verdict.

## What a result looks like

Each run produces one row. This one is from `results/spring-ai/2.0.2.jsonl`, shortened:

```json
{
  "cell": "spring-ai@2.0.2|spring-ai-anthropic|loop-call|anthropic/finish.stop-with-tool-call@nonstream|record_note|jdk21|okhttp",
  "rep": 1,
  "category": "EXECUTED_AS_SENT",
  "invocations": [{"seq": 1, "name": "record_note", "args": {"priority": 3, "title": "Quarterly report", "body": "Send the Q3 numbers to finance before Friday"}}],
  "attributes": {"request_count": 2, "invocation_count": 1, "terminal_signals": ["complete"], "caller_finish_reasons": ["end_turn"], "timeout_fired": false, "watchdog_killed": false}
}
```

The `cell` key reads, from left to right: library and version, module, surface, wire format and scenario, tool, JDK, HTTP client. `invocations` is what the tool body received. The `category` is one of:

| Category | Meaning |
|---|---|
| `EXECUTED_AS_SENT` | The tool ran with the arguments as sent. |
| `EXECUTED_ALTERED` | The tool ran with other arguments than a complete call carried. |
| `SURFACED` | The tool did not run, and the caller can tell. |
| `DROPPED` | The tool did not run, and the caller cannot tell. |
| `FED_BACK_HIDDEN` | The error was sent back to the model and hidden from the caller. |
| `ERROR_TYPED` | The client raised an error of its own type. |
| `ERROR_UNTYPED` | The client raised an error of a generic type. |
| `HANG` | No terminal signal arrived before the watchdog ended the run. |
| `UNSUPPORTED` | The client cannot express the cell. |
| `HARNESS` | The run itself was invalid. Such a run is never kept as an observation. |

## Status

Pre-release. No report pages are published.

- `results/` holds observation rows for cells that are not judged, or whose behaviour is already reported upstream, by the author or by someone else. [results/upstream.json](results/upstream.json) lists those threads.
- Where a cell shows an outcome without documentary support that has not been reported upstream, its rows and the rows of the other cells of the same target version, module and scenario are withheld until the report is filed.

## Try it

You need a JDK and Docker. Maven compiles Java 17 source; the reference containers exercise JDK 17, 21 and 25. The wrapper downloads pinned build dependencies when needed. Container preparation downloads adapter dependencies; the measured processes run with `--network none`, fake credentials and a cleared environment.

```sh
# Build and unit-test the project's own code.
./mvnw verify
# Check the fixtures, the manifests and the published rows.
./tcf lint
# Run one cell three times in the JDK 21 container.
./tcf --container 21 cell spring-ai@2.0.2 tools.single-call --wire openai-chat --module spring-ai-openai --surface loop-stream --tools record_note --reps 3
# Check the replay server, the controls and the anchors.
./tcf --container 21 selftest
```

Replace the scenario with the fixture's scenario ID to reproduce a particular cell. `tcf cell` prints the fixture, resolved versions, category and received arguments. A loop surface records tool execution inside the tool body; a model surface records the assembled response without running a tool. The latter is record-only in this release.

`./tcf --container 21 run` runs the selected matrix; `./tcf check` compares observations with reviewed baselines; `./tcf report` generates Markdown. An unreviewed cell makes `check` fail. Observations of behaviours that are not reported upstream yet, with their baselines, anchors and the frozen predictions, remain private; `expectations/predictions.commitment.json` holds the hash of the predictions. A public checkout runs the published cells with `./tcf --container 21 run --jdk 21 --published`; a full run may report `NEW` for the others. Do not suppress that check to obtain a green run.

## The scenarios

A scenario is one kind of response: a kind of damage, or an undamaged control. Each has a fixture per wire format.

| Scenario | The response |
|---|---|
| `tools.single-call` | One complete call, normal finish |
| `tools.zero-arg-call` | Complete call to a zero-argument tool |
| `tools.parallel-sequential` | Two complete calls with sequential argument fragments |
| `tools.parallel-interleaved` | Two complete calls with alternating argument fragments |
| `tools.concatenated-args` | Empty object followed by the actual argument object |
| `truncation.name-only` | Call started, zero argument bytes; limit reason |
| `truncation.args-cut-mid-string` | Arguments stop inside a string value; limit reason |
| `truncation.complete-args-under-limit` | Complete arguments under a limit reason |
| `truncation.second-call-cut` | First call complete, second cut inside a string |
| `finish.stop-with-tool-call` | Complete call under a natural stop reason |
| `finish.unknown-value-with-tool-call` | Complete call under an unknown finish reason |
| `stream.missing-finish` | Complete call, then a clean HTTP end: no finish reason and no terminator |
| `stream.final-event-unterminated` | Final finish event without its terminating blank line |
| `stream.abort-after-call-start` | Call started with no argument bytes, then connection closed |
| `stream.error-after-call-start` | Call started, then an in-band error |
| `stream.stall-mid-args` | Partial arguments, then silence with the connection open |

## Scope

| Axis | Included |
|---|---|
| Clients | Spring AI, LangChain4j, openai-java, anthropic-sdk-java at pinned versions |
| Wires | OpenAI Chat Completions and Anthropic Messages |
| Damage | Token limits, incomplete arguments, absent closing events, contradictory finish reasons, parallel deltas, concatenated arguments, transport interruption and in-band errors |
| Controls | Normal calls, streaming and blocking surfaces, reference/primitive/zero-argument tools, historical pins and the configured next Spring AI release |
| Environment | HTTP/1.1 on loopback in Linux containers; JDK and supported HTTP-client axes |

[matrix.json](matrix.json), adapter manifests and scenario files define the exact selection. A declared control failure keeps its observed result and upstream basis. Non-control cells on that module and surface are listed as **not run** while the declaration applies; they return automatically when it no longer applies. See [the decisions](docs/decisions.md).

## Non-goals

| Outside the first release | Reason |
|---|---|
| Python and TypeScript targets | Existing projects already measure these clients. |
| Model quality, prompts or frequency of truncation | Replayed bytes do not measure a live model. |
| Gateway and proxy targets | This measures client handling of responses. |
| A league table, scores or pass rates | Reproductions and individual observations are the useful output. |
| A general mock server | The server only replays fixed bytes. |
| Attack scenarios, prompt injection or hostile servers | Scope is correctness and data integrity. |
| HTTP/2, TLS, compression, WebSocket or gRPC | Deferred transport work. |
| Responses, Gemini, Bedrock or Ollama wires; additional targets | Deferred scope. |
| A published package/image, automated standalone exporter or JavaScript site | Unneeded for the first release. |
| Reference adapters and deliberately misleading adapters | Deferred; the first release exercises the named real clients. |

This is not [`toolcall-conformance` on PyPI](https://pypi.org/project/toolcall-conformance/), a server-side checker.

## Reading the evidence

Fixtures are synthetic unless their `tier` says otherwise. A synthetic replay demonstrates client behaviour on those bytes; it does not prove that a live provider emits them. Recorded fixtures retain provenance and licences; derived fixtures name their source and transformation. Shape evidence and its limits belong to each scenario.

| Label | Meaning |
|---|---|
| observed | Measured in the cited run; repeat count and environment travel with the result. |
| predicted | Inferred from released source and not yet measured. |
| documented | Stated in provider or project documentation. |
| inference | Our interpretation of the evidence. |
| unverified | Not confirmed by the available evidence. |

A category describes what happened. A judgement records whether a cited provider contract supports it; disputed or undocumented behaviour may be record-only. The result records execution, callbacks and caller-visible signals separately. It does not infer execution merely from a completed model response. [Dispute an observation or judgement](docs/disputing-a-cell.md) with the cell key and evidence.

## Glossary

| Term | Meaning |
|---|---|
| Tool call / tool body | A model's request to invoke a function / the application's function itself. |
| Stream / delta / SSE | A response arriving in pieces / one piece / server-sent event framing. |
| Finish or stop reason | The provider's stated reason output stopped. |
| Wire format | A provider's encoding of a response. |
| Fixture / scenario | Exact response bytes / a kind of damage or control with fixtures per wire. |
| Target / adapter | A client library at a version / the small program that calls it. |
| Surface | The target API used: loop or model, streaming or blocking. |
| Cell | A target, version, module, surface, wire, scenario, tool and environment combination. |

## Relationship to earlier work

Based on the projects and catalogues reviewed in November 2026, to our knowledge this is the first measurement of how JVM LLM frameworks and SDKs (Spring AI, LangChain4j, openai-java and anthropic-sdk-java) act on damaged streamed tool calls, using the cut-off modes of agent-cutoff-bench and the replay method of wiretruth, extended with tool-call fault classes those catalogues do not cover, and judged on loop surfaces by whether the tool body ran. This claim is limited to the reviewed catalogues; it is not an exhaustive claim about all prior testing. Non-streaming controls, historical pins and testing truncated calls already exist elsewhere. So does a measurement of Spring AI and LangChain4j on cut and failed text streams, without tool calls ([Klymenko, arXiv 2610.25480](https://arxiv.org/abs/2610.25480)).

The project builds on and acknowledges:

- [wiretruth](https://github.com/kartsan03/wiretruth) for recorded replay fixtures and derivation; [verified derive recipes](docs/export-wiretruth.md) describe the conversion boundaries.
- [agent-cutoff-bench](https://github.com/simin-yuan/agent-cutoff-bench) ([raw records](https://doi.org/10.5281/zenodo.23808696)) and [agent-faultbench](https://github.com/simin-yuan/agent-faultbench) ([archive](https://doi.org/10.5281/zenodo.24143645)) for cut-off modes and the file side-effect oracle; see the [JVM compatibility wrapper](compat/agent-faultbench/README.md).
- [LangChain #41285](https://github.com/langchain-ai/langchain/issues/41285), an upstream report of lenient parsing of truncated tool-call arguments, which itself names the earlier #39275; [Semantic Kernel #14723](https://github.com/microsoft/semantic-kernel/issues/14723) for live observations of the name-only shape on its reported provider paths.
- [aimock](https://github.com/CopilotKit/aimock) and [Mastra's scenario suite](https://github.com/mastra-ai/mastra/tree/main/packages/core/src/loop/test-utils/aimock/scenarios), and [rig's recorded cassettes](https://github.com/0xPlaygrounds/rig/tree/0748a9d53616fdd1556b6b7b8e8df8008312d877/crates/rig-cassette/fixtures/cassettes), for existing wire-level testing and capture evidence.
- [isloss](https://arxiv.org/abs/2611.15543), [AgentChaos](https://arxiv.org/abs/2608.06790), [the cross-framework study by Zhang, Zhang and Tan](https://arxiv.org/abs/2604.08906), [MCP-Injector](https://arxiv.org/abs/2610.32555), [FailureAtlas](https://arxiv.org/abs/2607.17525), [LLM-Rosetta](https://arxiv.org/abs/2604.09360) and [Klymenko's benchmark of call handling in Spring AI and LangChain4j](https://arxiv.org/abs/2610.25480) for related work on preservation, fault injection, failure taxonomies and framework behaviour.
- [openresponses-client-conformance](https://github.com/farajhadi/openresponses-client-conformance), [LLMock](https://github.com/JulienRabault/LLMock) and [canitoolcall](https://github.com/redd34/canitoolcall) for adjacent client and engine testing.

## Disclosure and maintenance

The author also reports to upstream projects behaviours of the kind this suite measures, which is a potential conflict of interest. A local observation does not establish an upstream-confirmed issue. Confirmation and the maintainers' position are recorded separately. Unfiled result rows are withheld; judged report cells wait for a maintainer response or the configured 14-day reply window after filing.

This project is being built with AI assistance for research, design documents and code. Automated checks are recorded as such; upstream reproductions and reports are prepared with AI assistance and reviewed by the author before submission.

Report pages carry a last-successful-run stamp. Treat a stamp older than 14 days as stale. A weekly run replays the published cells: [![Weekly replay](https://github.com/AdrianPerez3/toolcall-faults/actions/workflows/weekly.yml/badge.svg)](https://github.com/AdrianPerez3/toolcall-faults/actions/workflows/weekly.yml). It uploads the stamp for maintainer review; it does not push commits. If maintenance stops, the repository will be archived with a dated notice on the README's first line and the adapters offered upstream. Historical version results are retained.

See [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md), [CITATION.cff](CITATION.cff) and [NOTICE](NOTICE). Code and our own fixtures use Apache-2.0; imported fixtures retain their named licences. Provider and framework names imply no endorsement.
