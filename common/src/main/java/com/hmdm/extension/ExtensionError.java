package com.hmdm.extension;

/**
 * Why a save's {@code extensions} were refused: one entry of the error response's data. {@code key} is a message
 * key: {@code error.extensions.<code>} for core's checks, {@code error.extension.<namespace>.<code>} for a plugin's
 * {@link ConfigurationExtensionValidator}.
 */
public final class ExtensionError {
    private final String namespace;
    private final String key;
    private final String message;

    public ExtensionError(String namespace, String key, String message) {
        this.namespace = namespace;
        this.key = key;
        this.message = message;
    }

    /**
     * The namespace it is about; null when it is about the whole of {@code extensions}. A key that isn't a namespace
     * is cut to 64 characters here, as in the message.
     */
    public String getNamespace() {
        return namespace;
    }

    public String getKey() {
        return key;
    }

    /** A sentence for people. */
    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return key + ": " + message;
    }
}
