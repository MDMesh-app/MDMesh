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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
    /** A configuration copy whose source's image was deleted meanwhile. */
    public static final String COPY_SOURCE_DELETED = "That configuration's background image was deleted meanwhile. Try again.";
    public static final String BEING_EDITED = "That image is being changed in Files right now. Try again in a moment.";
    static final String DELETED = "That image was deleted meanwhile. Choose or upload an image again.";
    static final String NOT_A_GALLERY_IMAGE = "Only kiosk background images can be deleted here. Use Files for other files.";
    static final String IN_USE = "This image is used by a configuration. Choose another image there first, then delete it.";
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

    /**
     * What {@link #apply} did: the file the save now holds (until {@link #release()}) and a cleaned copy it hosted,
     * which a failed save takes back ({@link #undo}).
     */
    public static final class Applied {
        static final Applied NOTHING = new Applied(null, null, null, null);
        private final KioskBackgroundImageAssets owner;
        private final Customer customer;
        private final UploadedFile createdCopy;
        private final Integer heldId;
        private boolean released;

        private Applied(KioskBackgroundImageAssets owner, Customer customer, UploadedFile createdCopy, Integer heldId) {
            this.owner = owner;
            this.customer = customer;
            this.createdCopy = createdCopy;
            this.heldId = heldId;
        }

        /**
         * Removes a cleaned copy this save hosted (row and file), unless another save holds it or has picked it up
         * since, or a configuration references it ({@code inUse}, asked under the same lock). A copy found from an
         * earlier save is never removed.
         *
         * @param inUse whether any configuration references a file id (ConfigurationDAO.isBackgroundImageFileUsed)
         */
        public void undo(java.util.function.IntPredicate inUse) {
            if (createdCopy == null) return;
            synchronized (COPY_LOCK) {
                int id = createdCopy.getId();
                if (owner.reused.contains(id) || owner.heldByOthers(id, this) || inUse.test(id)) return;
                owner.uploadedFiles.remove(id);
                FileUtil.deleteFile(customer, owner.filesDirectory, createdCopy.getFilePath());
            }
        }

        /**
         * Lets go of the file this save chose: call it once the configuration is written (it references the file from
         * then on) or the save failed. Idempotent.
         */
        public void release() {
            if (heldId == null || released) return;
            released = true;
            owner.unhold(heldId);
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
            // Kept, and held until written like a new choice. Strict: the hold re-checks under the lock that the file is
            // still there, so a replace and delete that ran after the stored configuration was read refuse this save
            // instead of letting it write an id that points at nothing.
            return holdExisting(stored.getBackgroundImageFileId(), true);
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
        // Held before its bytes are read and hashed, until the save is written or fails: a gallery delete, a Files edit
        // or another save's undo leaves it alone meanwhile, and one that got there first fails this save cleanly.
        Applied held = holdExisting(fileId, true);
        try {
            if (beforeRead != null) beforeRead.run();
            Read read = read(resolved);
            String sha256 = validate(read.clean);
            UploadedFile file = resolved.file;
            if (read.hasMetadata()) {
                // Metadata (an API upload, or one from before the console cleaned uploads): the image is hosted again
                // without it. The original is another uploaded file that may be used elsewhere, so it is never
                // rewritten. The copy is held as it is created or found (under the lock); the original is let go.
                UploadedFile[] created = new UploadedFile[1];
                file = hostCleanCopy(resolved.customer, file, read.clean, sha256, created);
                Applied copy = new Applied(this, resolved.customer, created[0], file.getId());
                held.release();
                held = copy;
            }
            setImage(configuration, file.getUrl(baseUrl, resolved.customer), file.getId(), sha256);
            return held;
        } catch (RuntimeException e) {
            held.release();
            throw e;
        }
    }

    /**
     * Holds an uploaded file a configuration is about to keep or copy, until {@link Applied#release()}.
     *
     * @param strict refuse (DELETED) when the file is gone; otherwise go on without a hold (an update that keeps
     *               the image it already has must stay saveable)
     */
    public Applied holdExisting(int fileId, boolean strict) {
        return hold(fileId, strict) ? new Applied(this, null, null, fileId) : Applied.NOTHING;
    }

    /** Files chosen by saves not written yet: file id to the number of such saves. */
    private final Map<Integer, java.util.concurrent.atomic.AtomicInteger> held = new java.util.concurrent.ConcurrentHashMap<>();
    /** Tests: runs just before a save takes hold of its file (to put a delete in between). */
    Runnable beforeHold;

    /** Tests: runs once a save holds its file, just before its bytes are read. */
    Runnable beforeRead;

    private boolean hold(int fileId, boolean strict) {
        if (beforeHold != null) beforeHold.run();
        synchronized (COPY_LOCK) {
            boolean exists;
            try {
                exists = uploadedFiles.getById(fileId) != null;
            } catch (com.hmdm.security.SecurityException e) {
                // Another account's file: the row exists. A configuration copied from the master account's template
                // uses the master's image, as released versions do; holding it by id reads and shows nothing of it.
                exists = true;
            }
            if (!exists) {
                if (strict) throw new IllegalArgumentException(DELETED);
                return false;
            }
            if (editing.contains(fileId)) throw new IllegalArgumentException(BEING_EDITED);
            holdLocked(fileId);
            return true;
        }
    }

    /** Counts a hold. Call under COPY_LOCK. */
    private void holdLocked(int fileId) {
        held.computeIfAbsent(fileId, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
    }

    private void unhold(int fileId) {
        synchronized (COPY_LOCK) {
            java.util.concurrent.atomic.AtomicInteger n = held.get(fileId);
            if (n != null && n.decrementAndGet() <= 0) held.remove(fileId);
        }
    }

    /** A save not written yet holds the file (other than {@code self}). Call under COPY_LOCK. */
    private boolean heldByOthers(int fileId, Applied self) {
        java.util.concurrent.atomic.AtomicInteger n = held.get(fileId);
        int mine = self != null && self.heldId != null && self.heldId == fileId && !self.released ? 1 : 0;
        return n != null && n.get() > mine;
    }

    /** Files being changed through Files → edit: no save takes hold of them meanwhile. */
    private final java.util.Set<Integer> editing = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** How a {@link #beginEdit} went. */
    public enum EditStart {
        /** The edit may run; call {@link #endEdit} when it is done. */
        STARTED,
        /** A configuration save holds the file. */
        HELD,
        /** Another edit of the file is running. */
        EDITING
    }

    /**
     * Starts a Files → edit of {@code fileId}: refused while a save holds it or another edit runs; otherwise no save
     * can take hold of it, and no delete removes it, until {@link #endEdit}. Only this check runs under the lock; the
     * edit's file I/O runs outside it. The caller checks first that the file belongs to the current account.
     */
    public EditStart beginEdit(int fileId) {
        synchronized (COPY_LOCK) {
            if (heldByOthers(fileId, null)) return EditStart.HELD;
            return editing.add(fileId) ? EditStart.STARTED : EditStart.EDITING;
        }
    }

    public void endEdit(int fileId) {
        editing.remove(fileId);
    }

    /** A save not written yet holds the file. */
    public boolean isHeld(int fileId) {
        synchronized (COPY_LOCK) {
            return heldByOthers(fileId, null);
        }
    }

    /**
     * Runs {@code action} under the lock saves take hold of files with, unless a save holds {@code fileId}: the Files
     * page's Remove goes through here, so it can't remove a file a save is about to commit.
     *
     * @return the action's result, {@code whenHeld} (nothing run) when a save holds the file, or {@code whenEditing}
     * (nothing run) while a Files → edit of it runs
     */
    public <T> T unlessHeld(int fileId, java.util.function.Supplier<T> action, T whenHeld, T whenEditing) {
        synchronized (COPY_LOCK) {
            if (heldByOthers(fileId, null)) return whenHeld;
            if (editing.contains(fileId)) return whenEditing;
            return action.get();
        }
    }

    /** Describe results by file and its modification: a GET doesn't read and strip up to 10 MB again. */
    private final Map<String, KioskBackgroundImageInfo> described = new LinkedHashMap<String, KioskBackgroundImageInfo>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, KioskBackgroundImageInfo> eldest) {
            // Small entries (no pixels): enough for every kiosk image of a large customer, so the gallery reads a file
            // once per change, not once per page.
            return size() > 4096;
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

    /** A configuration using an image (one the user may see). */
    @lombok.Getter
    @lombok.Setter
    public static final class Use {
        private int id;
        private String name;
    }

    /** One image of the gallery: one per content (SHA-256), with every uploaded file holding it. */
    @lombok.Getter
    @lombok.Setter
    public static final class GalleryItem extends KioskBackgroundImageInfo {
        /** Every uploaded file with these bytes; {@link #getFileId()} is the one to use. */
        private List<Integer> fileIds;
        /** The configurations using it that the user may see. */
        private List<Use> usedBy;
        /** All configurations using it, seen or not. */
        private int usedByCount;
    }

    @lombok.Getter
    @lombok.Setter
    public static final class GalleryPage {
        private List<GalleryItem> items;
        private int total;
        private int page;
        private int size;
    }

    private static final Pattern KIOSK_NAME = Pattern.compile("(^|/)kiosk-[0-9a-f]{12}-[^/]*$");

    /**
     * The current customer's kiosk background images: files hosted under the console's {@code kiosk-<hex>-} name and
     * files a configuration uses, readable as images, once per content, newest first.
     *
     * @param usesByFile  configurations using each file id (all of the customer's)
     * @param hiddenUses  configuration ids the user may not see: counted, not named
     */
    public GalleryPage gallery(Map<Integer, List<Use>> usesByFile, java.util.Set<Integer> hiddenUses, int page, int size) {
        Map<String, List<KioskBackgroundImageInfo>> bySha = new LinkedHashMap<>();
        Map<Integer, Long> uploaded = new java.util.HashMap<>();
        for (UploadedFile f : uploadedFiles.getAll()) {
            if (f.isExternal() || f.getFilePath() == null) continue;
            boolean used = usesByFile.containsKey(f.getId());
            if (!used && !KIOSK_NAME.matcher(f.getFilePath().replace('\\', '/')).find()) continue;
            KioskBackgroundImageInfo info;
            try {
                info = describe(f.getId());
            } catch (IllegalArgumentException e) {
                continue; // not an image the agent would show, or missing
            }
            uploaded.put(f.getId(), f.getUploadTime() == null ? 0L : f.getUploadTime());
            bySha.computeIfAbsent(info.getSha256(), k -> new ArrayList<>()).add(info);
        }
        List<GalleryItem> items = new ArrayList<>();
        Map<GalleryItem, Long> newest = new java.util.HashMap<>();
        for (List<KioskBackgroundImageInfo> same : bySha.values()) {
            // The file to pick: the one most configurations use, then the newest.
            same.sort((a, b) -> {
                int ua = usesByFile.getOrDefault(a.getFileId(), Collections.emptyList()).size();
                int ub = usesByFile.getOrDefault(b.getFileId(), Collections.emptyList()).size();
                if (ua != ub) return Integer.compare(ub, ua);
                return Long.compare(uploaded.get(b.getFileId()), uploaded.get(a.getFileId()));
            });
            GalleryItem item = new GalleryItem();
            KioskBackgroundImageInfo pick = same.get(0);
            item.setFileId(pick.getFileId());
            item.setUrl(pick.getUrl());
            item.setName(pick.getName());
            item.setFormat(pick.getFormat());
            item.setWidth(pick.getWidth());
            item.setHeight(pick.getHeight());
            item.setOrientation(pick.getOrientation());
            item.setSize(pick.getSize());
            item.setSha256(pick.getSha256());
            item.setUploadTime(pick.getUploadTime());
            item.setHasMetadata(pick.getHasMetadata());
            List<Integer> ids = new ArrayList<>();
            Map<Integer, Use> uses = new LinkedHashMap<>();
            long latest = 0;
            for (KioskBackgroundImageInfo i : same) {
                ids.add(i.getFileId());
                latest = Math.max(latest, uploaded.get(i.getFileId()));
                for (Use u : usesByFile.getOrDefault(i.getFileId(), Collections.emptyList())) uses.putIfAbsent(u.getId(), u);
            }
            item.setFileIds(ids);
            item.setUsedByCount(uses.size());
            List<Use> visible = new ArrayList<>();
            for (Use u : uses.values()) if (!hiddenUses.contains(u.getId())) visible.add(u);
            item.setUsedBy(visible);
            items.add(item);
            newest.put(item, latest);
        }
        items.sort((a, b) -> Long.compare(newest.get(b), newest.get(a)));
        GalleryPage out = new GalleryPage();
        int from = Math.min(items.size(), Math.max(0, page) * Math.max(1, size));
        out.setItems(new ArrayList<>(items.subList(from, Math.min(items.size(), from + Math.max(1, size)))));
        out.setTotal(items.size());
        out.setPage(page);
        out.setSize(size);
        return out;
    }

    /**
     * Deletes an uploaded kiosk image (one the gallery lists by its {@code kiosk-<hex>-} name) of the current customer
     * that nothing uses and no save holds. It runs under the lock saves take hold of their file with, so a save either
     * holds the file first (the delete is refused) or finds it gone (the save fails cleanly). The file on disk goes too
     * unless another record points at it.
     *
     * @param inUse whether a configuration (or any other part of the console) uses a file id
     * @throws IllegalArgumentException NOT_FOUND for another customer's or an unknown file, NOT_A_GALLERY_IMAGE for any
     *                                  other file, IN_USE when used or held
     */
    public void deleteUnused(int fileId, java.util.function.IntPredicate inUse) {
        synchronized (COPY_LOCK) {
            UploadedFile file;
            try {
                file = uploadedFiles.getById(fileId);
            } catch (com.hmdm.security.SecurityException e) {
                throw new IllegalArgumentException(NOT_FOUND);
            }
            Customer customer = SecurityContext.get().getCurrentUser()
                    .map(user -> customers.findById(user.getCustomerId()))
                    .orElseThrow(() -> new IllegalArgumentException(NOT_FOUND));
            if (file == null || file.isExternal() || customer == null || !Objects.equals(customer.getId(), file.getCustomerId())) {
                throw new IllegalArgumentException(NOT_FOUND);
            }
            if (!KIOSK_NAME.matcher(file.getFilePath() == null ? "" : file.getFilePath().replace('\\', '/')).find()) {
                throw new IllegalArgumentException(NOT_A_GALLERY_IMAGE);
            }
            if (editing.contains(fileId)) throw new IllegalArgumentException(BEING_EDITED);
            if (heldByOthers(fileId, null) || inUse.test(fileId)) throw new IllegalArgumentException(IN_USE);
            uploadedFiles.remove(fileId);
            if (file.getFilePath() != null && uploadedFiles.getByPath(customer.getId(), file.getFilePath()) == null) {
                FileUtil.deleteFile(customer, filesDirectory, file.getFilePath());
            }
        }
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
    /**
     * Saves hosting a cleaned copy, and undoing one: one at a time, so two saves of one image find one file and one
     * row. Per JVM: MDMesh runs as a single server node (across nodes the atomic move keeps the file whole, but two
     * rows for one path would be possible).
     */
    private static final Object COPY_LOCK = new Object();
    /** Copies another save picked up after the one that hosted them: never undone. */
    private final java.util.Set<Integer> reused = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
                // Never a copy a Files → edit is changing: its file I/O runs outside this lock, so the bytes compared
                // below could be replaced, or the file moved, right after this save pinned their SHA-256 on it (and
                // with its delete done first, this save would write into the gap its move then finds taken). Checked
                // before the file is read, under the lock beginEdit takes, as every other hold does.
                refuseWhileEdited(uploadedFiles.getByPath(customer.getId(), copyPath));
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
                refuseWhileEdited(existing);
                if (existing != null && !existing.isExternal()) {
                    // Another save uses it now: a failed save that hosted it must not take it back.
                    reused.add(existing.getId());
                    holdLocked(existing.getId());
                    return existing;
                }
                UploadedFile copy = new UploadedFile();
                copy.setCustomerId(customer.getId());
                copy.setFilePath(copyPath);
                copy.setDescription(original.getDescription());
                copy.setExternal(false);
                copy.setUploadTime(System.currentTimeMillis());
                UploadedFile inserted = uploadedFiles.insert(copy);
                // Only a copy this save wrote is taken back if the save fails; a file already there may be in use.
                if (wrote) created[0] = inserted;
                holdLocked(inserted.getId());
                return inserted;
            } catch (UnsafePathException | IOException e) {
                throw new IllegalArgumentException(NOT_STORED, e);
            }
        }
    }

    /** BEING_EDITED while a Files → edit of {@code file} runs. Call under COPY_LOCK. */
    private void refuseWhileEdited(UploadedFile file) {
        if (file != null && editing.contains(file.getId())) throw new IllegalArgumentException(BEING_EDITED);
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
