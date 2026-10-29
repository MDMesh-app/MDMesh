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

import com.hmdm.auth.HmdmAuthInterface;
import com.hmdm.persistence.CommonDAO;
import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Settings;
import com.hmdm.rest.filter.AuthFilter;
import com.hmdm.rest.json.AuthOptionsResponse;
import com.hmdm.rest.json.Response;
import com.hmdm.security.LoginThrottle;
import com.hmdm.rest.json.UserCredentials;
import com.hmdm.persistence.domain.User;
import com.hmdm.rest.json.view.user.UserView;
import com.hmdm.service.EmailService;
import com.hmdm.service.RsaKeyService;
import com.hmdm.util.BackgroundTaskRunnerService;
import com.hmdm.util.PasswordResetTokens;
import com.hmdm.util.PasswordUtil;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.security.PublicKey;
import java.util.Base64;

/**
 * <p>A resource for authenticating the users based on provided login/password credentials.</p>
 *
 * @author isv
 */
@Singleton
@Path( "/public/auth" )
public class AuthResource {

    private UnsecureDAO userDAO;
    private CustomerDAO customerDAO;
    private UnsecureDAO settingsDAO;
    private BackgroundTaskRunnerService taskRunner;
    private EmailService emailService;
    private RsaKeyService rsaKeyService;
    private boolean transmitPassword;
    private HmdmAuthInterface authEngine;
    private LoginThrottle loginThrottle;

    /** The message of a sign-in refused because of too many failed ones (LoginThrottle); the console shows it. */
    public static final String TOO_MANY_ATTEMPTS = "error.auth.too.many.attempts";

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public AuthResource() {
    }

    /**
     * <p>Constructs new <code>AuthResource</code> instance. This implementation does nothing.</p>
     */
    @Inject
    public AuthResource(UnsecureDAO userDAO,
                        CustomerDAO customerDAO,
                        UnsecureDAO settingsDAO,
                        BackgroundTaskRunnerService taskRunner,
                        EmailService emailService,
                        RsaKeyService rsaKeyService,
                        @Named("transmit.password") boolean transmitPassword,
                        @Named("auth.class") HmdmAuthInterface authEngine,
                        LoginThrottle loginThrottle) {
        this.userDAO = userDAO;
        this.customerDAO = customerDAO;
        this.settingsDAO = settingsDAO;
        this.taskRunner = taskRunner;
        this.emailService = emailService;
        this.rsaKeyService = rsaKeyService;
        this.transmitPassword = transmitPassword;
        this.authEngine = authEngine;
        this.loginThrottle = loginThrottle;
    }

    /**
     * <p>Authenticates the user based on provided credentials and responds with the user account details in case of
     * successful authentication.</p>
     *
     * @param credentials the credentials to be used for authenticating the user to application.
     * @param req an incoming request.
     * @return a response containing the details for authenticated user.
     */
    @POST
    @Path( "/login" )
    @Consumes( MediaType.APPLICATION_JSON )
    @Produces( MediaType.APPLICATION_JSON )
    public Response login( UserCredentials credentials,
                           @Context HttpServletRequest req,
                           @Context HttpServletResponse resp ) {
        if ( credentials.getLogin() == null || credentials.getPassword() == null ) {
            return Response.ERROR();
        }

        // Failed sign-ins are limited per client address and per account (LoginThrottle), without sleeping: a refused
        // attempt is answered at once, with the same message whichever limit refused, and its password is never
        // checked. The account is looked up first, so every attempt costs that lookup.
        User user = authEngine.findUser(credentials.getLogin());
        LoginThrottle.Verdict verdict = loginThrottle.begin(req, user, credentials.getLogin());
        if (!verdict.allowed()) {
            return Response.ERROR(TOO_MANY_ATTEMPTS);
        }
        if (user == null) {
            return Response.ERROR();
        }

        String password = null;
        if (transmitPassword) {
            byte[] passEnc = Base64.getDecoder().decode(credentials.getPassword());
            password = rsaKeyService.decrypt(passEnc);
        } else {
            password = credentials.getPassword();
        }

        // Web app sends MD5 hash, we need to re-hash it to compare with the DB value
        if (!authEngine.authenticate(user, password)) {
            return Response.ERROR();
        }
        loginThrottle.succeeded(verdict.attempt());

        try {
            this.taskRunner.submitTask(() -> {
                this.customerDAO.recordLastLoginTime(user.getCustomerId(), System.currentTimeMillis());
            });

            HttpSession userSession = req.getSession();
            userSession.setAttribute(AuthFilter.sessionCredentials, user );

            boolean secondFactorPending = false;
            Settings settings = settingsDAO.getSettings(user.getCustomerId());
            if (settings != null) {
                if (settings.isTwoFactor()) {
                    userSession.setAttribute(AuthFilter.twoFactorNeeded, "true");
                    user.setTwoFactor(true);
                    secondFactorPending = true;
                }
                user.setIdleLogout(settings.getIdleLogout());
            }

            if (user.getAuthToken() == null || user.getAuthToken().length() == 0) {
                user.setAuthToken(PasswordUtil.generateToken());
                userDAO.setAuthTokenUnsecure(user);
            }

            // A forced change at first sign-in uses a reset token, which expires. The password was just proven, so a
            // token that expired (or was seeded without an issue time) is replaced with a fresh one.
            if (user.isPasswordReset() && !PasswordResetTokens.isFresh(user, System.currentTimeMillis())) {
                PasswordResetTokens.issue(user, System.currentTimeMillis());
                userDAO.setPasswordResetTokenUnsecure(user);
            }

            user.setPassword(null);

            user.setSingleCustomer(userDAO.isSingleCustomer());
            user.setDeploymentAdmin(userDAO.mayManageDeployment(user));

            // A full sign-in: this browser and this address now know the account, so someone failing sign-ins for it
            // can't lock them out (LoginThrottle). Not while a second factor is pending: MDMesh has no second-factor
            // step today, so such a session never gets further, and the password alone must not make a browser known.
            if (!secondFactorPending) {
                String knownBrowser = loginThrottle.signedIn(verdict.attempt(), user);
                if (resp != null) {
                    resp.addHeader("Set-Cookie", knownBrowser);
                }
            }

            return Response.OK(new UserView(user));
        } catch (Exception e) {
            e.printStackTrace();
            return Response.INTERNAL_ERROR();
        }
    }

    /**
     * <p>Logs the current user out by invalidating the current session.</p>
     *
     * @param req an incoming request.
     */
    @POST
    @Path( "/logout" )
    public void logout( @Context HttpServletRequest req ) {
        HttpSession session = req.getSession( false );
        if ( session != null ) {
            session.invalidate();
        }
    }


    /**
     * <p>Returns the login options</p>
     */
    @GET
    @Path( "/options" )
    @Produces( MediaType.APPLICATION_JSON )
    public Response options() {
        AuthOptionsResponse response = new AuthOptionsResponse();
        response.setSignup(false);  // MDMesh has no self-signup
        response.setRecover(emailService.isConfigured());
        if (transmitPassword) {
            PublicKey publicKey = rsaKeyService.getPublicKey();
            byte[] keyBytes = publicKey.getEncoded();
            String encoded = Base64.getEncoder().encodeToString(keyBytes);
            response.setPublicKey(encoded);
        }
        return Response.OK(response);
    }
}
