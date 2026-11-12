package com.hmdm.rest.resource.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.util.BaseUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The managed kiosk background image in a {@code kiosk.enter}, at the address config.apply sends. The console's Enter
 * kiosk (one device, or many) carries the configuration's launcher theme with the image URL stored when the image was
 * picked; once the install moved to HTTPS or another domain, that address is one the agent refuses, while config.apply
 * already sends the new one ({@link KioskBackgroundImageUrls}). So as a {@code kiosk.enter} is queued, an image that is
 * a configuration's managed image (the account's configuration with these bytes, at its stored or current address)
 * gets the URL built from the server's current base URL, by the same function config.apply uses. Every queued
 * {@code kiosk.enter} passes here, from the console or an API client, single or bulk.
 *
 * <p>Nothing else in the payload changes, and the payload is queued exactly as sent when there is nothing to change:
 * no image, an image elsewhere (a client's own address, even for the same bytes), an unreadable payload (the agent
 * reports it), or a file that can't be resolved (the stored address goes out, as config.apply would send it).</p>
 */
@Singleton
public class KioskCommandImages {
    private static final Logger logger = LoggerFactory.getLogger(KioskCommandImages.class);

    public static final String KIOSK_ENTER = "kiosk.enter";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UnsecureDAO unsecureDAO;
    private final KioskBackgroundImageUrls urls;

    @Inject
    public KioskCommandImages(UnsecureDAO unsecureDAO, KioskBackgroundImageUrls urls) {
        this.unsecureDAO = unsecureDAO;
        this.urls = urls;
    }

    /**
     * @param customerId the account queueing the command: only its configurations say where an image lives
     * @return {@code payload} with the managed image's current URL, or {@code payload} itself
     */
    public String forCommand(String type, String payload, int customerId) {
        if (type == null || !KIOSK_ENTER.equalsIgnoreCase(type.trim()) || payload == null) return payload;
        JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (IOException e) {
            return payload;
        }
        JsonNode theme = root == null ? null : root.get("theme");
        JsonNode image = theme == null ? null : theme.get("backgroundImage");
        if (!(image instanceof ObjectNode)) return payload;
        JsonNode url = image.get("url");
        JsonNode sha = image.get("sha256");
        if (url == null || !url.isTextual() || sha == null || !sha.isTextual()) return payload;
        String sent = url.asText().trim();
        String sha256 = sha.asText().trim().toLowerCase(Locale.ROOT);
        if (sent.isEmpty() || !SHA256.matcher(sha256).matches()) return payload;

        String current;
        try {
            current = currentUrl(customerId, sent, sha256);
        } catch (RuntimeException e) {
            // As sent: the stored address, which the device shows or falls back from to the colour.
            logger.warn("kiosk.enter: could not look up the background image's current URL; queued as sent", e);
            return payload;
        }
        if (current == null || current.equals(url.asText())) return payload;
        ((ObjectNode) image).put("url", current);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return payload;
        }
    }

    /**
     * The current URL of the account's managed image with these bytes, when {@code sent} is its stored or current
     * address; null when no configuration of the account has it there.
     */
    private String currentUrl(int customerId, String sent, String sha256) {
        List<Configuration> candidates = unsecureDAO.getManagedBackgroundImageConfigurations(customerId, sha256);
        for (Configuration c : candidates) {
            String current = urls.current(c);
            if (BaseUrl.sameAddress(sent, c.getBackgroundImageUrl()) || BaseUrl.sameAddress(sent, current)) {
                return current;
            }
        }
        return null;
    }
}
