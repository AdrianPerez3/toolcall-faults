# Adding an adapter

Target maintainers are invited to own their adapter and correct its configuration. Start from an existing adapter with the same kind of surface, then supply four things:

1. A manifest, `adapters/<target>/adapter.json`: target and Maven coordinates, `version_property`, `main_class`, modules with wires and HTTP clients, surfaces, typed-error prefixes, documentation links and `config_notes`. Every unsupported entry needs a reason and a basis link.
2. `adapters/<target>/pom.xml`, with the target version as a property named by the manifest. Keep dependency versions explicit. The runner builds each version into its own classpath; target libraries do not belong in the runner's dependencies.
3. One source file using the dependency-free kit under the adapter directory. It invokes the target's public API, records inside the tool methods and emits the kit's result format.
4. A reviewed baseline file under `expectations/baseline/`, keyed by exact cells. Unfiled findings stay in the ignored local baseline directory until disclosed; never publish them merely to make an adapter contribution complete.

Register the target and current pin selection in `matrix.json`. A contribution must show **S01 `tools.single-call` as `EXECUTED_AS_SENT` on every declared surface**, with three stable repetitions. Model surfaces report assembled calls without executing tools; the control oracle checks that contract. Other existing controls must pass too.

## Process contract

The runner passes `--wire`, `--module`, `--surface`, `--tools`, `--base-url`, `--timeout-ms`, `--workdir` and `--http-client`. Use the supplied cell URL; never read real credentials. Retries are disabled where the library allows it, and the target timeout is three seconds. Document each departure from defaults in `config_notes`, including a setting the API cannot apply.

Library diagnostics go to standard error. Emit exactly one `tcf.result/v1` JSON line to standard output, and exit zero whenever that line was produced, including a captured library exception. Nonzero adapter exit is a harness failure. Use the kit for result emission, resolved dependency metadata and the recorder file; no new JSON dependency is needed in the adapter.

Record all terminal signals, caller-visible finish reasons and returned tool calls. Keep callback-only information separate. A loop surface records every real tool invocation and its received arguments; a model surface does not invoke tools. Listen for one second after the first terminal signal so a second signal remains visible. The runner supplies the watchdog and server-side request observations.

If a normal control fails, first fix the adapter, harness or fixture. Only a library failure established from released source on provider-documented valid input may become a `control_failures` declaration. It must match module, surface, wire, tool, category and error class exactly, with a public basis link and notes explaining synthetic/live-capture limits. Restrict versions where applicable. A changed category, changed error class, undeclared failure or newly passing declared control fails the self-test. Remove an obsolete declaration after review; non-control cells on that module and surface return automatically.

## Verify

Build with `./mvnw verify`; run `./tcf lint` and `./tcf lint --scan`. Use the README's container cell command with your target, module and each surface, then `./tcf --container 21 selftest --controls`. Inspect the recorded versions, arguments and requests, not only the category. Review any `NEW` or `CHANGED` baseline entry against raw observations and documentary judgements. Keep target execution inside `--network none` containers.
