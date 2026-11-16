package com.hmdm.rest.resource.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * A kiosk background image file as the console's viewer shows it: what devices get once a configuration is saved
 * with it. Width and height are as displayed (after the EXIF orientation); size and SHA-256 are the cleaned file's.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KioskBackgroundImageInfo {
    private Integer fileId;
    /** Where devices download it, from the server's current base URL. */
    private String url;
    /** The file name, without the {@code kiosk-<hex>-} prefix the console hosts it under. */
    private String name;
    /** PNG, JPEG or WebP. */
    private String format;
    private int width;
    private int height;
    /** The EXIF orientation kept in the file (1 = upright, none kept). */
    private int orientation;
    /** Bytes. */
    private long size;
    private String sha256;
    /** Epoch milliseconds; null when unknown. */
    private Long uploadTime;
    /** The uploaded file still has metadata (location, camera...): saving hosts a copy without it. */
    private Boolean hasMetadata;
    /**
     * True for another account's file that a configuration of this account uses (one copied from the main account's
     * template): shown and sent as it is, never chosen, cleaned or deleted from here. Absent for the account's own.
     */
    private Boolean fromAnotherAccount;
}
