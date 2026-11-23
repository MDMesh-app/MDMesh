package com.hmdm.rest.resource.support;

import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.persistence.domain.UploadedFile;
import com.hmdm.util.BaseUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * The URL a device downloads a configuration's kiosk background image from, built from the server's CURRENT base URL
 * and the uploaded file, every time the desired state is built. The URL stored with the configuration on save is only
 * the fallback (and the legacy Headwind launcher's wallpaper): it freezes the base URL of the day the image was picked,
 * so an install later moved to HTTPS or to another domain would keep sending an address the agent refuses.
 */
@Singleton
public class KioskBackgroundImageUrls {
    private static final Logger logger = LoggerFactory.getLogger(KioskBackgroundImageUrls.class);

    private final UnsecureDAO unsecureDAO;
    private final String baseUrl;

    @Inject
    public KioskBackgroundImageUrls(UnsecureDAO unsecureDAO, @Named("base.url") String baseUrl) {
        this.unsecureDAO = unsecureDAO;
        this.baseUrl = BaseUrl.trim(baseUrl);
    }

    /**
     * The managed image's URL as of now; the stored URL when the configuration has no managed image (a legacy
     * wallpaper, or none), or when the file can't be resolved (row gone, external, unknown customer).
     */
    public String current(Configuration cfg) {
        String stored = cfg.getBackgroundImageUrl();
        Integer fileId = cfg.getBackgroundImageFileId();
        if (fileId == null || baseUrl == null || baseUrl.isEmpty()) return stored;
        try {
            UploadedFile file = unsecureDAO.getUploadedFileById(fileId);
            if (file == null || file.isExternal() || file.getFilePath() == null) return stored;
            // The file's own customer: a configuration copied from the master template points at the master's file.
            Customer owner = unsecureDAO.getCustomerByIdUnsecure(file.getCustomerId());
            if (owner == null) return stored;
            String built = file.getUrl(baseUrl, owner);
            // Saved under a base URL with a trailing slash: the same address, so the same document and revision.
            return BaseUrl.sameAddress(built, stored) ? stored : built;
        } catch (RuntimeException e) {
            logger.debug("kiosk background image {} of configuration {}: sending the stored URL", fileId, cfg.getId(), e);
            return stored;
        }
    }
}
