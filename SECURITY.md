# Reporting security problems

For a security problem in toolcall-faults itself, use the repository's **Security → Advisories → Report a vulnerability** action when private reporting is enabled. That setting is separate from this file; this pre-release checkout does not establish that the hosted route is enabled.

If the action is unavailable, open an issue asking only for a private contact route. Do not include sensitive details, reproduction bytes, credentials or personal data in that issue. This follows [GitHub's private reporting instructions](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/report-privately).

In the private channel, provide the affected commit, impact, minimal steps and scrubbed evidence. Keep ordinary correctness and cell disputes in the [cell dispute process](docs/disputing-a-cell.md).

For target-library observations involving approval bypass, unbounded resource use on default settings, mixed users' responses or a repeat of a published advisory, keep the fixture and result private and use that target's security policy. Do not attach such material to a public suite issue. The suite's ordinary disclosure window does not authorize publishing private-class findings. A behaviour that the target project already tracks in a public issue is not treated as private-class here: its rows may be published with a link to that issue.

No stable release is available yet. Corrections are made on the development branch; no support window for older releases is promised.
