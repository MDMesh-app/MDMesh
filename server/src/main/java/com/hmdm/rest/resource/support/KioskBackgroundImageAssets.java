package com.hmdm.rest.resource.support;

import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.UploadedFileDAO;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.persistence.domain.UploadedFile;
import com.hmdm.security.SecurityContext;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/** Resolves a browser-selected uploaded file into immutable kiosk desired-state data. */
@Singleton
public class KioskBackgroundImageAssets {
    private final UploadedFileDAO uploadedFiles;
    private final CustomerDAO customers;
    private final ApkHashResolver hashes;
    private final String baseUrl;

    @Inject
    public KioskBackgroundImageAssets(UploadedFileDAO uploadedFiles, CustomerDAO customers,
                                      ApkHashResolver hashes, @Named("base.url") String baseUrl) {
        this.uploadedFiles = uploadedFiles;
        this.customers = customers;
        this.hashes = hashes;
        this.baseUrl = baseUrl;
    }

    /**
     * Applies the browser's explicit image operation. An omitted operation keeps the stored image, so an ordinary
     * configuration save cannot wipe a legacy launcher wallpaper merely because it has no managed uploaded-file id.
     * Browser-provided URL/hash values are never accepted.
     */
    public void apply(Configuration configuration, Configuration stored) {
        String action = configuration.getBackgroundImageAction();
        if (action == null || "keep".equals(action)) {
            copyStoredImage(configuration, stored);
            return;
        }
        if ("remove".equals(action)) {
            clearImage(configuration);
            return;
        }
        if (!"replace".equals(action)) {
            throw new IllegalArgumentException("backgroundImageAction must be keep, replace, or remove");
        }

        Integer id = configuration.getBackgroundImageFileId();
        if (id == null) {
            throw new IllegalArgumentException("backgroundImageFileId is required when replacing the background image");
        }
        UploadedFile file = uploadedFiles.getById(id);
        if (file == null || file.isExternal()) {
            throw new IllegalArgumentException("backgroundImageFileId must name an uploaded file of this customer");
        }
        Customer customer = SecurityContext.get().getCurrentUser()
                .map(user -> customers.findById(user.getCustomerId()))
                .orElseThrow(() -> new IllegalArgumentException("authenticated customer is required"));
        String url = file.getUrl(baseUrl, customer);
        try {
            configuration.setBackgroundImageUrl(url);
            configuration.setBackgroundImageSha256(hashes.sha256Hex(url));
        } catch (Exception e) {
            throw new IllegalArgumentException("background image file is unavailable", e);
        }
    }

    private void copyStoredImage(Configuration configuration, Configuration stored) {
        if (stored == null) {
            clearImage(configuration);
            return;
        }
        configuration.setBackgroundImageUrl(stored.getBackgroundImageUrl());
        configuration.setBackgroundImageFileId(stored.getBackgroundImageFileId());
        configuration.setBackgroundImageSha256(stored.getBackgroundImageSha256());
    }

    private void clearImage(Configuration configuration) {
        configuration.setBackgroundImageUrl(null);
        configuration.setBackgroundImageFileId(null);
        configuration.setBackgroundImageSha256(null);
    }
}
