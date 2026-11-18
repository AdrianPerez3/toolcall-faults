# Disputing a cell

Use the cell-dispute issue form for a public, ordinary correctness disagreement. Follow [SECURITY.md](../SECURITY.md) instead for sensitive behavior. Do not publish an unfiled finding or private fixture in the form.

Provide the complete cell key from the result: target, version, module, surface, wire, scenario and variant, recorder tool, JDK and HTTP client. Include the suite commit, container environment, resolved dependency versions and fixture identifier/hash/provenance. Give the exact reproduction command and the outputs from three repetitions, scrubbed of secrets and local paths.

Say which layer you dispute:

- **Observation:** identify the recorded field that differs, such as tool arguments, requests, terminal signals or caller-visible error. Attach the evidence and explain the adapter or fixture configuration involved.
- **Judgement:** identify the judgement key and provider documentation, quote or link the relevant contract, and explain the proposed interpretation. An unchanged observation can acquire a corrected judgement.

Include a relevant upstream issue and the maintainers' position if one exists. A not-run cell has a declared control failure basis; resolving that declaration restores its selection. Do not infer its outcome from an unrelated error category.

The maintainer checks the supplied evidence, reruns the cell and records any correction with its reason. Historical observations are retained; a changed fixture or configuration gets its own provenance. Documentary uncertainty can leave a cell record-only. Target maintainers are welcome to correct adapter configuration and own their adapter.
