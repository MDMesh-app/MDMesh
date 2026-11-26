# Notes for coding agents

Guidance for AI coding agents (Claude Code, Codex, Cursor and others) working on MDMesh. People follow the same rules.
[CONTRIBUTING.md](CONTRIBUTING.md) is the full guide, and this page only points into it and adds what agents tend to miss.

## Find your way
- [STRUCTURE.md](STRUCTURE.md): the repository map. [CONTRIBUTING.md](CONTRIBUTING.md#build-and-test): build and test commands for each plane. [docs/DEV.md](docs/DEV.md): the dev stack.
- [docs/adr/](docs/adr): read the matching ADR before changing signing, device IDs, the agent contract or licensing.
- [proto/](proto): the agent–server protocol. [proto/VERSIONING.md](proto/VERSIONING.md) has the rules.

## Rules that CI or review will hold you to
- **The agent contract is additive-only.** New request fields are optional and defaulted. Never remove, rename or repurpose a field. A break means `/agent/v2` ([ADR-0009](docs/adr/0009-agent-v1-contract-stability.md)).
- **One logical change per PR, kept small** (a few hundred lines of non-test code). Split anything bigger, and open an issue first for a large feature.
- **Tests for behaviour changes**, in the plane's existing suites and with its existing helpers.
- **Docs and a changelog line** under Unreleased in [CHANGELOG.md](CHANGELOG.md), written for the people running MDMesh. Anything an operator must do when updating also goes in [docs/UPGRADING.md](docs/UPGRADING.md).
- **Licence headers.** A file kept from the upstream project keeps its header, and `scripts/licenses/headers.py check` must pass ([docs/licenses/policy.md](docs/licenses/policy.md)). New dependencies must be under a licence in [docs/licenses/allowed.txt](docs/licenses/allowed.txt).
- **Security issues** go through [SECURITY.md](SECURITY.md), never a public issue or PR.

## How we like code
- Use the established tool or library over hand-written code: a real parser, `docker compose config`, `pg_dump`, the framework's own feature. Don't write your own parser for a format something already reads.
- Don't copy and paste. Reuse or extract the existing helper, in tests too.
- Keep it as simple as the problem allows, with no speculative options or layers. Readable beats clever.
- Make install, update and data paths fail safe: stop with a clear message rather than guess, and never delete data the code didn't create.
- Match the surrounding style, and keep comments and docs short and plain.

## Commits and PRs
- Branch off `main`, use short imperative commit messages with a prefix (`feat:`, `fix:`, `docs:` …), and fill in the [PR template](.github/pull_request_template.md).
- Leave AI attribution trailers (`Co-Authored-By`, generated-by lines, session links) out of commits and PR text.
- Say what you actually ran to test the change. Don't claim CI or checks that didn't run.
