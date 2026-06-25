/*
 * MDMesh: F-Droid catalogue proxy.
 *
 * The F-Droid repository index (index-v2.json) is large (tens of MB) and is not
 * CORS-accessible from the browser, so the server fetches it, parses it with a
 * streaming parser (one package subtree at a time to bound heap), caches a slim
 * catalogue, and serves search results to the admin console. Deploy then uses
 * the public F-Droid APK URL + the sha256 from the index.
 */
package com.hmdm.rest.resource;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Api(tags = {"F-Droid"}, authorizations = {@Authorization("Bearer Token")})
@Singleton
@Path("/private/fdroid")
public class FDroidResource {

    private static final Logger logger = LoggerFactory.getLogger(FDroidResource.class);

    private static final String REPO = "https://f-droid.org/repo";
    private static final String INDEX_URL = REPO + "/index-v2.json";
    private static final long TTL_MILLIS = 6L * 60L * 60L * 1000L;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final ObjectMapper mapper = new ObjectMapper();

    private volatile List<FDroidApp> cache = null;
    private volatile long fetchedAt = 0L;

    public FDroidResource() {
    }

    // =================================================================================================================
    @ApiOperation(value = "Search F-Droid", notes = "Searches the cached F-Droid catalogue.")
    @GET
    @Path("/search")
    @Produces(MediaType.APPLICATION_JSON)
    public Response search(@QueryParam("q") @ApiParam("Search query") String q,
                           @QueryParam("limit") @ApiParam("Max results") Integer limit) {
        if (!SecurityContext.get().getCurrentUser().isPresent()) {
            return Response.PERMISSION_DENIED();
        }

        int max = limit == null ? DEFAULT_LIMIT : limit;
        if (max < 1) max = 1;
        if (max > MAX_LIMIT) max = MAX_LIMIT;

        final List<FDroidApp> catalogue;
        try {
            catalogue = getCatalogue();
        } catch (Exception e) {
            logger.error("Failed to fetch/parse the F-Droid index", e);
            return Response.ERROR("error.fdroid.fetch");
        }

        final String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        final List<FDroidApp> result = new ArrayList<>();
        for (FDroidApp app : catalogue) {
            if (needle.isEmpty() || app.matches(needle)) {
                result.add(app);
                if (result.size() >= max) {
                    break;
                }
            }
        }
        return Response.OK(result);
    }

    // -----------------------------------------------------------------------------------------------------------------
    private List<FDroidApp> getCatalogue() throws Exception {
        List<FDroidApp> local = cache;
        if (local != null && (System.currentTimeMillis() - fetchedAt) < TTL_MILLIS) {
            return local;
        }
        synchronized (this) {
            if (cache != null && (System.currentTimeMillis() - fetchedAt) < TTL_MILLIS) {
                return cache;
            }
            List<FDroidApp> fresh = fetchCatalogue();
            cache = fresh;
            fetchedAt = System.currentTimeMillis();
            return fresh;
        }
    }

    private List<FDroidApp> fetchCatalogue() throws Exception {
        logger.info("Fetching F-Droid index from {}", INDEX_URL);
        final List<FDroidApp> apps = new ArrayList<>();

        URL url = new URL(INDEX_URL);
        URLConnection conn = url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("User-Agent", "MDMesh");

        JsonFactory factory = mapper.getFactory();
        try (InputStream in = conn.getInputStream();
             JsonParser parser = factory.createParser(in)) {
            parser.setCodec(mapper);

            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("Unexpected F-Droid index root");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT && parser.currentToken() != null) {
                String field = parser.getCurrentName();
                parser.nextToken(); // value
                if ("packages".equals(field)) {
                    readPackages(parser, apps);
                } else {
                    parser.skipChildren();
                }
            }
        }

        apps.sort(Comparator.comparing(a -> a.name == null ? "" : a.name.toLowerCase(Locale.ROOT)));
        logger.info("F-Droid catalogue parsed: {} apps", apps.size());
        return apps;
    }

    private void readPackages(JsonParser parser, List<FDroidApp> apps) throws Exception {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            return;
        }
        while (parser.nextToken() != JsonToken.END_OBJECT && parser.currentToken() != null) {
            String packageName = parser.getCurrentName();
            parser.nextToken(); // START_OBJECT of the package
            JsonNode pkg = parser.readValueAsTree();
            FDroidApp app = toApp(packageName, pkg);
            if (app != null) {
                apps.add(app);
            }
        }
    }

    private FDroidApp toApp(String packageName, JsonNode pkg) {
        if (packageName == null || pkg == null) {
            return null;
        }
        JsonNode metadata = pkg.get("metadata");
        String name = localized(metadata == null ? null : metadata.get("name"));
        String summary = localized(metadata == null ? null : metadata.get("summary"));
        String iconUrl = localizedIcon(metadata == null ? null : metadata.get("icon"));

        // Pick the version with the highest versionCode.
        JsonNode versions = pkg.get("versions");
        long bestCode = Long.MIN_VALUE;
        JsonNode best = null;
        if (versions != null && versions.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = versions.fields();
            while (it.hasNext()) {
                JsonNode v = it.next().getValue();
                JsonNode manifest = v.get("manifest");
                long code = manifest != null && manifest.hasNonNull("versionCode")
                        ? manifest.get("versionCode").asLong() : 0L;
                if (best == null || code > bestCode) {
                    bestCode = code;
                    best = v;
                }
            }
        }
        if (best == null) {
            return null; // nothing installable
        }
        JsonNode manifest = best.get("manifest");
        JsonNode file = best.get("file");
        if (file == null || !file.hasNonNull("name")) {
            return null;
        }

        FDroidApp app = new FDroidApp();
        app.packageName = packageName;
        app.name = name != null ? name : packageName;
        app.summary = summary != null ? summary : "";
        app.iconUrl = iconUrl;
        app.versionName = manifest != null && manifest.hasNonNull("versionName")
                ? manifest.get("versionName").asText() : null;
        app.versionCode = bestCode == Long.MIN_VALUE ? 0L : bestCode;
        app.apkUrl = REPO + file.get("name").asText();
        app.sha256 = file.hasNonNull("sha256") ? file.get("sha256").asText() : null;
        app.size = file.hasNonNull("size") ? file.get("size").asLong() : null;
        return app;
    }

    /** A localized field is either a plain string or an {"en-US": "..."} map. */
    private String localized(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isObject()) {
            JsonNode en = node.get("en-US");
            if (en != null && en.isTextual()) {
                return en.asText();
            }
            Iterator<JsonNode> it = node.elements();
            if (it.hasNext()) {
                JsonNode first = it.next();
                if (first.isTextual()) {
                    return first.asText();
                }
            }
        }
        return null;
    }

    /** Icon is {"en-US": {"name": "/icons/..png"}}; resolve to a full URL. */
    private String localizedIcon(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode loc = node.get("en-US");
        if (loc == null) {
            Iterator<JsonNode> it = node.elements();
            if (it.hasNext()) {
                loc = it.next();
            }
        }
        if (loc != null && loc.hasNonNull("name")) {
            return REPO + loc.get("name").asText();
        }
        return null;
    }

    // -----------------------------------------------------------------------------------------------------------------
    public static class FDroidApp {
        private String packageName;
        private String name;
        private String summary;
        private String iconUrl;
        private String versionName;
        private long versionCode;
        private String apkUrl;
        private String sha256;
        private Long size;

        boolean matches(String needle) {
            return (name != null && name.toLowerCase(Locale.ROOT).contains(needle))
                    || (summary != null && summary.toLowerCase(Locale.ROOT).contains(needle))
                    || (packageName != null && packageName.toLowerCase(Locale.ROOT).contains(needle));
        }

        public String getPackageName() { return packageName; }
        public String getName() { return name; }
        public String getSummary() { return summary; }
        public String getIconUrl() { return iconUrl; }
        public String getVersionName() { return versionName; }
        public long getVersionCode() { return versionCode; }
        public String getApkUrl() { return apkUrl; }
        public String getSha256() { return sha256; }
        public Long getSize() { return size; }
    }
}
