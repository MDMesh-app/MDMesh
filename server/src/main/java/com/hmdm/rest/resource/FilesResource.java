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

package com.hmdm.rest.resource;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.inject.Named;

import com.hmdm.notification.PushService;
import com.hmdm.persistence.*;
import com.hmdm.persistence.domain.*;
import com.hmdm.rest.json.*;
import com.hmdm.rest.json.view.FileView;
import com.hmdm.util.APKFileAnalyzer;
import com.hmdm.util.StringUtil;
import org.apache.commons.io.FileUtils;
import org.glassfish.jersey.media.multipart.ContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.StreamingOutput;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;
import io.swagger.annotations.ResponseHeader;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import com.hmdm.util.FileExistsException;
import com.hmdm.util.FileUtil;
import com.hmdm.util.HostedFiles;
import com.hmdm.util.UnsafePathException;

@Api(tags = {"Files"}, authorizations = {@Authorization("Bearer Token")})
@Singleton
@Path("/private/web-ui-files")
public class FilesResource {
    private static final Logger logger = LoggerFactory.getLogger(FilesResource.class);

    private String filesDirectory;
    private String baseUrl;
    private File baseDirectory;
    private CustomerDAO customerDAO;
    private UnsecureDAO unsecureDAO;
    private ApplicationDAO applicationDAO;
    private ConfigurationDAO configurationDAO;
    private UploadedFileDAO uploadedFileDAO;
    private APKFileAnalyzer apkFileAnalyzer;
    private ConfigurationFileDAO configurationFileDAO;
    private IconDAO iconDAO;
    private PushService pushService;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public FilesResource() {
    }

    @Inject
    public FilesResource(@Named("files.directory") String filesDirectory,
                         @Named("base.url") String baseUrl,
                         CustomerDAO customerDAO,
                         UnsecureDAO unsecureDAO,
                         ApplicationDAO applicationDAO,
                         ConfigurationDAO configurationDAO,
                         UploadedFileDAO uploadedFileDAO,
                         APKFileAnalyzer apkFileAnalyzer,
                         ConfigurationFileDAO configurationFileDAO,
                         IconDAO iconDAO,
                         PushService pushService) {
        this.filesDirectory = filesDirectory;
        this.baseDirectory = new File(filesDirectory);
        this.customerDAO = customerDAO;
        this.unsecureDAO = unsecureDAO;
        this.applicationDAO = applicationDAO;
        this.configurationDAO = configurationDAO;
        this.uploadedFileDAO = uploadedFileDAO;
        this.configurationFileDAO = configurationFileDAO;
        this.iconDAO = iconDAO;
        this.pushService = pushService;
        if (!this.baseDirectory.exists()) {
            this.baseDirectory.mkdirs();
        }
        this.apkFileAnalyzer = apkFileAnalyzer;

        this.baseUrl = baseUrl;
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get all files",
            notes = "Gets the list of all available files",
            response = FileView.class,
            responseContainer = "List"
    )
    @GET
    @Path("/search")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllFiles() {
        if (!SecurityContext.get().hasPermission("files")) {
            logger.error("Unauthorized attempt to access file list by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        return Response.OK(this.generateFilesList((String)null));
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Remove a file",
            notes = "Removes the file from the MDM server"
    )
    @POST
    @Path("/remove")
    public Response removeFile(FileView file) {
        if (!SecurityContext.get().hasPermission("edit_files")) {
            logger.error("Unauthorized attempt to remove a file by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        if (!FileUtil.isSafePath(file.getFilePath())) {
            logger.error("Attempt to remove a file with unsafe path! path: " + file.getFilePath());
            return Response.PERMISSION_DENIED();
        }

        return SecurityContext.get().getCurrentUser().map(u -> {
            Customer customer = customerDAO.findById(u.getCustomerId());

            // Check if file is not used
            if (this.configurationFileDAO.isFileUsed(file.getId())) {
                return Response.FILE_USED();
            } else if (this.iconDAO.isFileUsed(file.getId())) {
                return Response.FILE_USED();
            }

            if (!file.isExternal()) {
                // Delete the file the record points at, never a path taken from the request.
                UploadedFile dbFile = uploadedFileDAO.getById(file.getId());
                if (dbFile == null) {
                    return Response.PERMISSION_DENIED();
                }
                String recordedPath = dbFile.getFilePath();
                logger.info("File {} is removed by user {}", recordedPath, SecurityContext.get().getCurrentUserName());
                uploadedFileDAO.remove(file.getId());
                if (recordedPath != null && !recordedPath.isEmpty()
                        && uploadedFileDAO.getByPath(customer.getId(), recordedPath) == null) {
                    FileUtil.deleteFile(customer, filesDirectory, recordedPath);
                }
                return Response.OK();
            } else {
                logger.info("External file {} ({}) is removed by user {}",
                        file.getDescription(), file.getUrl(), SecurityContext.get().getCurrentUserName());
                uploadedFileDAO.remove(file.getId());
                return Response.OK();
            }
        }).orElse(Response.PERMISSION_DENIED());
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Complete file upload",
            notes = "Commits the file upload to MDM server. Returns the uploaded file data",
            response = FileView.class
    )
    @POST
    @Path("/update")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateFile(UploadedFile uploadedFile) {
        if (!SecurityContext.get().hasPermission("edit_files")) {
            logger.error("Unauthorized attempt to update a file by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        if (uploadedFile.getId() == null) {
            if (!uploadedFile.isExternal()) {
                return createFileInternal(uploadedFile);
            } else {
                return createExternalFileInternal(uploadedFile);
            }
        } else {
            return updateFileInternal(uploadedFile);
        }
    }

    private Response createFileInternal(UploadedFile uploadedFile) {
        // A hosted file has no external URL, whatever the request carries.
        uploadedFile.setExternalUrl(null);
        if (uploadedFile.getFilePath() == null) {
            uploadedFile.setFilePath("");
        }
        Customer uploader = SecurityContext.get().getCurrentUser()
                .map(u -> customerDAO.findById(u.getCustomerId())).orElse(null);
        if (!FileUtil.isSafePath(uploadedFile.getFilePath()) ||
            !FileUtil.isUploadedTempFile(uploadedFile.getTmpPath(), uploader)) {
            logger.warn("Refused to create file {}: not a safe path or not an upload of this customer",
                    uploadedFile.getFilePath());
            return Response.PERMISSION_DENIED();
        }

        String fname = uploadedFile.getFileName();
        // Empty fname means using the default name from tmp path (processed by moveFile)
        if (fname.equals("")) {
            fname = FileUtil.getNameFromTmpPath(uploadedFile.getTmpPath());
            while (fname.startsWith("/")) {
                fname = fname.substring(1);
            }
            uploadedFile.setFilePath(fname);
        }
        final String fileName = fname;

        return SecurityContext.get().getCurrentUser().map(u -> {
            Customer customer = customerDAO.findById(u.getCustomerId());
            try {
                File movedFile = FileUtil.moveFile(customer, filesDirectory, uploadedFile.getSubdir(), uploadedFile.getTmpPath(), fileName);
                if (movedFile != null) {
                    List<FileView> result = new LinkedList<>();
                    handleFile(movedFile, result, null, customer);

                    BasicFileAttributes attrs = Files.readAttributes(movedFile.toPath(), BasicFileAttributes.class);
                    uploadedFile.setUploadTime(attrs.creationTime().toMillis());
                    uploadedFile.setCustomerId(customer.getId());
                    uploadedFileDAO.insert(uploadedFile);
                    uploadedFile.setUrl(uploadedFile.getUrl(baseUrl, customer));

                    return Response.OK(uploadedFile);
                } else {
                    return Response.ERROR("error.file.save");
                }
            } catch (FileExistsException e) {
                logger.warn("File {} already exists", uploadedFile.getFilePath());
                return Response.FILE_EXISTS();
            } catch (UnsafePathException e) {
                logger.warn("Refused to create file {}: {}", uploadedFile.getFilePath(), e.getMessage());
                return Response.ERROR(UNSAFE_FILE_PATH);
            } catch (IOException e) {
                logger.warn("While creating file {}: {}", uploadedFile.getFilePath(), e.getMessage());
                e.printStackTrace();
                return Response.INTERNAL_ERROR();
            }
        }).orElse(Response.PERMISSION_DENIED());
    }

    /** Shown when a file path would leave the customer's files directory. */
    static final String UNSAFE_FILE_PATH = "The file path is not allowed";
    /** Shown when an update would turn an uploaded file into an external one or back. */
    static final String FILE_KIND_CHANGE = "An uploaded file cannot become an external file, or the other way round";

    /** Shown when an external file's URL is not http(s). */
    static final String EXTERNAL_URL_NOT_HTTP = "An external file's URL must start with http:// or https://";

    private Response createExternalFileInternal(UploadedFile uploadedFile) {
        if (uploadedFile.getExternalUrl() == null || uploadedFile.getExternalUrl().trim().equals("")) {
            logger.warn("While creating external file: empty URL");
            return Response.ERROR();
        }
        if (!HostedFiles.isHttpUrl(uploadedFile.getExternalUrl())) {
            logger.warn("Refused to create an external file with a non-http(s) URL");
            return Response.ERROR(EXTERNAL_URL_NOT_HTTP);
        }
        return SecurityContext.get().getCurrentUser().map(u -> {
            if (uploadedFile.getFilePath() == null) {
                // It was previously set as NOT NULL
                uploadedFile.setFilePath("");
            }
            Customer customer = customerDAO.findById(u.getCustomerId());
            uploadedFile.setCustomerId(customer.getId());
            uploadedFileDAO.insert(uploadedFile);
            uploadedFile.setUrl(uploadedFile.getUrl(baseUrl, customer));

            return Response.OK(uploadedFile);
        }).orElse(Response.PERMISSION_DENIED());
    }

    private Response updateFileInternal(UploadedFile uploadedFile) {
        return SecurityContext.get().getCurrentUser().map(u -> {
            Customer customer = customerDAO.findById(u.getCustomerId());
            // The stored row decides what kind of file this is; the request may not change it.
            final UploadedFile dbFile;
            try {
                dbFile = uploadedFileDAO.getById(uploadedFile.getId());
            } catch (SecurityException e) {
                logger.warn("Refused to update file {}: not a file of this customer", uploadedFile.getId());
                return Response.PERMISSION_DENIED();
            }
            if (dbFile == null) {
                logger.warn("Refused to update file {}: no such file", uploadedFile.getId());
                return Response.PERMISSION_DENIED();
            }
            if (uploadedFile.isExternal() != dbFile.isExternal()) {
                logger.warn("Refused to update file {}: the external flag cannot change", uploadedFile.getId());
                return Response.ERROR(FILE_KIND_CHANGE);
            }
            if (dbFile.isExternal()) {
                // A changed URL must be http(s); an older file keeps whatever URL it already has.
                String url = uploadedFile.getExternalUrl();
                if (!Objects.equals(url, dbFile.getExternalUrl()) && !HostedFiles.isHttpUrl(url)) {
                    logger.warn("Refused to set a non-http(s) URL on external file {}", uploadedFile.getId());
                    return Response.ERROR(EXTERNAL_URL_NOT_HTTP);
                }
            } else {
                // A hosted file keeps its stored external URL (none), whatever the request carries.
                uploadedFile.setExternalUrl(dbFile.getExternalUrl());
                if (!StringUtil.isEmpty(uploadedFile.getTmpPath())) {
                    if (!FileUtil.isUploadedTempFile(uploadedFile.getTmpPath(), customer)) {
                        logger.warn("Refused to replace file {}: not an upload of this customer", dbFile.getFilePath());
                        return Response.PERMISSION_DENIED();
                    }
                    File movedFile;
                    try {
                        // Validate the target exactly as moveFile will, before the old content is removed
                        FileUtil.checkTarget(customer, filesDirectory, dbFile.getSubdir(), dbFile.getFileName());
                        // Renew the file content - remove the old file and overwrite with the new content
                        FileUtil.deleteFile(customer, filesDirectory, dbFile.getFilePath());
                        movedFile = FileUtil.moveFile(customer, filesDirectory, dbFile.getSubdir(),
                                uploadedFile.getTmpPath(), dbFile.getFileName());
                    } catch (UnsafePathException e) {
                        logger.warn("Refused to replace file {}: {}", dbFile.getFilePath(), e.getMessage());
                        return Response.ERROR(UNSAFE_FILE_PATH);
                    }
                    if (movedFile == null) {
                        return Response.ERROR("error.file.save");
                    }
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(movedFile.toPath(), BasicFileAttributes.class);
                        uploadedFile.setUploadTime(attrs.lastModifiedTime().toMillis());
                    } catch (IOException e) {
                        e.printStackTrace();
                        return Response.INTERNAL_ERROR();
                    }
                }
                if (!dbFile.getFilePath().equals(uploadedFile.getFilePath())) {
                    if (!FileUtil.isSafePath(uploadedFile.getFilePath())) {
                        logger.error("Attempt to move a file to unsafe path: " + uploadedFile.getFilePath());
                        return Response.PERMISSION_DENIED();
                    }
                    File movedFile;
                    try {
                        movedFile = FileUtil.renameFile(customer, filesDirectory,
                                dbFile.getFilePath(), uploadedFile.getFilePath());
                    } catch (UnsafePathException e) {
                        logger.warn("Refused to move a file to path {}: {}", uploadedFile.getFilePath(), e.getMessage());
                        return Response.ERROR(UNSAFE_FILE_PATH);
                    }
                    if (movedFile == null) {
                        logger.error("Failed to move a file to path: " + uploadedFile.getFilePath());
                        return Response.ERROR("error.file.save");
                    }
                }
            }
            uploadedFileDAO.update(uploadedFile);
            return Response.OK();
        }).orElse(Response.PERMISSION_DENIED());
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Search files",
            notes = "Search files meeting the specified filter value",
            response = FileView.class,
            responseContainer = "List"
    )
    @GET
    @Path("/search/{value}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getFilesByName(@PathParam("value") @ApiParam("A filter value") String value) {
        if (!SecurityContext.get().hasPermission("files")) {
            logger.error("Unauthorized attempt to access file list by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        return Response.OK(this.generateFilesList(value));
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get applications",
            notes = "Gets the list of applications using the file",
            response = Application.class,
            responseContainer = "List"
    )
    @GET
    @Path("/apps/{url}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getApplicationsForFile(@PathParam("url") @ApiParam("An URL referencing the file") String url) {
        if (!SecurityContext.get().hasPermission("files")) {
            logger.error("Unauthorized attempt to access file list by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            String decodedUrl = URLDecoder.decode(url, "UTF-8");
            return Response.OK(this.applicationDAO.getAllApplicationsByUrl(decodedUrl));
        } catch (Exception e) {
            logger.error("Unexpected error when getting the list of applications by URL", e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @GET
    @Path("/limit")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStorageLimit() {
        LimitResponse lr = new LimitResponse();
        if (!unsecureDAO.isSingleCustomer()) {
            // Check the disk size in multi-tenant mode
            Customer currentCustomer = customerDAO.findById(SecurityContext.get().getCurrentCustomerId().get());
            if (!currentCustomer.isMaster() && currentCustomer.getSizeLimit() > 0) {
                File userDir = new File(this.filesDirectory, currentCustomer.getFilesDir());
                long userDirSize = 0;
                if (userDir.exists()) {
                    userDirSize = FileUtils.sizeOfDirectory(userDir);
                }
                lr.setSizeUsed((int) (userDirSize / 1048576l));
                lr.setSizeLimit(currentCustomer.getSizeLimit());
            }
        }
        return Response.OK(lr);
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get file configurations",
            notes = "Gets the list of configurations using requested file",
            response = ApplicationConfigurationLink.class,
            responseContainer = "List"
    )
    @GET
    @Path("/configurations/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getFileConfigurations(@PathParam("id") @ApiParam("File ID") Integer id) {
        if (!SecurityContext.get().hasPermission("files")) {
            logger.error("Unauthorized attempt to get file configurations by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        return Response.OK(this.uploadedFileDAO.getFileConfigurations(id));
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Update file configurations",
            notes = "Updates the list of configurations using requested file"
    )
    @POST
    @Path("/configurations")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateFileConfigurations(LinkConfigurationsToFileRequest request) {
        if (!SecurityContext.get().hasPermission("edit_files")) {
            logger.error("Unauthorized attempt to update file configurations by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            User user = SecurityContext.get().getCurrentUser().get();
            if (!user.isAllConfigAvailable()) {
                // Remove all configurations unavailable to user
                request.getConfigurations().removeIf(c ->
                        user.getConfigurations()
                                .stream()
                                .noneMatch(uc -> uc.getId() == c.getConfigurationId()));
            }
            // Avoid access to objects of another customer
            request.getConfigurations().removeIf(c -> {
                // findById will raise a SecurityException if attempting to access an object of another customer
                // So actually this code is a bit redundant, but it guards access to own objects anyway
                UploadedFile file = uploadedFileDAO.getById(c.getFileId());
                Configuration configuration = configurationDAO.getConfigurationById(c.getConfigurationId());
                return file.getCustomerId() != user.getCustomerId() ||
                        configuration.getCustomerId() != user.getCustomerId();
            });
            logger.info("File configurations updated by user " + SecurityContext.get().getCurrentUserName());
            this.uploadedFileDAO.updateFileConfigurations(request.getConfigurations());

            for (FileConfigurationLink configurationLink : request.getConfigurations()) {
                if (configurationLink.isNotify()) {
                    this.pushService.notifyDevicesOnUpdate(configurationLink.getConfigurationId());
                }
            }

            return Response.OK();
        } catch (Exception e) {
            logger.error("Unexpected error when updating file configurations", e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Upload raw file",
            notes = "Uploads the raw file to server (without attempt to parse APK). Returns a path to uploaded file",
            response = FileUploadResult.class
    )
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Path("/raw")
    public Response uploadFilesRaw(@FormDataParam("file") InputStream uploadedInputStream,
                                @ApiParam("A file to upload") @FormDataParam("file") FormDataContentDisposition fileDetail) throws Exception {
        return uploadFilesInternal(uploadedInputStream, fileDetail, false);
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Upload file or application",
            notes = "Uploads the file or application to server. Returns a path to uploaded file",
            response = FileUploadResult.class
    )
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadFiles(@FormDataParam("file") InputStream uploadedInputStream,
                                @ApiParam("A file to upload") @FormDataParam("file") FormDataContentDisposition fileDetail) throws Exception {
        return uploadFilesInternal(uploadedInputStream, fileDetail, true);
    }

    // =================================================================================================================
    private Response uploadFilesInternal(InputStream uploadedInputStream,
                                         FormDataContentDisposition fileDetail,
                                         boolean parseFile) throws Exception {
        if (!SecurityContext.get().hasPermission("edit_files")) {
            logger.error("Unauthorized attempt to upload a file by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            // For some reason, the browser sends the file name in ISO_8859_1, so we use a workaround to convert
            // it to UTF_8 and enable non-ASCII characters
            // https://stackoverflow.com/questions/50582435/jersey-filename-encoded
            String fileName = new String(fileDetail.getFileName().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            String adjustedFileName = FileUtil.adjustFileName(fileName);
            Customer uploader = customerDAO.findById(SecurityContext.get().getCurrentCustomerId().get());
            File uploadFile = FileUtil.createUploadFile(uploader, adjustedFileName);
            FileUtil.writeToFile(uploadedInputStream, uploadFile.getAbsolutePath());

            FileUploadResult result = new FileUploadResult();
            result.setName(fileName);

            if (!unsecureDAO.isSingleCustomer()) {
                // Check the disk size in multi-tenant mode
                Customer currentCustomer = customerDAO.findById(SecurityContext.get().getCurrentCustomerId().get());
                if (!currentCustomer.isMaster() && currentCustomer.getSizeLimit() > 0) {
                    File userDir = new File(this.filesDirectory, currentCustomer.getFilesDir());
                    long userDirSize = 0;
                    long uploadFileSize = uploadFile.length();
                    if (userDir.exists()) {
                        userDirSize = FileUtils.sizeOfDirectory(userDir);
                    }
                    long totalSizeMb = (userDirSize + uploadFileSize) / 1048576l;
                    if (totalSizeMb > currentCustomer.getSizeLimit()) {
                        uploadFile.delete();
                        logger.warn("Storage limit exceeded for customer {}: {}/{}", currentCustomer.getName(),
                                totalSizeMb, currentCustomer.getSizeLimit());
                        return Response.ERROR("error.size.limit.exceeded",
                                "" + totalSizeMb + " / " + currentCustomer.getSizeLimit());
                    }
                }
            }

            result.setServerPath(uploadFile.getAbsolutePath());

            if (parseFile && fileName.endsWith("apk")) {
                final APKFileDetails apkFileDetails;
                apkFileDetails = this.apkFileAnalyzer.analyzeFile(uploadFile.getAbsolutePath());
                result.setFileDetails(apkFileDetails);

                ApplicationVersion version;
                if (apkFileDetails.getVersionCode() != 0) {
                    version = this.applicationDAO.findApplicationVersionByCode(apkFileDetails.getPkg(), apkFileDetails.getVersionCode());
                    if (version != null && !version.getVersion().equals(apkFileDetails.getVersion())) {
                        logger.warn("Version of {} with code {} already exists, name {}", apkFileDetails.getPkg(),
                                apkFileDetails.getVersionCode(), version.getVersion());
                        // Version with the same version code but different version name exists
                        // This is a violation of Android versioning guidelines - disable upload
                        return Response.DUPLICATE_ENTITY("form.application.version.code.exists");
                    }
                }

                version = this.applicationDAO.findApplicationVersion(apkFileDetails.getPkg(), apkFileDetails.getVersion());
                if (StringUtil.isEmpty(apkFileDetails.getArch())){
                    if (version != null) {
                        result.setExists(true);
                    }
                } else if (apkFileDetails.getArch().equals(Application.ARCH_ARMEABI)) {
                    // If version for arm64 is already uploaded, set the complete flag
                    result.setComplete(version != null && !StringUtil.isEmpty(version.getUrlArm64()));
                    // Check if version is already uploaded
                    result.setExists(version != null && (!version.isSplit() || !StringUtil.isEmpty(version.getUrlArmeabi())));
                } else if (apkFileDetails.getArch().equals(Application.ARCH_ARM64)) {
                    result.setComplete(version != null && !StringUtil.isEmpty(version.getUrlArmeabi()));
                    result.setExists(version != null && (!version.isSplit() || !StringUtil.isEmpty(version.getUrlArm64())));
                }

                final List<Application> dbAppsByPkg = this.applicationDAO.findByPackageId(apkFileDetails.getPkg());
                if (!dbAppsByPkg.isEmpty()) {
                    final Application dbApp = dbAppsByPkg.get(0);
                    final Application dbAppCopy = new Application();
                    dbAppCopy.setId(dbApp.getId());
                    dbAppCopy.setShowIcon(dbApp.getShowIcon());
                    dbAppCopy.setUseKiosk(dbApp.getUseKiosk());
                    dbAppCopy.setRunAfterInstall(dbApp.isRunAfterInstall());
                    dbAppCopy.setRunAtBoot(dbApp.isRunAtBoot());
                    dbAppCopy.setSystem(dbApp.isSystem());
                    dbAppCopy.setName(dbApp.getName());
                    dbAppCopy.setPkg(dbApp.getPkg());

                    result.setApplication(dbAppCopy);
                }
            }


            return Response.OK(result);
        } catch (Exception e) {
            logger.error("Unexpected error when handling file upload", e);
            return Response.ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Upload a split-APK bundle",
            notes = "Unpacks a .xapk/.apks/zip bundle into individual APKs, hosts each, and returns the "
                    + "deployable part list for a split app.install. A plain .apk yields a single part."
    )
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Path("/bundle")
    public Response uploadBundle(@FormDataParam("file") InputStream uploadedInputStream,
                                 @FormDataParam("file") FormDataContentDisposition fileDetail) {
        if (!SecurityContext.get().hasPermission("edit_files")) {
            logger.error("Unauthorized attempt to upload a bundle by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        File bundleTmp = null;
        List<File> partTmps = new LinkedList<>();
        try {
            String fileName = new String(fileDetail.getFileName().getBytes(StandardCharsets.ISO_8859_1),
                    StandardCharsets.UTF_8);
            String lower = fileName.toLowerCase();
            Customer customer = customerDAO.findById(SecurityContext.get().getCurrentCustomerId().get());
            bundleTmp = FileUtil.createUploadFile(customer, FileUtil.adjustFileName(fileName));
            FileUtil.writeToFile(uploadedInputStream, bundleTmp.getAbsolutePath());

            // Extract the APK parts. A bare .apk is a one-part install; anything else is treated as a
            // zip container (.xapk/.apks/.apkm/.zip) and every *.apk entry becomes a part.
            if (lower.endsWith(".apk")) {
                partTmps.add(bundleTmp);
                bundleTmp = null; // it IS a part now; don't double-delete
            } else {
                partTmps = extractApkParts(bundleTmp, customer);
                if (partTmps.isEmpty()) {
                    return Response.ERROR("error.bundle.noApks"); // encrypted .apkm, or not an APK bundle
                }
            }

            // Package + version come from any part (splits share them); prefer one that parses cleanly.
            APKFileDetails meta = null;
            for (File part : partTmps) {
                try {
                    APKFileDetails d = apkFileAnalyzer.analyzeFile(part.getAbsolutePath());
                    if (d != null && d.getPkg() != null && d.getVersionCode() != 0) { meta = d; break; }
                    if (meta == null) meta = d;
                } catch (Exception ignored) { /* a config split may not parse standalone — try the next */ }
            }
            if (meta == null || meta.getPkg() == null) {
                return Response.ERROR("error.bundle.unreadable");
            }

            List<java.util.Map<String, Object>> parts = new LinkedList<>();
            for (File part : partTmps) {
                // Content-addressed name: identical bytes → identical name → re-uploading the same
                // bundle reuses the already-hosted part instead of colliding (moveFile throws on an
                // existing name). A different build hashes differently and never clobbers.
                String sha256 = sha256Hex(part);
                String partName = String.format("%s-%s.apk", meta.getPkg(), sha256);
                String url = hostFile(part, partName, customer);
                java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
                p.put("url", url);
                p.put("sha256", sha256);
                p.put("name", partName);
                parts.add(p);
            }
            partTmps.clear(); // moved/reused into the files area — nothing left to clean up

            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("name", fileName);
            out.put("packageName", meta.getPkg());
            out.put("version", meta.getVersion());
            out.put("versionCode", meta.getVersionCode());
            out.put("parts", parts);
            logger.info("Bundle {} unpacked: {} parts for {} ({})", fileName, parts.size(), meta.getPkg(),
                    meta.getVersionCode());
            return Response.OK(out);
        } catch (Exception e) {
            logger.error("Unexpected error unpacking bundle", e);
            return Response.ERROR("error.bundle.unpack");
        } finally {
            if (bundleTmp != null) bundleTmp.delete();
            for (File f : partTmps) f.delete();
        }
    }

    /** Extract every {@code *.apk} entry of a zip container to temp files. If a {@code universal.apk}
     *  is present (bundletool .apks), return ONLY it — it's a self-contained single install. */
    private List<File> extractApkParts(File zip, Customer customer) throws IOException {
        List<File> parts = new LinkedList<>();
        File universal = null;
        try (java.util.zip.ZipInputStream zin =
                     new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(new FileInputStream(zip)))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String base = new File(entry.getName()).getName().toLowerCase();
                if (!base.endsWith(".apk")) continue;
                // Output name is generated (not the entry name), so a malicious entry path can't escape.
                File out = FileUtil.createUploadFile(customer, "bundlepart");
                try (java.io.OutputStream os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(out))) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = zin.read(buf)) >= 0) os.write(buf, 0, n);
                }
                if (base.equals("universal.apk")) universal = out; else parts.add(out);
            }
        }
        if (universal != null) {
            for (File f : parts) f.delete(); // discard splits; the universal APK stands alone
            List<File> only = new LinkedList<>();
            only.add(universal);
            return only;
        }
        return parts;
    }

    /**
     * Host an extracted APK under [fileName] and return its public URL. Idempotent: when a file of
     * that name already exists (a re-upload of the same content-addressed part), reuse it rather
     * than throwing FileExistsException — the bytes are identical by construction.
     */
    private String hostFile(File apkTmp, String fileName, Customer customer) throws IOException {
        // Resolve the exact target path moveFile would use (no drift), so the existence check is reliable.
        File dest = FileUtil.resolveFile(customer, filesDirectory, null, fileName);
        File hosted;
        if (dest.exists()) {
            hosted = dest;          // same content-addressed part already served; reuse it
            apkTmp.delete();        // drop the redundant temp copy
        } else {
            hosted = FileUtil.moveFile(customer, filesDirectory, null, apkTmp.getAbsolutePath(), fileName);
            if (hosted == null) throw new IOException("failed to host " + fileName);
        }
        List<FileView> view = new LinkedList<>();
        handleFile(hosted, view, null, customer);
        if (view.isEmpty()) throw new IOException("hosted file produced no URL: " + fileName);
        return view.get(0).getUrl();
    }

    /** Lowercase hex SHA-256 — the format the agent's InstallManager verifies each part against. */
    private static String sha256Hex(File file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(file))) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e); // never on a stock JRE
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Download a file",
            notes = "Downloads the content of the file",
            responseHeaders = {@ResponseHeader(name = "Content-Disposition")}
    )
    @GET
    @Path("/{filePath}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public javax.ws.rs.core.Response downloadFile(@PathParam("filePath") @ApiParam("A path to a file") String filePath) throws Exception {
        File file = new File(filePath + "/" + URLDecoder.decode(filePath, "UTF8"));
        if (!file.exists()) {
            return javax.ws.rs.core.Response.status(404).build();
        } else {
            ContentDisposition contentDisposition = ContentDisposition.type("attachment").fileName(file.getName()).creationDate(new Date()).build();
            return javax.ws.rs.core.Response.ok( ( StreamingOutput ) output -> {
                try {
                    InputStream input = new FileInputStream( file );
                    IOUtils.copy(input, output);
                    output.flush();
                } catch ( Exception e ) { e.printStackTrace(); }
            } ).header( "Content-Disposition", contentDisposition ).build();

        }
    }

    private List<FileView> generateFilesList(String value) {
        List<FileView> files = SecurityContext.get().getCurrentUser().map(u -> {
            Customer customer = customerDAO.findById(u.getCustomerId());

            List<UploadedFile> customerFiles = value != null ?
                    uploadedFileDAO.getAllByValue(value) :
                    uploadedFileDAO.getAll();
            List<FileView> result = customerFiles.stream()
                    .map(f -> {
                        FileView hFile = new FileView(f, this.baseUrl, this.filesDirectory, customer);
                        hFile.setUsedByConfigurations(this.configurationFileDAO.getUsingConfigurations(customer.getId(), hFile.getId()));
                        hFile.setUsedByIcons(this.iconDAO.getUsingIcons(customer.getId(), hFile.getId()));
                        return hFile;
                    })
                    .collect(Collectors.toList());
            return result;

        }).orElse(new LinkedList<>());

        return files;
    }

    private void handleFile(File file, List<FileView> result, String value, Customer customer) {
        final String customerFilesBaseDir = customer.getFilesDir();
        if (file != null && file.exists()) {
            if (file.isDirectory()) {
                File[] files = file.listFiles();
                File[] filesArray = files;
                int length = files.length;

                for(int i = 0; i < length; ++i) {
                    File fl = filesArray[i];
                    this.handleFile(fl, result, value, customer);
                }
            } else if (value == null || file.getName().contains(value)) {

                String path = file.getParentFile().getAbsolutePath().replace(this.filesDirectory.replace("/", File.separator), "");
                if (!path.endsWith(File.separator)) {
                    path += File.separator;
                }
                String url;
                if (customerFilesBaseDir != null && !customerFilesBaseDir.isEmpty()) {
                    path = path.substring((File.separator + customerFilesBaseDir + File.separator).length()); // Strip off the name of directory for customer files
                    url = String.format("%s/files/%s/%s", this.baseUrl, customerFilesBaseDir, path.replace(File.separator, "/") + file.getName());
                } else {
                    url = String.format("%s/files%s", this.baseUrl, path.replace(File.separator, "/") + file.getName());
                }

                final FileView fileObj = new FileView(path, file.getName(), url, file.length());

                fileObj.setUsedByConfigurations(this.configurationFileDAO.getUsingConfigurations(customer.getId(), fileObj.getId()));
                fileObj.setUsedByIcons(this.iconDAO.getUsingIcons(customer.getId(), fileObj.getId()));

                result.add(fileObj);
            }
        }

    }
}