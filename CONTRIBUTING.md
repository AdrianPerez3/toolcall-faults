# Contributing

Start with [README.md](README.md) for scope; the rules for changes are on this page. Keep changes small and focused on the existing first-release contract. Target maintainers are invited to own their adapters and correct their configuration.

For an adapter, follow [adding an adapter](docs/adding-an-adapter.md). For a changed result or documentary basis, follow [disputing a cell](docs/disputing-a-cell.md). Include the exact cell key, resolved dependency versions, fixture provenance and three repetitions. Keep observation changes separate from changes in judgement. Do not modify a fixture or loosen an assertion to match an observed library outcome.

Write a failing test and observe the stated failure before changing behavior. Build the project's code natively, and run target libraries only in an offline reference container:

```sh
./mvnw verify
./tcf lint
./tcf lint --scan
./tcf --container 21 selftest
./tcf check
```

Observations of behaviours that are not reported upstream yet are not in this repository: their rows, baselines and anchors stay with the maintainer until the report is filed. A public checkout therefore runs and checks the published cells, `./tcf --container 21 run --jdk 21 --published`, which is also what the hosted workflows do. A full matrix run compared with `./tcf check --run <directory>` reports `NEW` for the other cells; that is expected, must not be suppressed, and such cells do not belong in a public issue or pull request.

Use only the fake keys `sk-test` and `test`. Never attach credentials, personal data or local paths. Cite fixture sources and licences. Imported recordings need a scrubbed provenance review. An unreviewed baseline is a failed check to investigate, not a value to accept automatically.

Before a local commit, review the diff, pass the checks, and use a plain Conventional Commit message without co-author or generated-with trailers. Keep hooks enabled. Result rows and baselines for an unfiled finding stay private. Judged public report cells wait for a maintainer answer or the configured reply window. Follow [SECURITY.md](SECURITY.md) for private-class observations.

Upstream reports are prepared with AI assistance and reviewed by the maintainer before submission, under the receiving project's current contribution rules. One behavior per report; no target-library pull request until its maintainers ask. A contribution here does not authorize sending material elsewhere.

Contributions are licensed under Apache-2.0 unless a clearly attributed imported fixture retains its own compatible licence. See [NOTICE](NOTICE) and the [code of conduct](CODE_OF_CONDUCT.md).
