# ADR 0008 — Licensing & rebrand (building on Apache-2.0 Headwind)

**Status:** Accepted (2026-06-21)

**Amended (2026-11-11):** MDMesh is not a proprietary product. It has been open source under Apache-2.0
([LICENSE](../../LICENSE)) since its first public release (v0.1, 2026-06-25), including the components written for
it, so what this record says about a proprietary product and keeping our code closed no longer applies. The
obligations to the upstream code (headers, `NOTICE`, change notices, no use of the Headwind marks) still do;
[NOTICE](../../NOTICE) has the current position.

## Context

The product forks Headwind MDM. We need to (a) confirm we can legally build a
proprietary product on it, and (b) avoid "legality hiccups" — chiefly trademark misuse.
Directive from the owner: use *just enough* of the upstream backend to get going, then
progressively replace it with our own code so we own the project.

## Findings

- **Both upstream repos are Apache-2.0** (`hmdm-server`, `hmdm-android`). Permissive, no
  copyleft: we may modify, redistribute, sublicense, and keep our additions closed/proprietary.
- No copyleft (GPL/AGPL/LGPL) dependency contamination found by name across the server's
  ~70 artifacts. (A formal `mvn license` scan is a pre-launch follow-up.)
- Headwind's "Enterprise/Pro" features (e.g. real COSU kiosk) are closed-source and are NOT in
  the Apache-2.0 community repo we forked — we build those ourselves; no license entanglement.

## Decision

Build a proprietary product on the Apache-2.0 base via a **strangler** approach: retain the
community backend as a foundation and replace modules with our own (closeable) code over time.
New components (`proto/`, React `web/`) are original works. The agent v1 endpoints were written for MDMesh, though
some carry the upstream header. The Kotlin agent is new, but parts of it are ported from the Headwind Android agent;
those files carry the upstream header and say what changed.

**Obligations we will honor:**
1. Keep upstream Apache license headers on retained/derived files; new files need no header.
2. Propagate `NOTICE` (Apache §4d) — upstream attribution retained, ours added (done in `NOTICE`).
3. Changed upstream files carry a notice saying so (§4b). Ported agent files state what changed; retained server
   files do not yet.
4. **No trademark use** (§6): the product is NOT named "Headwind"; no Headwind logos/marks; no
   implied endorsement. All product-facing surfaces get rebranded (starts with the React UI).
5. Run a formal dependency license scan + obtain legal sign-off before commercial launch.

## Consequences

- (+) Free to commercialize and keep our work closed; no obligation to upstream.
- (+) Strangler path de-risks: never blocked, migrate at our pace.
- (−) Must pick and apply a product brand (the `com.hmdm` package namespace is cosmetic legacy,
  not a legal issue; renaming it is optional cleanup). The new agent already uses `com.mdmesh`.
- This is practical guidance, not legal advice; §5 sign-off remains required.
