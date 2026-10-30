# Fork plugins (server)

This folder is yours (a fork's). MDMesh ships `pom.xml`, `bundle/pom.xml`, `public-routes.txt` and this README here,
and never changes the two poms' `<modules>` or `<dependencies>` or the routes in `public-routes.txt`, so the lines you
add to them stay yours: adding a server plugin edits nothing outside this folder, and a merge from upstream stays
clean.

To add a plugin: put it in `plugins/fork/<id>/` with a `pom.xml` whose parent is `fork`, list it as a `<module>` in
`pom.xml` and as a `<dependency>` in `bundle/pom.xml`, and implement `com.hmdm.plugin.PluginConfiguration`. Put the
plugin's classes in a package under `com.mdmesh`, by convention `com.mdmesh.<package>`, `<package>` being the id
without dashes. The server finds a `PluginConfiguration`, and serves JAX-RS resources, only below `com.mdmesh` and
`com.hmdm`; `com.hmdm` is core's. List the plugin's public routes (any not below `/private/`) in `public-routes.txt`,
and put its `/private/` routes through `AuthFilter` and then `PluginAccessFilter` in its `ServletModule`, or the server
build's tests fail. Don't use `init-plugin.sh`: it edits core's poms.

[web/EXTENDING.md](../../web/EXTENDING.md#server-plugin) is the guide: the pom, the one-id rule, the conventions (REST,
tables, Liquibase, permissions, the `plugins` row), public routes, configuration data and its validator, policy keys
in config.apply, and troubleshooting. The console side is `web/src/extensions/`.
