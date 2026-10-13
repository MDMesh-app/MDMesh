/*
 * MDMesh: a file path that leaves the directory it must stay in.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
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
