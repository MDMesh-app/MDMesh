# Console extensions

This folder is yours (a fork's). MDMesh ships only this README and `.gitkeep` here, so a merge from upstream never
touches what you put in it.

Add a feature to the console by adding a folder, `extensions/<id>/`, whose `index.ts` default-exports
`defineExtension({ id: '<id>', ... })` and imports only `@mdmesh/console`, `react` and its own files. The console
finds it at build time; no core file changes. `npm test` checks it.

[web/EXTENDING.md](../../EXTENDING.md) is the guide: every slot with an example, `requires`, `ORDER`, what happens
when something breaks, the server plugin side, testing and the stability promise.
