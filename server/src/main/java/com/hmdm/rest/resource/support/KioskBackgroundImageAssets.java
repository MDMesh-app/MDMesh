package com.hmdm.rest.resource.support;

import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.UploadedFileDAO;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.persistence.domain.UploadedFile;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.BaseUrl;
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
import java.util.LinkedHashMap;
import java.util.Map;
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
    static final String STORAGE_FULL = "The image without its metadata doesn't fit in this account's file storage.";
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
        this.baseUrl = BaseUrl.trim(baseUrl);
        this.filesDirectory = filesDirectory;
    }

    /** What {@link #apply} did that a failed save must take back. */
    public static final class Applied {
        static final Applied NOTHING = new Applied(null, null, null);
        private final KioskBackgroundImageAssets owner;
        private final Customer customer;
        private final UploadedFile createdCopy;

        private Applied(KioskBackgroundImageAssets owner, Customer customer, UploadedFile createdCopy) {
            this.owner = owner;
            this.customer = customer;
            this.createdCopy = createdCopy;
        }

        /** Removes a cleaned copy this save hosted (row and file); a copy found from an earlier save stays. */
        public void undo() {
            if (createdCopy == null) return;
            owner.uploadedFiles.remove(createdCopy.getId());
            FileUtil.deleteFile(customer, owner.filesDirectory, createdCopy.getFilePath());
        }
    }

    /**
     * Applies a save to the image fields. {@code replace} uses the sent file id; {@code remove} clears the URL, file
     * id and hash. Otherwise ({@code keep} or none): an update keeps the stored managed image while the sent URL is
     * empty or the stored one; any other URL is a legacy wallpaper that replaces the managed image. A new
     * configuration with a file id gets that image (a copy of one that has it); without one, its URL is a legacy
     * wallpaper.
     *
     * @return what to take back if the configuration then fails to save
     * @throws IllegalArgumentException with a message for the admin, when the action or the image is refused
     */
    public Applied apply(Configuration configuration, Configuration stored) {
        String action = configuration.getBackgroundImageAction();
        if ("remove".equals(action)) {
            setImage(configuration, null, null, null);
            return Applied.NOTHING;
        }
        if ("replace".equals(action)) {
            if (configuration.getBackgroundImageFileId() == null) {
                throw new IllegalArgumentException("backgroundImageFileId is required when replacing the background image");
            }
            return useUploadedFile(configuration, configuration.getBackgroundImageFileId());
        }
        if (action != null && !"keep".equals(action)) {
            throw new IllegalArgumentException("backgroundImageAction must be keep, replace, or remove");
        }

        String sent = blankToNull(configuration.getBackgroundImageUrl());
        if (stored == null) {
            if (configuration.getBackgroundImageFileId() != null) {
                return useUploadedFile(configuration, configuration.getBackgroundImageFileId());
            }
            setImage(configuration, sent, null, null);
            return Applied.NOTHING;
        }
        if (stored.getBackgroundImageFileId() != null
                && (sent == null || sent.equals(stored.getBackgroundImageUrl()))) {
            setImage(configuration, stored.getBackgroundImageUrl(), stored.getBackgroundImageFileId(),
                    stored.getBackgroundImageSha256());
            return Applied.NOTHING;
        }
        // A legacy wallpaper URL, as sent: the admin set it over the managed image (if any), so that one is dropped.
        setImage(configuration, sent, null, null);
        return Applied.NOTHING;
    }

    /** An uploaded file of the current customer, resolved and access-checked; not read yet. */
    private static final class Resolved {
        final UploadedFile file;
        final Customer customer;
        final File hosted;

        Resolved(UploadedFile file, Customer customer, File hosted) {
            this.file = file;
            this.customer = customer;
            this.hosted = hosted;
        }
    }

    /** The bytes of a resolved file, checked against the agent's limits, and the same without metadata. */
    private static final class Read {
        final byte[] bytes;
        final byte[] clean;
        final KioskImageHeader header;

        Read(byte[] bytes, byte[] clean, KioskImageHeader header) {
            this.bytes = bytes;
            this.clean = clean;
            this.header = header;
        }

        boolean hasMetadata() {
            return !Arrays.equals(clean, bytes);
        }
    }

    private Resolved resolve(int fileId) {
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
        return new Resolved(file, customer, hosted);
    }

    private final java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();

    /** Files read and stripped so far (tests: describe's cache). */
    int reads() {
        return reads.get();
    }

    private Read read(Resolved resolved) {
        reads.incrementAndGet();
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(resolved.hosted.toPath());
        } catch (IOException e) {
            throw new IllegalArgumentException(MISSING, e);
        }
        // Limits from the header first: nothing here ever decodes the pixels.
        validate(bytes);
        byte[] clean = KioskImageMetadata.strip(bytes);
        if (clean == null) {
            throw new IllegalArgumentException(NOT_AN_IMAGE);
        }
        return new Read(bytes, clean, KioskImageHeader.read(clean));
    }

    /** Points the configuration at a hosted file of the current customer, once its bytes pass the agent's limits. */
    private Applied useUploadedFile(Configuration configuration, int fileId) {
        Resolved resolved = resolve(fileId);
        Read read = read(resolved);
        String sha256 = validate(read.clean);
        UploadedFile file = resolved.file;
        Applied applied = Applied.NOTHING;
        if (read.hasMetadata()) {
            // Metadata (an API upload, or one from before the console cleaned uploads): the image is hosted again
            // without it. The original is another uploaded file that may be used elsewhere, so it is never rewritten.
            UploadedFile[] created = new UploadedFile[1];
            file = hostCleanCopy(resolved.customer, file, read.clean, sha256, created);
            if (created[0] != null) applied = new Applied(this, resolved.customer, created[0]);
        }
        setImage(configuration, file.getUrl(baseUrl, resolved.customer), file.getId(), sha256);
        return applied;
    }

    /** Describe results by file and its modification: a GET doesn't read and strip up to 10 MB again. */
    private final Map<String, KioskBackgroundImageInfo> described = new LinkedHashMap<String, KioskBackgroundImageInfo>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, KioskBackgroundImageInfo> eldest) {
            return size() > 128;
        }
    };

    /**
     * What the console shows for a background image file: the image as devices will get it once a configuration is
     * saved with it (metadata removed, so size and SHA-256 are the cleaned file's), at the URL of today's base URL.
     *
     * @throws IllegalArgumentException with a message for the admin, as a save would refuse the file
     */
    public KioskBackgroundImageInfo describe(int fileId) {
        Resolved resolved = resolve(fileId);
        String key = resolved.file.getId() + "|" + resolved.hosted.getPath() + "|" + resolved.hosted.lastModified()
                + "|" + resolved.hosted.length();
        KioskBackgroundImageInfo cached;
        synchronized (described) {
            cached = described.get(key);
        }
        KioskBackgroundImageInfo info = cached != null ? copy(cached) : null;
        if (info == null) {
            Read read = read(resolved);
            info = new KioskBackgroundImageInfo();
            info.setFormat(read.header.format);
            int orientation = KioskImageMetadata.orientation(read.clean);
            boolean quarterTurn = orientation >= 5;
            info.setWidth(quarterTurn ? read.header.height : read.header.width);
            info.setHeight(quarterTurn ? read.header.width : read.header.height);
            info.setOrientation(orientation);
            info.setSize(read.clean.length);
            info.setSha256(validate(read.clean));
            info.setHasMetadata(read.hasMetadata());
            synchronized (described) {
                described.put(key, copy(info));
            }
        }
        info.setFileId(resolved.file.getId());
        info.setUrl(resolved.file.getUrl(baseUrl, resolved.customer));
        String path = resolved.file.getFilePath().replace('\\', '/');
        info.setName(KIOSK_PREFIX.matcher(path.substring(path.lastIndexOf('/') + 1)).replaceFirst(""));
        info.setUploadTime(resolved.file.getUploadTime());
        return info;
    }

    private static KioskBackgroundImageInfo copy(KioskBackgroundImageInfo i) {
        KioskBackgroundImageInfo c = new KioskBackgroundImageInfo();
        c.setFormat(i.getFormat());
        c.setWidth(i.getWidth());
        c.setHeight(i.getHeight());
        c.setOrientation(i.getOrientation());
        c.setSize(i.getSize());
        c.setSha256(i.getSha256());
        c.setHasMetadata(i.getHasMetadata());
        return c;
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

    private static final Pattern KIOSK_PREFIX = Pattern.compile("^kiosk-[0-9a-f]{12}-");
    /** Saves hosting a cleaned copy: one at a time, so two saves of one image find one file and one row. */
    private static final Object COPY_LOCK = new Object();

    /**
     * Hosts {@code clean} next to {@code original} as {@code kiosk-<12 hex of its SHA-256>-<name>}, or finds it there
     * from an earlier save of the same image.
     *
     * @param created set to the new row when this call hosted the copy (left null when it found one)
     */
    private UploadedFile hostCleanCopy(Customer customer, UploadedFile original, byte[] clean, String sha256,
                                       UploadedFile[] created) {
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
        synchronized (COPY_LOCK) {
            try {
                File target = FileUtil.resolveFile(customer, filesDirectory, null, copyPath);
                boolean wrote = false;
                if (target.isFile()) {
                    if (!Arrays.equals(clean, Files.readAllBytes(target.toPath()))) {
                        throw new IllegalArgumentException(NOT_STORED);
                    }
                } else {
                    checkStorage(customer, clean.length);
                    java.nio.file.Path staged = Files.createTempFile(target.getParentFile().toPath(), ".kiosk-", ".tmp");
                    try {
                        Files.write(staged, clean);
                        Files.move(staged, target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                        wrote = true;
                    } finally {
                        Files.deleteIfExists(staged);
                    }
                }
                UploadedFile existing = uploadedFiles.getByPath(customer.getId(), copyPath);
                if (existing != null && !existing.isExternal()) return existing;
                UploadedFile copy = new UploadedFile();
                copy.setCustomerId(customer.getId());
                copy.setFilePath(copyPath);
                copy.setDescription(original.getDescription());
                copy.setExternal(false);
                copy.setUploadTime(System.currentTimeMillis());
                UploadedFile inserted = uploadedFiles.insert(copy);
                // Only a copy this save wrote is taken back if the save fails; a file already there may be in use.
                if (wrote) created[0] = inserted;
                return inserted;
            } catch (UnsafePathException | IOException e) {
                throw new IllegalArgumentException(NOT_STORED, e);
            }
        }
    }

    /** The account's file storage limit (multi-tenant installs), as Files enforces it on upload. */
    private void checkStorage(Customer customer, long adding) {
        if (customer.isMaster() || customer.getSizeLimit() <= 0) return;
        File dir = new File(filesDirectory, customer.getFilesDir() == null ? "" : customer.getFilesDir());
        long used = dir.isDirectory() ? org.apache.commons.io.FileUtils.sizeOfDirectory(dir) : 0;
        if ((used + adding) / 1048576L > customer.getSizeLimit()) {
            throw new IllegalArgumentException(STORAGE_FULL);
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
