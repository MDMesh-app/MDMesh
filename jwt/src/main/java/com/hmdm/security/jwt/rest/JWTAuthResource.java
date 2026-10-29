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

package com.hmdm.security.jwt.rest;

import javax.inject.Inject;
import javax.inject.Singleton;
import com.hmdm.persistence.CustomerDAO;
import com.hmdm.util.BackgroundTaskRunnerService;
import com.hmdm.security.LoginThrottle;
import com.hmdm.util.PasswordUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.ResponseHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.rest.json.UserCredentials;
import com.hmdm.security.jwt.TokenProvider;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * <p>A resource for authenticating the requests bearing the JWT-token.</p>
 *
 * @author isv
 */
@Singleton
@Path( "/public/jwt" )
@Api(tags = {"Authentication"})
public class JWTAuthResource {

    /**
     * <p>A name of HTTP request's "Authorization" header.</p>
     */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /** 429: not in the JAX-RS 2.0 Response.Status list. */
    private static final int TOO_MANY_REQUESTS = 429;

    /**
     * <p>A logger used for logging various events encountered during the lifecycle.</p>
     */
    private final Logger log = LoggerFactory.getLogger("JWTAuth");

    private TokenProvider tokenProvider;

    private UnsecureDAO userDAO;

    private CustomerDAO customerDAO;

    private BackgroundTaskRunnerService taskRunner;

    private LoginThrottle loginThrottle;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public JWTAuthResource() {
    }

    /**
     * <p>Constructs new <code>JWTAuthResource</code> instance. This implementation does nothing.</p>
     */
    @Inject
    public JWTAuthResource(TokenProvider tokenProvider,
                           UnsecureDAO userDAO,
                           CustomerDAO customerDAO,
                           BackgroundTaskRunnerService taskRunner,
                           LoginThrottle loginThrottle) {
        this.tokenProvider = tokenProvider;
        this.userDAO = userDAO;
        this.customerDAO = customerDAO;
        this.taskRunner = taskRunner;
        this.loginThrottle = loginThrottle;
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Authenticate client",
            notes = "Authenticates the client using provided credentials and responds with JWT token in case of " +
                    "success. The password field should contain the MD5 hash of the actual password. " +
                    "The returned JWT token must be included into 'Authorization' header for all " +
                    "sub-sequent requests from the same client. A successful sign-in also sets the cookie " +
                    "MDMESH_SIGNIN (path /rest/public, 30 days): a client that keeps it and sends it with later " +
                    "sign-ins keeps signing in while someone else's failed sign-ins limit the account.",
            response = JWTToken.class,
            responseHeaders = {@ResponseHeader(name = AUTHORIZATION_HEADER)}
    )
    @ApiResponses({
            @ApiResponse(code = 400, message = "Bad request"),
            @ApiResponse(code = 401, message = "Unauthorized"),
            @ApiResponse(code = 429, message = "Too many failed sign-ins: try again after Retry-After seconds "
                    + "(always the full 15-minute window)")
    })
    @POST
    @Path( "/login" )
    @Consumes( MediaType.APPLICATION_JSON )
    @Produces( MediaType.APPLICATION_JSON )
    public Response login(UserCredentials credentials, @Context HttpServletRequest req) {
        try {
            if (credentials.getLogin() == null || credentials.getPassword() == null) {
                return Response.status(Response.Status.BAD_REQUEST).build();
            }

            // Shares the console's limit on failed sign-ins (LoginThrottle); nothing sleeps. A refusal is the same 429
            // and Retry-After whichever limit refused.
            User user = userDAO.findByLoginOrEmail(credentials.getLogin());
            LoginThrottle.Verdict verdict = loginThrottle.begin(req, user, credentials.getLogin());
            if (!verdict.allowed()) {
                return Response.status(TOO_MANY_REQUESTS)
                        .header("Retry-After", Long.toString(verdict.retryAfterSeconds()))
                        .build();
            }
            if (user == null) {
                return Response.status(Response.Status.UNAUTHORIZED).build();
            } else if (!PasswordUtil.passwordMatch(credentials.getPassword(), user.getPassword())) {
                // No lastLoginFail write: it would make a failure for a real account measurably slower (LocalAuth)
                return Response.status(Response.Status.UNAUTHORIZED).build();
            }
            loginThrottle.succeeded(verdict.attempt());
            if (userDAO.isTwoFactorRequired(user)) {
                // A token is issued on the password alone, so it can't be issued where a second factor is required
                log.warn("Token not issued for user {}: two-factor authentication is required", user.getLogin());
                return Response.status(Response.Status.FORBIDDEN).build();
            } else {
                this.taskRunner.submitTask(() -> {
                    this.customerDAO.recordLastLoginTime(user.getCustomerId(), System.currentTimeMillis());
                });

                if (user.getAuthToken() == null || user.getAuthToken().length() == 0) {
                    user.setAuthToken(PasswordUtil.generateToken());
                    userDAO.setAuthTokenUnsecure(user);
                }
                user.setPassword(null);

                String token = tokenProvider.createToken(user, false);

                JWTToken result = new JWTToken(token);

                Response.ResponseBuilder response = Response.ok(result);
                response.header(AUTHORIZATION_HEADER, "Bearer " + token);
                // A full sign-in: this client and its address now know the account, as a console browser does, so
                // someone failing sign-ins for it can't lock out a client that keeps cookies, or its host (LoginThrottle).
                response.header("Set-Cookie", loginThrottle.signedIn(verdict.attempt(), user));

                return response.build();
            }
        } catch (Exception e) {
            log.error("Unexpected error when authenticating user", e);
            return Response.serverError().build();
        }
    }
}
