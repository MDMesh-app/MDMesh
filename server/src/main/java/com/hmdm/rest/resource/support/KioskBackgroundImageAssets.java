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
     * Replaces browser-provided URL/hash values with values derived from a customer-owned hosted file.
     * A null file id explicitly clears the managed image. External URLs are never accepted here.
     */
    public void canonicalize(Configuration configuration) {
        Integer id = configuration.getBackgroundImageFileId();
        if (id == null) {
            configuration.setBackgroundImageUrl(null);
            configuration.setBackgroundImageSha256(null);
            return;
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
}
