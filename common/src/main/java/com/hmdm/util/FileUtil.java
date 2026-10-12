/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.util;

import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Customer;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * <p>An utility class for managing the files on local file system.</p>
 *
 * @author isv
 */
public final class FileUtil {
    static final String TEMP_FILE_DELIMITER = "1111111";
    private static final String TEMP_FILE_SUFFIX = ".temp";

    /**
     * <p>Constructs new <code>FileUtil</code> instance. This implementation does nothing.</p>
     */
    private FileUtil() {
    }

    public static String adjustFileName(String fileName) {
        return fileName
                .replace(' ', '_')
                .replace('+', '_')          // Not valid in URL
                .replace('%', '_')          // Not valid in URL
                .replace("(", "")           // These characters are used by Windows when a file is downloaded twice
                .replace(")", "");
    }

    public static void writeToFile(InputStream uploadedInputStream, String uploadedFileLocation) {
        try (FileOutputStream out = new FileOutputStream(new File(uploadedFileLocation))) {
            byte[] bytes = new byte[1024];

            int read;
            while((read = uploadedInputStream.read(bytes)) != -1) {
                out.write(bytes, 0, read);
            }

            out.flush();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String getNameFromTmpPath(String tmpFilePath) {
        File localFile = new File(tmpFilePath);
        if (!localFile.getName().contains(TEMP_FILE_DELIMITER)) {
            throw new RuntimeException("Temp file should contain the delimiter: " + tmpFilePath);
        }
        return localFile.getName().split(TEMP_FILE_DELIMITER)[0];
    }

    /** The base directory holding every customer's pending uploads. */
    private static Path uploadBase() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "mdmesh-uploads");
    }

    /** The directory holding {@code customer}'s pending uploads, below {@code base}. */
    static Path uploadDir(Customer customer, Path base) {
        return base.resolve("c" + customer.getId());
    }

    /**
     * <p>Creates an empty upload file owned by {@code customer}: only that customer can later commit it
     * ({@link #moveFile}) into its served files.</p>
     */
    public static File createUploadFile(Customer customer, String fileName) throws IOException {
        File dir = uploadDir(customer, uploadBase()).toFile();
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create the upload directory");
        }
        return File.createTempFile(fileName + TEMP_FILE_DELIMITER, TEMP_FILE_SUFFIX, dir);
    }

    /**
     * <p>Whether {@code path} names a file {@link #createUploadFile} created for {@code customer}: a regular file
     * (not a symlink) directly inside that customer's upload directory, named like an upload. Only such files may be
     * moved into the customer's served files; every other local path is refused.</p>
     */
    public static boolean isUploadedTempFile(String path, Customer customer) {
        return isUploadedTempFile(path, customer, uploadBase());
    }

    static boolean isUploadedTempFile(String path, Customer customer, Path uploadBase) {
        return customer != null && uploadedTempFile(path, uploadDir(customer, uploadBase)) != null;
    }

    /** @return the real path of the upload, or null when {@code path} is not an upload in {@code uploadDir}. */
    private static Path uploadedTempFile(String path, Path uploadDir) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        try {
            Path p = Paths.get(path);
            if (!p.isAbsolute() || Files.isSymbolicLink(p)) {
                return null;
            }
            Path real = p.toRealPath();
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            if (!uploadDir.toRealPath().equals(real.getParent())) {
                return null;
            }
            String name = real.getFileName().toString();
            if (!name.contains(TEMP_FILE_DELIMITER) || !name.endsWith(TEMP_FILE_SUFFIX)) {
                return null;
            }
            return real;
        } catch (IOException | InvalidPathException | SecurityException e) {
            return null;
        }
    }

    /** The directory holding this customer's served files (same layout as {@link #resolveFile}). */
    private static Path customerRoot(Customer customer, String filesDirectory) {
        try {
            return Paths.get(String.format("%s/%s", filesDirectory, customer.getFilesDir()).replace("/", File.separator))
                    .toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new UnsafePathException("Invalid files directory");
        }
    }

    /** Lexical containment: {@code candidate} lies strictly below {@code root}. */
    private static void requireInside(Path root, Path candidate, String what) {
        Path c = candidate.toAbsolutePath().normalize();
        if (!c.startsWith(root) || c.equals(root)) {
            throw new UnsafePathException("Path escapes the files directory: " + what);
        }
    }

    /** Containment after resolving symlinks: the existing directory {@code dir} is {@code root} or below it. */
    private static void requireRealInside(Path root, Path dir, String what) {
        try {
            if (!dir.toRealPath().startsWith(root.toRealPath())) {
                throw new UnsafePathException("Path escapes the files directory: " + what);
            }
        } catch (IOException e) {
            throw new UnsafePathException("Cannot resolve path: " + what);
        }
    }

    /** A target file name is a single path component (leading slashes are tolerated and stripped). */
    private static String requireFileName(String fileName) {
        String name = fileName == null ? "" : fileName;
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\u0000') >= 0) {
            throw new UnsafePathException("Invalid file name: " + fileName);
        }
        return name;
    }

    /**
     * <p>Moves the specified uploaded file to desired location related to specified customer account.
     * The target file name is determined from the tmp file name</p>
     *
     * @param customer a customer account which the file belongs to.
     * @param filesDirectory a directory which holds all the files maintained by the application.
     * @param localPath an optional local path to move file to.
     * @param tmpFilePath a path to a temporary file created by {@link #createUploadFile}.
     * @return a file referencing the moved file if operation was successful; <code>null</code> otherwise.
     * @throws FileExistsException if file already exists
     * @throws UnsafePathException if tmpFilePath is not an upload or the target leaves the customer's directory
     */
    public static File moveFile(Customer customer, String filesDirectory, String localPath, String tmpFilePath) {
        return moveFile(customer, filesDirectory, localPath, tmpFilePath, null);
    }

    /**
     * The absolute {@link File} where {@link #moveFile} would place {@code fileName} for this customer.
     * Single source of truth for the served-files layout so callers (e.g. idempotent hosting) can test
     * existence without duplicating — and drifting from — this path logic.
     *
     * @throws UnsafePathException if the result would not lie inside the customer's files directory
     */
    public static File resolveFile(Customer customer, String filesDirectory, String localPath, String fileName) {
        while (fileName.startsWith("/")) {
            fileName = fileName.substring(1);
        }
        String filePath;
        if (localPath == null || localPath.isEmpty()) {
            filePath = String.format("%s/%s/%s", filesDirectory, customer.getFilesDir(), fileName);
        } else {
            filePath = String.format("%s/%s/%s/%s", filesDirectory, customer.getFilesDir(), localPath, fileName);
        }
        File file = new File(filePath.replace("/", File.separator));
        try {
            requireInside(customerRoot(customer, filesDirectory), file.toPath(), fileName);
        } catch (InvalidPathException e) {
            throw new UnsafePathException("Invalid path: " + fileName);
        }
        return file;
    }

    /**
     * <p>Moves the specified uploaded file to desired location related to specified customer account.
     * The target file name is explicitly specified</p>
     *
     * @param customer a customer account which the file belongs to.
     * @param filesDirectory a directory which holds all the files maintained by the application.
     * @param localPath an optional local path (below the customer's directory) to move file to.
     * @param tmpFilePath a path to a temporary file created by {@link #createUploadFile}.
     * @param newName the target file name (a single path component), or null to derive it from tmpFilePath.
     * @return a file referencing the moved file if operation was successful; <code>null</code> otherwise.
     * @throws FileExistsException if file already exists
     * @throws UnsafePathException if tmpFilePath is not an upload or the target leaves the customer's directory
     */
    public static File moveFile(Customer customer, String filesDirectory, String localPath, String tmpFilePath, String newName) {
        return moveFile(customer, filesDirectory, localPath, tmpFilePath, newName, uploadBase());
    }

    static File moveFile(Customer customer, String filesDirectory, String localPath, String tmpFilePath,
                         String newName, Path uploadBase) {
        Path source = customer == null ? null : uploadedTempFile(tmpFilePath, uploadDir(customer, uploadBase));
        if (source == null) {
            throw new UnsafePathException("Not an upload of this customer");
        }
        String fileName = newName != null ? newName : getNameFromTmpPath(source.toString());
        File file = checkTarget(customer, filesDirectory, localPath, fileName);
        return place(customer, filesDirectory, source.toFile(), file);
    }

    /**
     * <p>The target {@link #moveFile} would write for {@code fileName}, after the same checks it applies (a single
     * name component, inside the customer's directory, no symlinked directory leading out), without creating or
     * changing anything. Lets callers validate before a destructive step.</p>
     *
     * @throws UnsafePathException if the target is not acceptable
     */
    public static File checkTarget(Customer customer, String filesDirectory, String localPath, String fileName) {
        File file = resolveFile(customer, filesDirectory, localPath, requireFileName(fileName));
        Path root = customerRoot(customer, filesDirectory);
        Path existing = file.toPath().toAbsolutePath().normalize().getParent();
        while (existing != null && !Files.exists(existing) && !existing.equals(root)) {
            existing = existing.getParent();
        }
        if (existing != null && Files.exists(existing)) {
            requireRealInside(root, existing, fileName);
        }
        return file;
    }

    /**
     * <p>Renames a file inside the customer's directory; both paths are relative to it.</p>
     *
     * @return the renamed file, or null if the source does not exist or could not be moved.
     * @throws FileExistsException if the target already exists
     * @throws UnsafePathException if either path leaves the customer's directory
     */
    public static File renameFile(Customer customer, String filesDirectory, String fromPath, String toPath) {
        if (fromPath == null || toPath == null) {
            throw new UnsafePathException("Missing path");
        }
        File from = resolveFile(customer, filesDirectory, null, fromPath);
        File to = resolveFile(customer, filesDirectory, null, toPath);
        Path src = from.toPath();
        if (!Files.exists(src, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(src, LinkOption.NOFOLLOW_LINKS)) {
            throw new UnsafePathException("Not a regular file: " + fromPath);
        }
        requireRealInside(customerRoot(customer, filesDirectory), src.toAbsolutePath().getParent(), fromPath);
        return place(customer, filesDirectory, from, to);
    }

    /**
     * Moves {@code source} to {@code target} (already lexically inside the customer's directory), refusing a
     * target directory that resolves elsewhere through a symlink, and never overwriting (or writing through)
     * an existing entry.
     */
    private static File place(Customer customer, String filesDirectory, File source, File target) {
        Path root = customerRoot(customer, filesDirectory);
        root.toFile().mkdirs();
        Path parent = target.toPath().toAbsolutePath().normalize().getParent();
        // Check the deepest existing ancestor before creating anything, then the created directory itself.
        Path existing = parent;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new UnsafePathException("Cannot resolve path: " + target.getName());
        }
        requireRealInside(root, existing, target.getName());
        parent.toFile().mkdirs();
        requireRealInside(root, parent, target.getName());

        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new FileExistsException(customer, target.getName());
        }

        final boolean success = source.renameTo(target);
        if (success) {
            return target;
        } else {
            // Try to copy and delete because rename can fail due to different file systems
            // For example, on Tomcat 9 renaming from /tmp to /var/lib/tomcat9/work will fail due to sandbox restrictions
            try {
                FileInputStream inputStream = new FileInputStream(source);
                writeToFile(inputStream, target.getAbsolutePath());
                inputStream.close();
                source.delete();
                return target;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    public static String translateURLToLocalFilePath(Customer customer, String url, String baseUrl) {
        final String prefixWithoutCustomer = baseUrl + "/files/";
        String prefix = prefixWithoutCustomer;
        if (customer.getFilesDir() != null && !customer.getFilesDir().isEmpty()) {
            prefix += customer.getFilesDir() + "/";
        }
        if (url.startsWith(prefix)) {
            final String path = url.substring(prefix.length());
            return path.replace("/", File.separator);
        }

        return null;
    }

    /**
     * <p>Deletes the specified file on local file system.</p>
     *
     * @param baseDirectory a path to a base directory where all application files are stored..
     * @param path a path to a file to delete.
     * @return <code>true</code> if file was deleted successfully; <code>false</code> otherwise.
     */
    public static boolean deleteFile(Customer customer, String baseDirectory, String path) {
        if (path == null) {
            return false;
        }
        final File fileToDelete;
        try {
            fileToDelete = resolveFile(customer, baseDirectory, null, path);
            Path parent = fileToDelete.toPath().toAbsolutePath().getParent();
            if (!Files.isDirectory(parent)) {
                return false;
            }
            requireRealInside(customerRoot(customer, baseDirectory), parent, path);
        } catch (UnsafePathException e) {
            return false;
        }
        return fileToDelete.delete();
    }

    public static String createFileUrl(String baseUrl, String customerDir, String fileName) {
        // TODO: Use files.directory from XML config!
        String url = baseUrl + "/files/";
        if (customerDir != null && !customerDir.equals("")) {
            url += customerDir + "/";
        }
        url += fileName;
        return url;
    }

    public static boolean isSafePath(String path) {
        return path == null || !path.contains("..");
    }
}
