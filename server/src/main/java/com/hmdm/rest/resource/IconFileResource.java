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

import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.UploadedFileDAO;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.persistence.domain.UploadedFile;
import com.hmdm.rest.json.FileUploadResult;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.imgscalr.Scalr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.UUID;

/**
 * <p>$</p>
 *
 * @author isv
 */
@Singleton
@Path("/private/icon-files")
public class IconFileResource {

    private static final Logger logger = LoggerFactory.getLogger(IconFileResource.class);

    private static final String DELIMITER = "1111111";

    /** Icons are scaled to 144 px; nothing legitimate comes close to these. */
    static final long MAX_ICON_BYTES = 1024 * 1024;
    static final int MAX_ICON_DIMENSION = 1024;

    /** The whole stream if it has at most {@code max} bytes, else null (reading at most max + 1 bytes). */
    private static byte[] readAtMost(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > max) {
                return null;
            }
        }
        return out.toByteArray();
    }

    /** The header declares a size beyond {@link #MAX_ICON_DIMENSION}. */
    private static final class TooLargeImageException extends Exception {
    }

    /**
     * Decodes the image only after its header-declared size is checked; null if it is not a readable image (any read
     * error, a truncated header included).
     */
    private static BufferedImage decodeBounded(byte[] bytes) throws TooLargeImageException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > MAX_ICON_DIMENSION || h > MAX_ICON_DIMENSION) {
                    throw new TooLargeImageException();
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private UploadedFileDAO uploadedFileDAO;
    private CustomerDAO customerDAO;
    private String filesDirectory;


    /**
     * <p>Constructs new <code>IconFileResource</code> instance. This implementation does nothing.</p>
     */
    @Inject
    public IconFileResource(UploadedFileDAO uploadedFileDAO,
                            CustomerDAO customerDAO,
                            @Named("files.directory") String filesDirectory) {
        this.uploadedFileDAO = uploadedFileDAO;
        this.customerDAO = customerDAO;
        this.filesDirectory = filesDirectory;
    }


    // =================================================================================================================
    @ApiOperation(
            value = "Upload icon",
            notes = "Uploads the icon to server. Returns a path to uploaded icon file",
            response = FileUploadResult.class
    )
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadIconFile(@FormDataParam("file") InputStream uploadedInputStream,
                                   @ApiParam("An icon file to upload") @FormDataParam("file") FormDataContentDisposition fileDetail) throws Exception {
        if (!SecurityContext.get().hasPermission("settings")) {
            logger.error("Unauthorized attempt to upload an icon by user " + SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            byte[] bytes = readAtMost(uploadedInputStream, MAX_ICON_BYTES);
            if (bytes == null) {
                logger.warn("Rejecting an icon upload larger than {} bytes", MAX_ICON_BYTES);
                return Response.ERROR("error.icon.size.invalid");
            }
            final BufferedImage img;
            try {
                img = decodeBounded(bytes);
            } catch (TooLargeImageException e) {
                logger.warn("Rejecting an icon upload larger than {}px", MAX_ICON_DIMENSION);
                return Response.ERROR("error.icon.dimension.invalid");
            }
            if (img == null) {
                logger.warn("Rejecting an icon upload that is not a readable image");
                return Response.ERROR("error.icon.format.invalid");
            }
            if (img.getWidth() != img.getHeight()) {
                logger.error("Rejecting the icon file upload due to unequal icon width and height");
                return Response.ERROR("error.icon.dimension.invalid");
            }

            return SecurityContext.get().getCurrentCustomerId().map(customerId -> {
                try {
                    final Customer customer = this.customerDAO.findById(customerId);
                    final String customerFilesDir = customer.getFilesDir();

                    final File customerFilesDirectory = new File(this.filesDirectory, customerFilesDir);
                    if (!customerFilesDirectory.exists()) {
                        customerFilesDirectory.mkdirs();
                    }

                    File iconFile = new File(customerFilesDirectory, UUID.randomUUID().toString() + ".png");

                    BufferedImage scaledImage = Scalr.resize(img, 144);
                    ImageIO.write(scaledImage, "png", iconFile);

                    UploadedFile uploadedFile = new UploadedFile();
                    uploadedFile.setCustomerId(customerId);
                    uploadedFile.setFilePath(iconFile.getName());
                    uploadedFile.setUploadTime(System.currentTimeMillis());

                    uploadedFile = this.uploadedFileDAO.insert(uploadedFile);

                    return Response.OK(uploadedFile);
                } catch (Exception e) {
                    logger.error("Unexpected error when handling icon file upload", e);
                    return Response.INTERNAL_ERROR();
                }

            }).orElseThrow(SecurityException::onAnonymousAccess);

        } catch (Exception e) {
            logger.error("Unexpected error when handling icon file upload", e);
            return Response.INTERNAL_ERROR();
        }
    }

}
