# Deriving related wiretruth fixtures

These commands express the listed scenario shapes using [wiretruth 0.4.0](https://pypi.org/project/wiretruth/0.4.0/) and its suite at commit [`b65af93e0186d1a9d8076f85ea4e6614a9102faf`](https://github.com/kartsan03/wiretruth/tree/b65af93e0186d1a9d8076f85ea4e6614a9102faf). They use wiretruth's own sources; no toolcall-faults synthetic bytes are exported. The commands were verified on 2026-11-17 in a Python 3.12 container with `--network none`: each exited zero, wrote a derived fixture with the named transform, and preserved the intended complete, partial or missing-finish shape.

The pinned OpenAI Chat sources are `recorded`. Their Anthropic counterparts are `documented`, not recordings. Both tiers are accepted by wiretruth's [derivation contract](https://github.com/kartsan03/wiretruth/blob/b65af93e0186d1a9d8076f85ea4e6614a9102faf/docs/derived-fixtures.md). A derived source cannot be used for another derivation. The output fixtures describe related shapes, not byte-identical copies of this suite's fixtures or evidence that a live provider emitted the transformed response.

## Running the commands

Use a disposable checkout of the pinned wiretruth commit. This builds the tool and copies its suite into the image; downloads happen only at build time:

```sh
git clone https://github.com/kartsan03/wiretruth wiretruth-export
cd wiretruth-export
git checkout b65af93e0186d1a9d8076f85ea4e6614a9102faf
docker build -t tcf-wiretruth-derive:0.4.0 -f - . <<'DOCKERFILE'
FROM python:3.12-slim@sha256:5e2bdb1b0d542d5039cffb8181c65930093949194a65c365dfb1b0fe13a4fa5b
RUN python -m pip install --no-cache-dir wiretruth==0.4.0
COPY suite /work/wiretruth/suite
WORKDIR /work/wiretruth
DOCKERFILE
```

Run the installed command in that directory, with networking disabled at runtime. The commands below create scratch fixture IDs prefixed with `tcf-`; the source fixture files remain unchanged. Keep the container if you want to inspect its output, or copy the generated files out before removing it.

For example, with the prepared image named `tcf-wiretruth-derive:0.4.0`:

```sh
docker run --rm --network none tcf-wiretruth-derive:0.4.0 wiretruth derive --from openai-chat/tools.parallel-calls-stream --transform strip_finish --out openai-chat/stream.tcf-missing-finish
```

Each command writes `suite/fixtures/<output-id>.json`. Repeating a command in the same checkout requires `--replace`, which only replaces an existing derived fixture. The derive command validates the fixture, but does not create a scenario document. Add the corresponding scenario and requirements before expecting `wiretruth lint` to pass for the expanded suite.

## Verified commands

| Scenario | Wire | Command (inside the container) |
|---|---|---|
| S04 truncation.complete-args-under-limit | openai-chat | `wiretruth derive --from openai-chat/tools.parallel-calls-stream --transform replace_finish_value --out openai-chat/truncation.tcf-complete-args-under-limit --args '{"value":"length"}'` |
| S06 stream.missing-finish | openai-chat | `wiretruth derive --from openai-chat/tools.parallel-calls-stream --transform strip_finish --out openai-chat/stream.tcf-missing-finish` |
| S07 @fin stream.abort-after-call-start | openai-chat | `wiretruth derive --from openai-chat/truncation.tool-call-args-stream --transform cut_stream_after --out openai-chat/stream.tcf-abort-after-call-start --args '{"n":1,"end":"abort"}'` |
| S09 finish.stop-with-tool-call | openai-chat | `wiretruth derive --from openai-chat/tools.parallel-calls-stream --transform replace_finish_value --out openai-chat/finish.tcf-stop-with-tool-call --args '{"value":"stop"}'` |
| S10 finish.unknown-value-with-tool-call | openai-chat | `wiretruth derive --from openai-chat/tools.parallel-calls-stream --transform replace_finish_value --out openai-chat/finish.tcf-unknown-value-with-tool-call --args '{"value":"insufficient_system_resource"}'` |
| S15 stream.error-after-call-start | openai-chat | `wiretruth derive --from openai-chat/truncation.tool-call-args-stream --transform inject_error_event --out openai-chat/stream.tcf-error-after-call-start --args '{"after":2,"error":{"error":{"type":"server_error","message":"Replay error","code":"server_error"}}}'` |
| S04 truncation.complete-args-under-limit | anthropic | `wiretruth derive --from anthropic/tools.parallel-calls-stream --transform replace_finish_value --out anthropic/truncation.tcf-complete-args-under-limit --args '{"value":"max_tokens"}'` |
| S06 stream.missing-finish | anthropic | `wiretruth derive --from anthropic/tools.parallel-calls-stream --transform strip_finish --out anthropic/stream.tcf-missing-finish` |
| S07 @fin stream.abort-after-call-start | anthropic | `wiretruth derive --from anthropic/truncation.tool-call-args-stream --transform cut_stream_after --out anthropic/stream.tcf-abort-after-call-start --args '{"n":7,"end":"abort"}'` |
| S09 finish.stop-with-tool-call | anthropic | `wiretruth derive --from anthropic/tools.parallel-calls-stream --transform replace_finish_value --out anthropic/finish.tcf-stop-with-tool-call --args '{"value":"end_turn"}'` |
| S10 finish.unknown-value-with-tool-call | anthropic | `wiretruth derive --from anthropic/tools.parallel-calls-stream --transform replace_finish_value --out anthropic/finish.tcf-unknown-value-with-tool-call --args '{"value":"tcf_unknown_reason"}'` |
| S15 stream.error-after-call-start | anthropic | `wiretruth derive --from anthropic/truncation.tool-call-args-stream --transform inject_error_event --out anthropic/stream.tcf-error-after-call-start --args '{"after":8,"error":{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}}'` |

`strip_finish` takes no arguments. `cut_stream_after` takes `n` and `end`; `inject_error_event` takes `after` and the complete provider error object. Counts select stored events, not bytes. The selected OpenAI cut retains the tool identity event; the Anthropic cut retains its seventh event, which starts the tool after a thinking block. Error injection retains the first partial argument event on each wire. These cut points are specific to the pinned source files.

Finish-only changes and S06 use `tools.parallel-calls-stream`: it has complete tool arguments, unlike `truncation.tool-call-args-stream`. Its two calls are preserved, so these are related complete-call shapes rather than this suite's single-call fixture. S15 on OpenAI Chat maps the `data-error` form; the transform does not emit this suite's additional named `event-error` variant.

## Conversion boundaries

The pinned [format reference](https://github.com/kartsan03/wiretruth/blob/b65af93e0186d1a9d8076f85ea4e6614a9102faf/docs/formats.md) defines these mappings:

| toolcall-faults | wiretruth | Constraint |
|---|---|---|
| Scenario ID, title, summary, evidence | `id`, `title`, `summary`, `references` | Use a scenario document for the derived fixture's ID. |
| Call and tool schema | `call.stream`, `call.messages`, `call.tools[]` | Translate the prompt to the provider-neutral message format. |
| Judgement | `requirements[]` with `level` and `check` | Translate each assertion; do not copy our observed categories as their grades. |
| HTTP exchanges | `exchanges` | Preserve supported bodies and endings; supported lowercase headers are `content-type` and `retry-after`. |
| `x.end: reset`, `x.end: stall`, `x.delays_ms` | No equivalent | Not exportable with these transforms. |
| Recorded provenance | `tier: recorded` and their provenance fields | Include their required recording metadata; a source URL alone is insufficient. |
| Synthetic fixture | No corresponding tier | Export a derivation recipe from their recorded/documented source, not our bytes. |
| Truth | `truth` | wiretruth recomputes it with its own extractor. |

The existing checks `no_complete_tool_call_from_partial_args` and `truncation_is_signalled` apply to S02 and S03. The former checks what the client reports as complete; it is not a measurement of a JVM tool body's execution. Other assertions may require a new check and upstream agreement.

S03 (name-only), S12 (interleaving), S13 (concatenated argument objects) and S16 (unterminated final event) need a new transform or an appropriate new recording for faithful conversion. S07 `@reset`, S08's stall and per-write delays have no equivalent in this pinned format. The S07 command above covers only `@fin` through `end: abort`. No translation layer, exporter or new upstream check is implemented here.
