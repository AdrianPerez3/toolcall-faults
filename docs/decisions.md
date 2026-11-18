# Decisions

- Project name: toolcall-faults. Maven groupId is the hosting account in lower case; it is declared in the POM files.
- Licence: Apache-2.0. Provider and framework names imply no endorsement.
- Right-of-reply window: 14 days for each judged finding, or until a maintainer answers.
- AI assistance is disclosed in the README. Commits carry no co-author trailer or tool attribution.
- Spring AI 1.1.x is the first addition after release; DeepSeek remains in the first release.
- Run the reviewed agent-cutoff-bench server in an offline container for compatibility rows.
- No container image is published. Weekly runs upload the last-run stamp as an artifact; they do not push.
- Model surfaces are record-only in the first release. Compare local Linux arm64 and CI Linux amd64 containers; no native execution of targets.
- Instructions and settings for coding tools are kept out of the repository. Hooks enforce attribution rules.

## Build pins

Resolved from Maven Central metadata on 2026-11-16. Use the latest stable JUnit 5 and Jackson 2 releases and stable Maven 3 plugins, preserving Java 17 source compatibility.

| Component | Version | Primary source |
|---|---|---|
| JUnit BOM | 5.14.4 | https://repo1.maven.org/maven2/org/junit/junit-bom/maven-metadata.xml |
| Jackson BOM | 2.23.0 | https://repo1.maven.org/maven2/com/fasterxml/jackson/jackson-bom/maven-metadata.xml |
| Surefire | 3.6.1 | https://repo1.maven.org/maven2/org/apache/maven/plugins/maven-surefire-plugin/maven-metadata.xml |
| Compiler | 3.16.0 | https://repo1.maven.org/maven2/org/apache/maven/plugins/maven-compiler-plugin/maven-metadata.xml |

Maven wrapper: 3.9.12. Java source release: 17. Jackson belongs only to the runner; kit and replay server have no runtime dependencies.

## Replay server

Public top-level Java records and enums each have their own source file. The concurrency check preserves the per-cell eight-request cap: later requests receive 409, and each successfully served response is checked against its own cell.

## Fixture format

Truth calls carry their wire call id so later requests can be matched to a particular tool result. Extensions are indexed by exchange number; reset and stall use abort as their portable base end mode. Structural and byte-hash checks apply to self-test fixtures, while provider completeness applies to catalogue fixtures. Generation is explicit through the fixture test write property.

## Cell execution

Watchdog kills retain their OS exit code and are classified by the watchdog rules; a nonzero adapter exit means HARNESS only when the watchdog did not cause it. The child environment is cleared. Native macOS test JVMs may recreate `__CF_USER_TEXT_ENCODING`, as documented in OpenJDK ProcessBuilder tests; provider credentials, PATH and HOME must remain absent. Target libraries execute only in the reference container.

## 2026-11-16 — Spring AI build pins

Maven Central metadata lists Spring AI 2.0.2 and Spring Boot 4.1.2. Keep the probe pins and import the Boot BOM first, as recommended, to preserve the probe dependency resolution. Sources: https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml and https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/maven-metadata.xml.

## 2026-11-16 — LangChain4j pin

Keep the probe version 1.23.0, confirmed in https://repo1.maven.org/maven2/dev/langchain4j/langchain4j/maven-metadata.xml. Use the released streaming builder and JDK HTTP client; intermediate response metadata belongs only in callback_only, as the spec requires.

## 2026-11-16 — Compatibility observations

Use the pinned, unmodified agent-cutoff-bench server in the offline reference environment. Identify watched artifacts by full Maven coordinate in `version_<groupId:artifactId>` fields so the two Jackson databind artifacts remain distinct. Generated observations stay local until the applicable disclosure steps; the reusable harness and attribution can be committed separately.

## 2026-11-16 — Verification contracts

The repository scan checks staged blobs against the staged allow-list and separately checks changed working copies, so partial staging cannot hide content from the commit gate. Parsed argument JSON is compacted outside strings when embedded in the single-line adapter result; `arguments_raw` remains unchanged.

## 2026-11-16 — Anthropic fixtures and adapters

Keep the name-only shape without `content_block_stop` separate from the constructed block-stop variant. The no-stop shape follows the one available capture of a truncated tool block, a mid-string cut recorded with fine-grained tool streaming; no capture of a name-only block exists for either variant. Both framework adapters use their released streaming APIs with the probe-verified base URL forms: the cell root for Spring AI and the cell root plus `/v1/` for LangChain4j. The anchor harness also compares caller chunk counts, so an empty completion cannot masquerade as a streamed response.

## 2026-11-16 — Recorded variants and error envelopes

Recorded variants keep their source bytes and use independent pinned hash assertions. The Anthropic source has no final newline; its fixture declares that omission. Synthetic completeness checks do not rewrite or constrain recordings, while structural and hash checks still apply. In-band error envelopes carry an error type and message rather than completion-chunk fields. Anthropic nonstream truth stores the serialized input object carried by the message.

## 2026-11-16 — Schema controls

Self-tests derive a normal primitive-tool control from the complete set_level fixture by changing only the finish reason and recalculating hashes. This validates primitive argument binding without adding a measurement scenario. Zero-argument controls default to flush_notes; adapters register only the tool selected for the cell.

## 2026-11-16 — Framework surfaces

Spring AI 2.0.2 model surfaces use provider-specific `OpenAiChatOptions` or `AnthropicChatOptions` with `toolCallbacks` on `Prompt`, built by mutating the model options to preserve configured defaults. The generic options builder in the reference example failed with a pre-request ClassCastException in the released OpenAI model; its released source casts resolved options to OpenAiChatOptions. Direct models return tool requests without executing them. Source: https://docs.spring.io/spring-ai/reference/api/tools/chatmodel-tool-calling.html.

LangChain4j 1.23.0 blocking OpenAI and Anthropic builders expose `timeout(Duration)` and `maxRetries(0)`. Use `Result<String>` for blocking AI Services and read `finalResponse()` and `finishReason()`. Model streaming uses `StreamingChatResponseHandler` with partial-tool, complete-tool, complete-response and error callbacks. Sources: https://docs.langchain4j.dev/tutorials/ai-services/ and https://docs.langchain4j.dev/tutorials/response-streaming/.

For model surfaces, an empty textual argument string on the known zero-parameter flush_notes tool represents an empty argument object for matching. Raw observations and parse flags stay unchanged. Parameterized tools are never repaired; incomplete truth still produces EXECUTED_ALTERED when a call is handed up.

Superseded on 2026-11-17 (see "Review corrections"): ~~LangChain4j complete-tool callbacks and the final response can expose equivalent representations of the same zero-parameter call. Match those copies once by id and name, retaining the final-response view in callback_only.~~

## 2026-11-16 — SDK surfaces

The Maven Central metadata and pinned READMEs confirm openai-java 4.105.0 and anthropic-java 3.8.0. OpenAI model surfaces use createStreaming with ChatCompletionAccumulator.chatCompletion, and create for blocking calls; function definitions use ChatCompletionFunctionTool, FunctionDefinition and FunctionParameters builders. Source: https://github.com/openai/openai-java/blob/v4.105.0/README.md.

Anthropic model surfaces use messages().createStreaming with MessageAccumulator.message, and messages().create. Loop surfaces use beta().messages().toolRunner(ToolRunnerCreateParams), iterated directly or via streaming(). Typed BetaRunnableTool.of(Class, Function) leaves argument binding to the SDK. Set thread(false) to replay the ordinary messages conversation shape: the released default maintains a server-side message thread. Every message yielded by the loop is caller-visible. Sources: https://github.com/anthropics/anthropic-sdk-java/blob/v3.8.0/anthropic-java-core/src/main/kotlin/com/anthropic/helpers/BetaToolRunner.kt and https://platform.claude.com/docs/en/api/sdks/java.

Anthropic non-beta model parameters take explicit Tool/InputSchema definitions; the Class-based definition overload is on beta message parameters. Both SDK client interfaces expose close() without implementing AutoCloseable, so adapters close them in finally.

The Anthropic class-schema generator rejects empty classes before a request. flush_notes therefore uses the explicit BetaTool/InputSchema overload of BetaRunnableTool with no properties; parameterized tools retain typed SDK binding and its default local schema validation. No dummy argument or validation override is added.

## 2026-11-16 — DeepSeek module

DeepSeek remains on the five specified scenarios. Its released builders accept deepSeekApi, options and a Spring Core RetryTemplate. Set RetryPolicy.withMaxRetries(0); the released RetryUtils default calls maxRetries(10). Set completionsPath to /v1/chat/completions instead of the default /chat/completions. Streaming and blocking calls both use Reactor Netty with the configured response/connect timeout and transport retries disabled; the blocking request factory also receives the read timeout. The low-level module needs an explicit BOM-managed reactor-netty-http dependency. Sources: https://docs.spring.io/spring-ai/reference/api/chat/deepseek-chat.html and https://github.com/spring-projects/spring-ai/blob/v2.0.2/models/spring-ai-deepseek/src/main/java/org/springframework/ai/deepseek/DeepSeekChatModel.java.

Module scenario restrictions apply to the CLI and self-tests. Modules without a scenarios list retain their existing scope.

## 2026-11-16 — Declared control failures

Keep the DeepSeek loop-call controls as observed: ERROR_UNTYPED with java.lang.IllegalStateException. The released createRequest assertion rejects null assistant text after tool execution. Declare each affected control in the manifest with https://github.com/spring-projects/spring-ai/pull/6775 as basis; controls fail verification if the category or error class changes, if the control starts passing, or if a declaration is not exercised. This supersedes the proposed surface exclusion.

The fixture is synthetic. The DeepSeek API reference permits null content; whether the live API sends null or an empty string is unverified until a recording. PR #6775 is already public upstream and references https://github.com/spring-projects/spring-ai/issues/5568, a related streaming issue; do not treat its closed state as confirmation of the blocking-path fix or prepare another report.

The catalogue includes S10 non-streaming on DeepSeek loop-call. Under the maintainer's corrected rule, select controls normally but list every non-control cell on the same module and surface as not run, with the declaration's basis. Removing the declaration restores those cells automatically. Preserve S10 on streaming and other modules; no hand-written exclusion.

## 2026-11-16 — Judgements and predictions

Model surfaces are record-only in the first release. The Anthropic block-stop truncation shape remains record-only without a capture, including combined non-streaming and tool variants. Judgements use provider register keys independently of the classifier; declared normal-control failures keep their observed category and upstream basis.

Predictions preserve the design table's wording and are committed before the first full matrix run. Empty category lists mean the source does not determine an outcome. The DeepSeek normal-control observations reference the already-public PR #6775; they require no new upstream report.

## 2026-11-16 — Matrix selection and result storage

Normal primitive controls use the same derived bytes as selftest and remain in the matrix after a failure declaration is removed. The catalogue fixture files are unchanged. Declared failures remove non-control cells by module and surface into a visible not-run list with their basis.

Results preserve observations and use private storage for unsupported cells without a filed baseline. A local previous-run snapshot permits check to detect changed observations after the current result file is replaced. Container runs record the image ID and the Git description, including the dirty marker when measured source is uncommitted.

## 2026-11-16 — Frozen prediction selector erratum

The prediction generator wrote langchain4j-openai, but the manifest already declared langchain4j-open-ai at the commit that froze the predictions (part of the unpublished history before the snapshot commit). Preserve the frozen predictions file and its category lists; prediction-errata.json, kept beside the predictions file, explicitly binds only this misspelled selector to the existing module. This correction uses pre-run source, not observed outcomes. Lint now checks prediction modules against manifests and allows only explicit module-selector errata with a basis; it rejects category changes in an erratum. Comparisons must apply this mapping and disclose it. Since 2026-11-17 both files stay out of the repository until the behaviours they name are reported; the repository carries the hash of the predictions file in expectations/predictions.commitment.json.

## Version columns

Spring AI control 2.0.0 uses Spring Boot 4.1.0, verified in the [released tag POM](https://github.com/spring-projects/spring-ai/blob/v2.0.0/pom.xml); the published flattened parent omits that property. Maven Central metadata lists 2.0.0 and LangChain4j 1.21.0. Spring AI 2.1.0 is not released; the next candidate is 2.1.0-RC2, whose [tag POM](https://github.com/spring-projects/spring-ai/blob/v2.1.0-RC2/pom.xml) uses Boot 4.2.0-RC2.

Required control pins both distinguish the current releases with three stable repetitions. The optional OpenAI 4.69.0 adapter compiles unchanged. Anthropic 2.53.0 (the last Central release before 2.54.0) does not compile: `BetaRunnableTool` is absent; omit that optional pin as planned, retaining the current SDK target. The declared DeepSeek control failure also reproduces on 2.0.0 and references the existing PR #6775.

OpenAI 4.69.0 and 4.105.0 give the same S12 observations in three repetitions. The [4.69.0 released source artifact](https://repo.maven.apache.org/maven2/com/openai/openai-java-core/4.69.0/openai-java-core-4.69.0-sources.jar), `ChatCompletionAccumulator`, already keys tool-call accumulation by index. Remove this optional regression claim and control role; keep its observation history, the current SDK target and S12 unchanged. Neither mandatory pin is replaced or removed.

DeepSeek normal loop-call controls pass on 2.1.0-RC2 with the unchanged null-content fixtures. Its [released message implementation](https://github.com/spring-projects/spring-ai/blob/v2.1.0-RC2/spring-ai-model/src/main/java/org/springframework/ai/chat/messages/AbstractMessage.java) joins a tool-call-only part list to empty text, avoiding the request assertion. Scope the two control declarations with `versions: ["2.0.0", "2.0.2"]`; the generic selection rule then restores S10 on 2.1.0-RC2 automatically. The PR remains the basis for the observed older failures, without attributing this recovery to that unmerged PR. Live API null-versus-empty behavior remains unverified.

Adapter JVMs route HotSpot warning/error logs to stderr with `-Xlog:all=off:stdout -Xlog:all=warning:stderr`. A startup performance-data file-lock warning occurred before Java main and contaminated the one-line result channel. [The Java launcher documentation](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html) defines the default warning/error output and explicit destinations. The runner keeps its strict single-JSON-line validation and preserves warnings in the cell stderr log. A native-startup-warning test verifies the destination.

## Report generation

Public reply-window eligibility applies to every page section, including the observations and scenario pages. The local report includes withheld rows. Declared control failures retain their observed category, first error class, basis and synthetic/live-API notes; excluded cells are explicitly listed as not run with the same basis. Reproduction commands use the reference container. Fixture version hashes newline-delimited body hashes in fixture-path order. Without a successful-run stamp, the banner says it is not recorded and separately labels the latest observation date. Differing repetitions and callback-only values remain distinct.

## Reference base images

Resolved from the Docker Hub official Maven registry on 2026-11-16 with `docker buildx imagetools inspect`. Pin the multi-platform image indexes so Linux arm64 locally and amd64 in CI select the same published release. Runtime containers use no network. The launcher records the built image ID, distinct from its base index digest.

| Base tag | Index digest |
|---|---|
| `maven:3.9-eclipse-temurin-17` | `sha256:1a352420f7aba21f5ad08df31bab55f74c013fb491f1ae8ab1dd7ff9ed698584` |
| `maven:3.9-eclipse-temurin-21` | `sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320` |
| `maven:3.9-eclipse-temurin-25` | `sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976` |

## Complete observations and comparison history

A saved run is not valid evidence when any repetition is HARNESS or when its repetition IDs are incomplete. Model-surface stability and pin comparisons use the arguments handed to the caller, including unparsed raw arguments; loop surfaces use recorder arguments. A filtered rerun updates comparison history only for the cells it reruns, and an alternate output directory never changes the default comparison history.

For S05, “turn reported clean” requires a completion signal without an error, a non-completion finish reason, or watchdog termination. The earlier predicate only checked the absence of error and limit signals and could label a hang clean. This correction follows the scenario's stated judgement, leaving the oracle and fixture bytes unchanged.

## Network isolation check

Docker's `none` driver is retained. Linux can automatically create inactive fallback tunnel devices in each network namespace (`fb_tunnels_only_for_init_net=0`); counting every interface is therefore not a valid isolation check on all kernels. Verify instead that loopback is the only interface with IFF_UP, the IPv4 route table is empty, and every IPv6 route refers to loopback. No networking configuration is changed. Sources: [Docker none driver](https://docs.docker.com/engine/network/drivers/none/) and [Linux network sysctls](https://docs.kernel.org/admin-guide/sysctl/net.html#fb-tunnels-only-for-init-net).

## LangChain4j HTTP client axis

The alternate client is `langchain4j-http-client-okhttp:1.23.0-beta33`, whose released POM uses core and HTTP-client 1.23.0; the matching control artifact is 1.21.0-beta31. The unqualified 1.23.0 OkHttp artifact does not exist. All four model builders accept `httpClientBuilder(HttpClientBuilder)`. The OkHttp profile excludes the transitive JDK client from both model modules; the adapter passes the sole discovered builder explicitly and checks its concrete type. Released 1.23.0 discovery throws on multiple factories without explicit selection, unlike the newer priority-based discovery described in current documentation.

The alternate build has its own classes, classpath and cache key, also used by controls and pin comparisons. Scenario `http_client_axis` flags limit selection to S01, S06, S07, S08, S15 and S16; the existing matrix configuration needs no duplicate axis field. Sources: [released OkHttp POM](https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-http-client-okhttp/1.23.0-beta33/langchain4j-http-client-okhttp-1.23.0-beta33.pom), [control POM](https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-http-client-okhttp/1.21.0-beta31/langchain4j-http-client-okhttp-1.21.0-beta31.pom), and [released HTTP-client source](https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-http-client/1.23.0/langchain4j-http-client-1.23.0-sources.jar).

## Complete environment comparisons

The cross-JDK command compares the complete current matrix on JDK 17, 21 and 25. Missing cells, incomplete repetitions, unstable observations and HARNESS results fail verification rather than yielding an apparent match. Diagnostic flaky reruns use ten fresh repetitions for the selected unstable cells and preserve unrelated stored observations.

## CI action pins and scheduled runs

Official action releases checked on 2026-11-16: [checkout v7.0.1](https://github.com/actions/checkout/releases/tag/v7.0.1) at `3d3c42e5aac5ba805825da76410c181273ba90b1`, [setup-java v6.3.0](https://github.com/actions/setup-java/releases/tag/v6.3.0) at `d1374f8ac74474200d16ef8ecee22c7ae7a51880`, and [upload-artifact v7.0.2](https://github.com/actions/upload-artifact/releases/tag/v7.0.2) at `cf430e030ddbb5b0abf93d22962f4752f3646cd9`. The workflows use these commit pins and read-only repository permissions.

The current matrix agrees across JDK 17, 21 and 25, so weekly runs use JDK 21 while change CI keeps all three. Weekly automation uploads only the successful-run stamp; it does not commit or push. Run and check details remain in the ephemeral workspace because unfiled observations must not appear in public logs or artifacts. A clean public checkout can therefore fail check until an observation has a filed baseline; this does not authorize publishing a private baseline.

Probe expectations retain their JDK 21 origin. The same unchanged fields are also checked on the other reference JDKs, with runtime JDK and probe origin labelled separately. The initial stamp records local execution of the workflow commands, not a hosted scheduled run. Hosted execution and the maintainer's 45-day activity reminder remain to be verified after publication is authorized.

Workflow outputs use `.local/run`, which survives the reference container through the launcher's existing private-output mount. The check reads that same directory. Dependabot uses its documented `directories` option to cover the root and each adapter, with one weekly group per ecosystem ([options reference](https://docs.github.com/en/code-security/reference/supply-chain-security/dependabot-options-reference)).

## Tracking existing upstream reports

Upstream status records existing reports and does not imply confirmation. The DeepSeek declared controls use [PR #6775](https://github.com/spring-projects/spring-ai/pull/6775), already filed by another contributor. Its related [streaming issue #5568](https://github.com/spring-projects/spring-ai/issues/5568) is linked in notes; its closure does not establish a blocking-path fix. The synthetic-fixture and unverified live-API qualifications remain with the observations. Unfiled observations have no invented issue row. Lint requires each filed baseline to match an issue or PR row for its library, scenario and provider.

## Stable runs and retained verification evidence

Completed unstable runs fail after saving their observations and reporting their distribution, including ten-repetition diagnostic reruns. Pin evidence is separated by JDK, HTTP client and tool. Reference images also prepare the frozen probe-anchor versions independently of moving current pins, preserving offline self-tests after dependency updates.

## wiretruth derivation sources

Export recipes use wiretruth 0.4.0 and its suite at `b65af93e0186d1a9d8076f85ea4e6614a9102faf`. The OpenAI Chat tool sources are recorded; the Anthropic counterparts are documented. Preserve that distinction: both source tiers are accepted by the upstream derivation contract, and neither turns our synthetic fixtures into recordings.

Finish-only changes and missing-finish derivation start from the complete parallel-tool source. The source named for truncated arguments already contains incomplete input, so changing only its finish value cannot produce complete arguments. Transport and error cut points are selected from the pinned events. Derived truth and the intended shape are checked in an offline container. The recipes and conversion limits are in [export-wiretruth.md](export-wiretruth.md); no export command or translation layer is added.

## Pre-release documents

Citation metadata uses the confirmed public account alias and the development version, without an invented release date, affiliation or DOI. Security reporting uses GitHub's private reporting action when enabled, with a contact-only fallback if unavailable; the document does not claim a hosted setting is active. The README distinguishes automated checks from upstream reports, which are prepared with AI assistance and reviewed by the maintainer before submission (changed on 2026-11-18; the entry first said they were reproduced and written by hand). Documentation paths trigger CI so the public wording tests also run for documentation-only changes.

## 2026-11-17 — Review corrections

LangChain4j model-stream: the caller view is the `ChatResponse` passed to `onCompleteResponse`, and nothing else. `onPartialToolCall` and `onCompleteToolCall` are optional callbacks with default no-op bodies in the released handler interface, so their content is recorded under `callback_only` and does not decide the category. This follows the rule that a signal available only through an opt-in callback is recorded separately. Stored rows written before this change classified some model-stream cells from the callback; they are regenerated by the next matrix run.

LangChain4j blocking AI Services: `Result.intermediateResponses()` is recorded under `callback_only`, as `onIntermediateResponse` is on the streaming surface. Categories are unaffected.

Result routing and the report treat a filed baseline entry as covering the outcome it names. A different unsupported outcome of the same cell is withheld until it has its own entry.

Fixture lint: `content_block_stop` and `message_delta` are required on the Anthropic wire unless listed in `removes`; removals apply to the damaged first exchange only; `final-blank-line` exempts only the last element of that exchange; an in-band error envelope is accepted only as the last frame of that exchange.

Hosted workflows keep self-test, run and check output in the ephemeral workspace and print fixed summary lines only. Public result files are written world-readable and withheld ones owner-only; the check step runs in the container so that it can read both.

## 2026-11-17 — What is public before an upstream report exists

A row is kept out of the tracked results when its cell shows an outcome without documentary support and no filed report names that outcome, and also when another cell of the same target, version, module and scenario does. Record-only rows of such a family show the same behaviour, so they wait with it; a cell with its own filed entry stays public. Lint and the public report apply the same rule. Anchors of such families live in a private file beside `expectations/anchors.json`, and the self-test reads both. The frozen predictions are private for the same reason; `expectations/predictions.commitment.json` publishes their SHA-256 and size, and lint checks the private file against it. The file is published unchanged once the behaviours it names are reported.

Hosted workflows run `./tcf run --published`: the cells with tracked rows. A public checkout has no baselines for the other cells, so it neither produces nor judges their observations; the maintainer runs the full matrix with the private baselines. `check` stays strict in both places.

## 2026-11-17 — Model surfaces and request bodies

On a model surface a call handed up exactly as the wire carried it, for a call the wire left incomplete, is not an alteration by the client. It is not counted as handed up; the cell is `SURFACED` when a limit reason or an unparsed call is visible to the caller and `DROPPED` otherwise. This replaces the earlier rule that treated an empty string for the zero-parameter tool as an executed call whatever the truth; that projection now applies only where the wire carried a complete call. A client that hands up something the wire did not carry, such as `{}` for a tool block that received no input bytes, is still `EXECUTED_ALTERED`.

A later request whose logged body does not parse as JSON makes the feedback decision impossible; such a cell is `HARNESS` unless the recorder already shows an altered execution. The server logs bodies up to 16 kB.

## 2026-11-17 — Judgement register

Each register sentence names the wire it is a basis for. A cell cites the sentences of the provider whose wire it replays; sentences of other providers on the same fault are printed as related guidance. Quotes carry the clause that states their scope. Each entry and, where they differ, each rule names its criterion: provider documentation, the target's own documentation, a criterion of this suite, or a control. Bases describe what the sources say and what the suite infers. The report has a register page and prints sighting, rules and criterion on every scenario page.

## 2026-11-17 — Fixtures

In fixtures with two calls on the OpenAI wire, the second call's first chunk carries its `tool_calls` entry only, as in the cited recording; `role`, `content` and `refusal` come once. Sighting labels state what the cited sources show; reports filed by this project's maintainer are marked as showing client impact, not a provider's shape. The Anthropic fixtures without `content_block_stop` say that the shape follows a capture made with fine-grained tool streaming. Two sources exist for a tool block cut by `max_tokens` and they disagree: that capture, in the Python and TypeScript SDK repositories, goes from `input_json_delta` straight to `message_delta`, and a constructed test in the Java SDK repository ends the block with `content_block_stop`. The documentation does not say which the default mode sends, so both variants stay and the one without a capture stays record-only.

The recorded Anthropic capture ends without the blank line that terminates its last event, so a conforming parser does not dispatch `message_stop`; its damage text now says so. `anthropic/truncation.args-cut-mid-string@recorded-terminated` is a derived fixture, the same bytes with that blank line appended, which leaves the cut inside the tool input as the only damage. Fixtures made from a recording run on model surfaces only.

## 2026-11-17 — Reset end mode

The server waits 15 ms before a reset, as before every part. A zero-linger close discards bytes the sender has not yet transmitted or had acknowledged, and they cannot be retransmitted afterwards; a receiver, on Linux and macOS, still delivers what it already queued. The pause gives the last part time to leave. It narrows the race and cannot remove it on a loaded host; `@reset` cells rest on the three-repetition rule.

## 2026-11-17 — Maven wrapper

The wrapper is of the `script` type, version 3.3.4, with Maven 3.9.12. Its launcher downloads `maven-wrapper.jar` and checks it against `wrapperSha256Sum`; the jar downloads the Maven zip, checks it against `distributionSha256Sum` and unpacks it in Java. The `only-script` type used before unpacks with `unzip` or, where that is absent, switches to the `.tar.gz`, so one checksum could not hold on a workstation and in the reference image. Both hashes were computed from the files on Maven Central, whose published SHA-512 (Maven) and SHA-1 (wrapper jar) match the downloads. The jar is not committed.


## 2026-11-17 — Rows whose cause an upstream thread already states

A row is withheld while its behaviour has not been reported upstream. A behaviour counts as reported when a public thread of the target project states the cause that produces the row's outcome, for the same module, whoever opened it; this was already the treatment of spring-ai PR 6775. The entry then links that thread, and results/upstream.json says who opened it and what the thread does not mention. A thread that states the cause for another module or another call path does not count, and those rows stay withheld.

Applied on this date after reading each thread: langchain4j issues 7145 and 3925 and pull request 6144; spring-ai issue 7360 for the truncated-call rows of both current versions, and issue 6374 for the spring-ai-openai row of the historical pin. Where a thread matched only in part, the criterion was not applied and those rows stay withheld.

A linked thread is a reference, not a confirmation: the upstream project has not reviewed these rows.

## 2026-11-17 — Parallelism follows the host

The first hosted run failed in the self-test on all three JDKs: the pin comparison started eight cells at once on a two-core runner, and a cell was ended by the watchdog before its request reached the replay server (HARNESS). The same run with the container limited to two processors reproduces it; two cells at once complete without HARNESS there. `run` without `--parallel`, and the pin comparison, now use half the available processors, at least one and at most eight. Observations are unaffected: a HARNESS repetition is never recorded as an observation.

A hosted step that fails now prints how far it got: the phase summary lines of the self-test, the `run:` line, and counts of HARNESS lines or of cells that differ from the baseline. It still prints no cell and no outcome; those stay in the workspace log.

Change CI runs once per change. The push trigger is limited to `main`, because a pull request branch inside the repository otherwise starts a push run and a pull_request run of the same commit. A newer push to a pull request cancels that pull request's run still in progress. A run on `main` is never cancelled, because it is the record that the published rows reproduce at that commit.

## 2026-11-18 — A version bump is not measured by itself

The current version of each target is the one in its adapter build, and a dependency update pull request on that version is how a new release is noticed. Hosted runs replay only the cells that have published rows, and a cell is identified with its version, so after such a bump a hosted run skipped every cell of that target and still passed. Lint now reports a target that has rows for a version the matrix no longer names and none for its current version. The pull request then fails in the lint step until the maintainer has run the new version and added its rows. Rows of the control and next versions alone do not trigger the rule, because a public checkout may hold only those.

## 2026-11-18 — Hosted job layout and limits

The first passing hosted run took 76 to 94 minutes per job against a limit of 120, with every library process run one after another on a two-processor runner. The self-test and the replay of the published cells now run as two jobs per JDK on separate runners, with limits of 75 and 120 minutes, about twice the slowest time seen for each. The weekly run stays one job, because its stamp depends on every phase, with a limit of 180 minutes. A cell still runs alone on its runner, so a repetition runs under the conditions of the run that passed.

The number of cells run at once is unchanged, and a HARNESS repetition is not retried. A host too busy to start one process in time can also end a neighbouring repetition after its response was served, which is recorded as an observation, so a HARNESS repetition marks the whole run as suspect. Each job prints the processor count and model of its runner. The weekly job writes no stamp when a step has modified tracked files, and a weekly run started by hand waits for a scheduled one.

## 2026-11-18 — Dependency updates

Updates of the build's own tools are grouped by size, so a major update comes in its own pull request. The libraries under test stay visible to the update tool, because its pull request on a current version is how a new release is noticed; a test rejects an ignore rule that covers one of them. Everything in an adapter build is on the classpath the rows record, so an adapter's pull request is merged after its cells have been run and their rows added.

## 2026-11-18 — A refused download is tried again

On hosted runners the wrapper script failed twice, in about 75 job starts, with "Failed to validate Maven wrapper SHA-256". The script downloads `maven-wrapper.jar`, removes the file when the download fails, and then reports the missing file as a checksum failure: a refused request to Maven Central, not a different jar. Both failures came while many jobs started at once.

The first Maven call of a fresh environment is now a download-only command with three attempts, 15 seconds apart: `--version` before the build on a runner, and `dependency:go-offline` in the image. A wrong jar still fails all three. Commands that run tests are not repeated, so a test that fails once still fails the job. The jar stays out of the repository: committing it would remove this download and put a binary under version control, and the other downloads would remain.

## 2026-11-18 — A behaviour the target project tracks in public is not private-class

`SECURITY.md` keeps observations of unbounded resource use on default settings private. The published rows include a wait that outlasts the configured timeout in two scenarios: a stream that ends without its final event, and a stream that stalls with the connection open. In each row a watchdog ends one stalled call after 12 seconds; no growth of memory, processor use or requests was measured. Both behaviours are described in public issues of the target project, opened by other people, and the project gave one of them its `bug` label, not a security label.

The maintainer decided on this date that such rows stay published. The rule now says so: a behaviour that the target project already tracks in a public issue is not treated as private-class, and its rows are published with a link to that issue. A behaviour of that class that no public issue describes stays private and goes to the target project's security contact.

## 2026-11-18 — Rows published under the maintainer's LangChain4j report

The rows for truncated tool calls on LangChain4j (a call with no argument bytes, a call with complete arguments, and a cut second call, each under a limit finish reason) are published with the report the maintainer filed on this date. The criterion is the one already used for the Spring AI rows: the report states the cause that produces the row's outcome.


## 2026-11-18 — Maven bootstrap in a fresh checkout

A public runner exhausted all three attempts to verify the Maven wrapper JAR during the image build. The checkout now includes Apache Maven Wrapper 3.3.4 in its binary distribution form. Both launchers check the existing SHA-256 pin. The Maven distribution retains its own checksum, and the download-only steps retain their retries. [Apache Maven Wrapper distributions](https://maven.apache.org/tools/wrapper/index.html)

The reference build context also excludes local agent configuration, research inputs and scratch files. The same checkout can contain private maintainer work alongside the tracked source.

## 2026-11-18 — Evidence used by the oracle

The recorder identifies its successful tool returns in later requests, including JSON-quoted returns. The adapter records the invocation count at its first error and leaves a marker on its first terminal signal. A watchdog kill after that signal, a missing fixture route, or a last request with less than one client timeout available gives an invalid run where that evidence is needed to establish a hang. Historical rows retain the evidence captured by their suite version.

The replay server reads both fixed-length and chunked HTTP/1.1 requests. Its request log retains a complete UTF-8 prefix within the 16 KiB byte limit.

## 2026-11-18 — Disclosure across partial measurements

A filtered run uses retained observations as well as new repetitions to decide which members of a family are public. A public report requires every unsupported outcome shown for a cell to be covered by its filed entry. A new unsupported category or a baseline whose judgement condition ceased to apply is reported by check. Lint verifies staged data, row identity and fixture hashes, and local comparison history is owner-readable.

## 2026-11-18 — Source scope in the judgement register

The silent-drop and stalled-body terminal criteria belong to this suite. Client timeouts can cover different parts of an HTTP response. The register distinguishes guidance for the tested request from guidance for an optional mode or a different service. Streaming-only guidance applies directly to streaming surfaces. Synthetic fixtures must retain message boundaries except where they declare a removal.

## 2026-11-18 — Self-test coverage

Each anchor requires an observed category and a nonempty value for any substring assertion. The self-test requires a public anchor register, fixtures for server checks, and a single-call control for each applicable module and surface.

## 2026-11-18 — Published replay coverage

Hosted checks require the complete set of published current cells for the running JDK. An alternate run compares with the stored same-JDK cell when one exists, or its JDK 21 reference otherwise. The comparison uses the registered stability fields. Empty output cannot verify a replay.
