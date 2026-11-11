package com.hmdm.rest.resource.support;

import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.UploadedFileDAO;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.persistence.domain.UploadedFile;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.CryptoUtil;
import com.hmdm.util.FileUtil;
import com.hmdm.util.UnsafePathException;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Resolves the kiosk background image of a configuration save. The managed image is an uploaded file of the
 * customer; its URL and SHA-256 always come from the hosted bytes, never from the browser. Those bytes are free of
 * metadata ({@link KioskImageMetadata}): an image that still has some is hosted again without it, and the SHA-256 is
 * that of the cleaned file. The same
 * {@code backgroundImageUrl} column is also the legacy Headwind launcher wallpaper, which an admin or an API client may
 * still set as a plain URL.
 */
@Singleton
public class KioskBackgroundImageAssets {

    /** The agent refuses a larger download (KioskBackgroundImageStore.MAX_BYTES). */
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    /** The agent refuses a larger image (KioskBackgroundImageStore.MAX_PIXELS). */
    public static final long MAX_PIXELS = 16L * 1024 * 1024;

    static final String NOT_AN_IMAGE = "The background image must be a PNG, JPEG or WebP file.";
    static final String TOO_LARGE = "The background image is larger than 10 MB.";
    static final String TOO_MANY_PIXELS = "The background image is larger than 16 megapixels.";
    static final String NOT_FOUND = "The background image is not an uploaded file of this account.";
    static final String NOT_STORED = "The server could not store the background image without its metadata. Try again.";
    static final String MISSING = "The background image file is missing on the server. Choose the image again.";

    private final UploadedFileDAO uploadedFiles;
    private final CustomerDAO customers;
    private final String baseUrl;
    private final String filesDirectory;

    @Inject
    public KioskBackgroundImageAssets(UploadedFileDAO uploadedFiles, CustomerDAO customers,
                                      @Named("base.url") String baseUrl,
                                      @Named("files.directory") String filesDirectory) {
        this.uploadedFiles = uploadedFiles;
        this.customers = customers;
        this.baseUrl = baseUrl;
        this.filesDirectory = filesDirectory;
    }

    /**
     * Applies a save to the image fields. {@code replace} uses the sent file id; {@code remove} clears the URL, file
     * id and hash. Otherwise ({@code keep} or none): an update keeps the stored managed image while the sent URL is
     * empty or the stored one; any other URL is a legacy wallpaper that replaces the managed image. A new
     * configuration with a file id gets that image (a copy of one that has it); without one, its URL is a legacy
     * wallpaper.
     *
     * @throws IllegalArgumentException with a message for the admin, when the action or the image is refused
     */
    public void apply(Configuration configuration, Configuration stored) {
        String action = configuration.getBackgroundImageAction();
        if ("remove".equals(action)) {
            setImage(configuration, null, null, null);
            return;
        }
        if ("replace".equals(action)) {
            if (configuration.getBackgroundImageFileId() == null) {
                throw new IllegalArgumentException("backgroundImageFileId is required when replacing the background image");
            }
            useUploadedFile(configuration, configuration.getBackgroundImageFileId());
            return;
        }
        if (action != null && !"keep".equals(action)) {
            throw new IllegalArgumentException("backgroundImageAction must be keep, replace, or remove");
        }

        String sent = blankToNull(configuration.getBackgroundImageUrl());
        if (stored == null) {
            if (configuration.getBackgroundImageFileId() != null) {
                useUploadedFile(configuration, configuration.getBackgroundImageFileId());
            } else {
                setImage(configuration, sent, null, null);
            }
            return;
        }
        if (stored.getBackgroundImageFileId() != null
                && (sent == null || sent.equals(stored.getBackgroundImageUrl()))) {
            setImage(configuration, stored.getBackgroundImageUrl(), stored.getBackgroundImageFileId(),
                    stored.getBackgroundImageSha256());
            return;
        }
        // A legacy wallpaper URL, as sent: the admin set it over the managed image (if any), so that one is dropped.
        setImage(configuration, sent, null, null);
    }

    /** An uploaded file of the current customer and its bytes, checked against the agent's limits. */
    private static final class Hosted {
        final UploadedFile file;
        final Customer customer;
        final byte[] bytes;
        final byte[] clean;
        final KioskImageHeader header;

        Hosted(UploadedFile file, Customer customer, byte[] bytes, byte[] clean, KioskImageHeader header) {
            this.file = file;
            this.customer = customer;
            this.bytes = bytes;
            this.clean = clean;
            this.header = header;
        }

        boolean hasMetadata() {
            return !Arrays.equals(clean, bytes);
        }
    }

    private Hosted load(int fileId) {
        UploadedFile file;
        try {
            file = uploadedFiles.getById(fileId);
        } catch (com.hmdm.security.SecurityException e) {
            // Another customer's file.
            throw new IllegalArgumentException(NOT_FOUND);
        }
        if (file == null || file.isExternal() || file.getFilePath() == null) {
            throw new IllegalArgumentException(NOT_FOUND);
        }
        Customer customer = SecurityContext.get().getCurrentUser()
                .map(user -> customers.findById(user.getCustomerId()))
                .orElseThrow(() -> new IllegalArgumentException("authenticated customer is required"));
        if (customer == null || !Objects.equals(customer.getId(), file.getCustomerId())) {
            throw new IllegalArgumentException(NOT_FOUND);
        }
        File hosted;
        try {
            hosted = FileUtil.resolveFile(customer, filesDirectory, null, file.getFilePath());
        } catch (UnsafePathException e) {
            throw new IllegalArgumentException(NOT_FOUND);
        }
        if (!hosted.isFile()) {
            throw new IllegalArgumentException(MISSING);
        }
        if (hosted.length() > MAX_BYTES) {
            throw new IllegalArgumentException(TOO_LARGE);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(hosted.toPath());
        } catch (IOException e) {
            throw new IllegalArgumentException(MISSING, e);
        }
        // Limits from the header first: nothing here ever decodes the pixels.
        validate(bytes);
        byte[] clean = KioskImageMetadata.strip(bytes);
        if (clean == null) {
            throw new IllegalArgumentException(NOT_AN_IMAGE);
        }
        return new Hosted(file, customer, bytes, clean, KioskImageHeader.read(clean));
    }

    /** Points the configuration at a hosted file of the current customer, once its bytes pass the agent's limits. */
    private void useUploadedFile(Configuration configuration, int fileId) {
        Hosted hosted = load(fileId);
        String sha256 = validate(hosted.clean);
        UploadedFile file = hosted.file;
        if (hosted.hasMetadata()) {
            // Metadata (an API upload, or one from before the console cleaned uploads): the image is hosted again
            // without it. The original is another uploaded file that may be used elsewhere, so it is never rewritten.
            file = hostCleanCopy(hosted.customer, file, hosted.clean, sha256);
        }
        setImage(configuration, file.getUrl(baseUrl, hosted.customer), file.getId(), sha256);
    }

    /**
     * What the console shows for a background image file: the image as devices will get it once a configuration is
     * saved with it (metadata removed, so size and SHA-256 are the cleaned file's), at the URL of today's base URL.
     *
     * @throws IllegalArgumentException with a message for the admin, as a save would refuse the file
     */
    public KioskBackgroundImageInfo describe(int fileId) {
        Hosted hosted = load(fileId);
        KioskBackgroundImageInfo info = new KioskBackgroundImageInfo();
        info.setFileId(hosted.file.getId());
        info.setUrl(hosted.file.getUrl(baseUrl, hosted.customer));
        String path = hosted.file.getFilePath().replace('\\', '/');
        info.setName(KIOSK_PREFIX.matcher(path.substring(path.lastIndexOf('/') + 1)).replaceFirst(""));
        info.setFormat(hosted.header.format);
        int orientation = KioskImageMetadata.orientation(hosted.clean);
        boolean quarterTurn = orientation >= 5;
        info.setWidth(quarterTurn ? hosted.header.height : hosted.header.width);
        info.setHeight(quarterTurn ? hosted.header.width : hosted.header.height);
        info.setOrientation(orientation);
        info.setSize(hosted.clean.length);
        info.setSha256(validate(hosted.clean));
        info.setUploadTime(hosted.file.getUploadTime());
        info.setMetadataRemoved(hosted.hasMetadata());
        return info;
    }

    private static final Pattern KIOSK_PREFIX = Pattern.compile("^kiosk-[0-9a-f]{12}-");

    /**
     * Hosts {@code clean} next to {@code original} as {@code kiosk-<12 hex of its SHA-256>-<name>}, or finds it there
     * from an earlier save of the same image.
     */
    private UploadedFile hostCleanCopy(Customer customer, UploadedFile original, byte[] clean, String sha256) {
        String path = original.getFilePath().replace('\\', '/');
        int slash = path.lastIndexOf('/');
        String dir = slash < 0 ? "" : path.substring(0, slash + 1);
        String name = KIOSK_PREFIX.matcher(path.substring(slash + 1)).replaceFirst("");
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 ? name.substring(dot).toLowerCase(Locale.ROOT).replaceAll("[^.a-z0-9]", "") : "";
        String stem = (dot > 0 ? name.substring(0, dot) : name).replaceAll("[^A-Za-z0-9_-]+", "-");
        if (stem.length() > 40) stem = stem.substring(0, 40);
        if (stem.isEmpty()) stem = "image";
        String copyPath = dir + "kiosk-" + sha256.substring(0, 12) + "-" + stem + ext;
        try {
            File target = FileUtil.resolveFile(customer, filesDirectory, null, copyPath);
            if (target.isFile()) {
                if (!Arrays.equals(clean, Files.readAllBytes(target.toPath()))) {
                    throw new IllegalArgumentException(NOT_STORED);
                }
            } else {
                File staged = new File(target.getParentFile(), "." + target.getName() + ".tmp");
                Files.write(staged.toPath(), clean);
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            }
            UploadedFile existing = uploadedFiles.getByPath(customer.getId(), copyPath);
            if (existing != null && !existing.isExternal()) return existing;
            UploadedFile copy = new UploadedFile();
            copy.setCustomerId(customer.getId());
            copy.setFilePath(copyPath);
            copy.setDescription(original.getDescription());
            copy.setExternal(false);
            copy.setUploadTime(System.currentTimeMillis());
            return uploadedFiles.insert(copy);
        } catch (UnsafePathException | IOException e) {
            throw new IllegalArgumentException(NOT_STORED, e);
        }
    }

    /**
     * Refuses bytes the agent would not show (format, size, pixel count, read from the header only).
     *
     * @return the lowercase hex SHA-256 of the bytes
     */
    static String validate(byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException(TOO_LARGE);
        }
        KioskImageHeader header = KioskImageHeader.read(bytes);
        if (header == null) {
            throw new IllegalArgumentException(NOT_AN_IMAGE);
        }
        if ((long) header.width * header.height > MAX_PIXELS) {
            throw new IllegalArgumentException(TOO_MANY_PIXELS);
        }
        try {
            return CryptoUtil.getLowerHexString(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setImage(Configuration configuration, String url, Integer fileId, String sha256) {
        configuration.setBackgroundImageUrl(url);
        configuration.setBackgroundImageFileId(fileId);
        configuration.setBackgroundImageSha256(sha256);
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s;
    }
}
