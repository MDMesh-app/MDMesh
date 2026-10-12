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

import com.hmdm.persistence.domain.ApplicationType;
import com.hmdm.rest.json.NameResponse;
import com.hmdm.util.FileUtil;
import net.glxn.qrgen.core.image.ImageType;
import net.glxn.qrgen.javase.QRCode;
import org.apache.poi.util.IOUtils;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.Response;
import com.hmdm.util.CryptoUtil;
import com.hmdm.util.StringUtil;

import javax.servlet.ServletOutputStream;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.StreamingOutput;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import static com.hmdm.util.FileUtil.writeToFile;

/**
 * <p>A publicly available API which does not require authentication/authorization.</p>
 *
 * @author isv
 */
@Singleton
@Path("/public")
@Api(tags = {"Mobile client API"})
public class PublicResource {

    private static final Logger logger  = LoggerFactory.getLogger(PublicResource.class);

    private UnsecureDAO unsecureDAO;
    private CustomerDAO customerDAO;
    private String hashSecret;
    private String filesDirectory;
    private String baseUrl;

    private String appName;
    private String appLogo;
    private String appVendorName;
    private String appVendorLink;
    private String appSignupLink;
    private String appTermsLink;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public PublicResource() {
    }

    /**
     * <p>Constructs new <code>PublicResource</code> instance. This implementation does nothing.</p>
     */
    @Inject
    public PublicResource(@Named("files.directory") String filesDirectory,
                          @Named("base.url") String baseUrl,
                          @Named("rebranding.name") String appName,
                          @Named("rebranding.logo") String appLogo,
                          @Named("rebranding.vendor.name") String appVendorName,
                          @Named("rebranding.vendor.link") String appVendorLink,
                          @Named("rebranding.signup.link") String appSignupLink,
                          @Named("rebranding.terms.link") String appTermsLink,
                          UnsecureDAO unsecureDAO,
                          CustomerDAO customerDAO,
                          @Named("hash.secret") String hashSecret) {
        this.filesDirectory = filesDirectory;
        this.baseUrl = baseUrl;
        this.appName = appName;
        this.appLogo = appLogo;
        this.appVendorName = appVendorName;
        this.appVendorLink = appVendorLink;
        this.appSignupLink = appSignupLink;
        this.appTermsLink = appTermsLink;
        this.unsecureDAO = unsecureDAO;
        this.customerDAO = customerDAO;
        this.hashSecret = hashSecret;
    }
    
    // =================================================================================================================
    @ApiOperation(
            value = "Get name and vendor",
            notes = "Gets the application name and vendor for rebranding purposes."
    )
    @GET
    @Path("/name")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRebranding() {
        NameResponse nameResponse = new NameResponse();
        nameResponse.setAppName(appName);
        nameResponse.setVendorName(appVendorName);
        nameResponse.setVendorLink(appVendorLink);
        nameResponse.setSignupLink(appSignupLink);
        nameResponse.setTermsLink(appTermsLink);
        return Response.OK(nameResponse);
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get logo",
            notes = "Returns the rebranded logo."
    )
    @GET
    @Path("/logo")
    @Produces(MediaType.APPLICATION_JSON)
    public javax.ws.rs.core.Response getRebrandedLogo() {
        try {
            if (!appLogo.equals("")) {
                File file = new File(appLogo);
                if (file.exists()) {
                    InputStream input = new FileInputStream(file);

                    return javax.ws.rs.core.Response.ok( (StreamingOutput) output -> {
                        IOUtils.copy(input, output);
                    } )
                            .header("Cache-Control", "no-cache")
                            .header( "Content-Type", "image/png" ).build();
                } else {
                    System.out.println("Not found: " + file.getAbsolutePath());
                    return javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.NOT_FOUND).build();
                }
            } else {
                return javax.ws.rs.core.Response.temporaryRedirect(new URI("../images/logo.png")).build();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return javax.ws.rs.core.Response.serverError().build();
        }
    }
}
