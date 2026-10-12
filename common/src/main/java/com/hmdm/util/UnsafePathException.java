/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.util;

/**
 * <p>A file path that resolves outside the directory it has to stay in, or a file that is not what the caller
 * claimed it to be (e.g. not a file the upload endpoint created).</p>
 */
public class UnsafePathException extends RuntimeException {

    public UnsafePathException(String message) {
        super(message);
    }
}
