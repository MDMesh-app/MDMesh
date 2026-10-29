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

package com.hmdm.guice.module;

import com.google.inject.AbstractModule;
import com.google.inject.name.Names;
import com.hmdm.auth.HmdmAuthInterface;
import com.hmdm.auth.LocalAuth;
import com.hmdm.persistence.domain.Application;
import com.hmdm.rest.filter.LegacyDeviceApiFilter;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;

public class ConfigureModule extends AbstractModule {
    private final String baseDirectoryParameter = "base.directory";
    private final String filesDirectoryParameter = "files.directory";
    private final String baseUrlParameter = "base.url";
    private final String pluginFilesDirectoryParameter = "plugins.files.directory";
    private final String usageScenarioParameter = "usage.scenario";
    private final String secureEnrollmentParameter = "secure.enrollment";
    private final String hashSecretParameter = "hash.secret";
    private final String hstsParameter = "strict.transport.security";
    private final String preventDuplicateParameter = "prevent.duplicate.enrollment";
    private final String transmitPasswordParameter = "transmit.password";
    private final String authClassParameter = "auth.class";
    private final String aaptCommandParameter = "aapt.command";
    private final String roleOrgadminIdParameter = "role.orgadmin.id";
    private final String launcherPackageParameter = "launcher.package";
    private final String rebrandingNameParameter = "rebranding.name";
    private final String rebrandingVendorParameter = "rebranding.vendor.name";
    private final String rebrandingVendorLinkParameter = "rebranding.vendor.link";
    private final String rebrandingLogoParameter = "rebranding.logo";
    private final String rebrandingMobileNameParameter = "rebranding.mobile.name";
    private final String rebrandingSignupLinkParameter = "rebranding.signup.link";
    private final String rebrandingTermsLinkParameter = "rebranding.terms.link";
    private final String smtpHostParameter = "smtp.host";
    private final String smtpPortParameter = "smtp.port";
    private final String smtpSslParameter = "smtp.ssl";
    private final String smtpStartTlsParameter = "smtp.starttls";
    private final String smtpSslProtocolsParameter = "smtp.ssl.protocols";
    private final String smtpSslTrustParameter = "smtp.ssl.trust";
    private final String smtpUsernameParameter = "smtp.username";
    private final String smtpPasswordParameter = "smtp.password";
    private final String smtpFromParameter = "smtp.from";
    private final String deviceFastSearchCharsParameter = "device.fast.search.chars";
    private final String sqlInitScriptPath = "sql.init.script.path";
    private final String proxyAddresses = "proxy.addresses";
    private final String proxyIpHeader = "proxy.ip.header";
    private final String customerAutoStatus = "customer.auto.status";
    private final String adminEmail = "admin.email";
    private final String mailchimpUrl = "mailchimp.url";
    private final String mailchimpKey = "mailchimp.key";
    private final String emailRecoverySubj = "email.recovery.subj";
    private final String emailRecoveryBody = "email.recovery.body";
    private final String ldapAdminBind = "ldap.admin.bind";
    private final String ldapHost = "ldap.host";
    private final String ldapPort = "ldap.port";
    private final String ldapBaseDn = "ldap.base.dn";
    private final String ldapAdminDn = "ldap.admin.dn";
    private final String ldapAdminPassword = "ldap.admin.password";
    private final String ldapUsernameAttribute = "ldap.username.attribute";
    private final String ldapUserDn = "ldap.user.dn";
    private final String ldapDefaultRole = "ldap.default.role";
    private final String ldapCustomerId = "ldap.customer.id";
    private final String deviceAllowedAddress = "device.allowed.address";
    private final String uiAllowedAddress = "ui.allowed.address";
    private final String apkTrustedUrlParameter = "apk.trusted.url";
    private final String jwtSecretKey = "jwt.secretkey";
    private final String jwtValidity = "jwt.validity";
    private final String jwtValidityForRememberMe = "jwt.validityrememberme";
    private final ServletContext context;

    public ConfigureModule(ServletContext context) {
        this.context = context;
    }

    /**
     * {@code secure.enrollment} as configured: it makes {@code /files/*} demand the Headwind launcher's request
     * signature. The MDMesh agent never sends one, so with the legacy device API off this also blocks the agent's
     * APK and app downloads; that is logged once at startup, and the setting is still honoured.
     */
    static boolean secureEnrollment(ServletContext context) {
        String value = context.getInitParameter("secure.enrollment");
        boolean on = value != null && (value.equals("1") || value.equalsIgnoreCase("true"));
        if (on && !LegacyDeviceApiFilter.isEnabled(context)) {
            LoggerFactory.getLogger(ConfigureModule.class).warn("secure.enrollment is on: /files/* downloads need the "
                    + "Headwind launcher's request signature, which the MDMesh agent does not send, so agent APK and "
                    + "app downloads will fail. Set it to 0 unless you need launcher-signed downloads.");
        }
        return on;
    }

    protected void configure() {
        this.bindConstant().annotatedWith(Names.named(filesDirectoryParameter)).to(this.context.getInitParameter(filesDirectoryParameter));
        this.bindConstant().annotatedWith(Names.named(baseUrlParameter)).to(this.context.getInitParameter(baseUrlParameter));
        this.bindConstant().annotatedWith(Names.named(pluginFilesDirectoryParameter)).to(this.context.getInitParameter(pluginFilesDirectoryParameter));
        this.bindConstant().annotatedWith(Names.named(usageScenarioParameter)).to(this.context.getInitParameter(usageScenarioParameter));
        this.bindConstant().annotatedWith(Names.named(secureEnrollmentParameter)).to(secureEnrollment(this.context));
        this.bindConstant().annotatedWith(Names.named(hashSecretParameter)).to(this.context.getInitParameter(hashSecretParameter));
        String hsts = this.context.getInitParameter(hstsParameter);
        this.bindConstant().annotatedWith(Names.named(hstsParameter)).to(
                hsts != null && (hsts.equals("1") || hsts.equalsIgnoreCase("true"))
        );
        String preventDuplicate = this.context.getInitParameter(preventDuplicateParameter);
        this.bindConstant().annotatedWith(Names.named(preventDuplicateParameter)).to(
                preventDuplicate != null && (preventDuplicate.equals("1") || preventDuplicate.equalsIgnoreCase("true"))
        );
        this.bindConstant().annotatedWith(Names.named(aaptCommandParameter)).to(this.context.getInitParameter(aaptCommandParameter));
        this.bindConstant().annotatedWith(Names.named(roleOrgadminIdParameter)).to(
                Integer.parseInt(this.context.getInitParameter(roleOrgadminIdParameter))
        );
        String launcherPackage = this.context.getInitParameter(launcherPackageParameter);
        if (launcherPackage == null) {
            launcherPackage = Application.DEFAULT_LAUNCHER_PACKAGE;
        }
        this.bindConstant().annotatedWith(Names.named(launcherPackageParameter)).to(launcherPackage);

        // Optional parameters (Guice doesn't allow to bind string to null)
        String opt;

        opt = this.context.getInitParameter(baseDirectoryParameter);
        this.bindConstant().annotatedWith(Names.named(baseDirectoryParameter)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(transmitPasswordParameter);
        this.bindConstant().annotatedWith(Names.named(transmitPasswordParameter)).to(
                opt != null && (opt.equals("1") || opt.equalsIgnoreCase("true")));
        opt = this.context.getInitParameter(authClassParameter);
        try {
            Class authImpl = opt != null ? Class.forName("com.hmdm.auth." + opt + "Auth") : LocalAuth.class;
            this.bind(HmdmAuthInterface.class).annotatedWith(Names.named(authClassParameter))
                    .to(authImpl);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Rebranding
        opt = this.context.getInitParameter(rebrandingNameParameter);
        this.bindConstant().annotatedWith(Names.named(rebrandingNameParameter)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(rebrandingVendorParameter);
        this.bindConstant().annotatedWith(Names.named(rebrandingVendorParameter)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(rebrandingVendorLinkParameter);
        this.bindConstant().annotatedWith(Names.named(rebrandingVendorLinkParameter)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(rebrandingLogoParameter);
        this.bindConstant().annotatedWith(Names.named(rebrandingLogoParameter)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(rebrandingMobileNameParameter);
        this.bindConstant().annotatedWith(Names.named(rebrandingMobileNameParameter)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(rebrandingSignupLinkParameter);
        this.bindConstant().annotatedWith(Names.named(rebrandingSignupLinkParameter)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(rebrandingTermsLinkParameter);
        this.bindConstant().annotatedWith(Names.named(rebrandingTermsLinkParameter)).to(opt != null ? opt : "");

        // SMTP
        opt = this.context.getInitParameter(smtpHostParameter);
        this.bindConstant().annotatedWith(Names.named(smtpHostParameter)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(smtpPortParameter);
        this.bindConstant().annotatedWith(Names.named(smtpPortParameter)).to(opt != null && !opt.equals("") ? Integer.parseInt(opt): 25);
        opt = this.context.getInitParameter(smtpSslParameter);
        this.bindConstant().annotatedWith(Names.named(smtpSslParameter)).to(
                opt != null && (opt.equals("1") || opt.equalsIgnoreCase("true")));
        opt = this.context.getInitParameter(smtpStartTlsParameter);
        this.bindConstant().annotatedWith(Names.named(smtpStartTlsParameter)).to(
                opt != null && (opt.equals("1") || opt.equalsIgnoreCase("true")));
        opt = this.context.getInitParameter(smtpSslProtocolsParameter);
        this.bindConstant().annotatedWith(Names.named(smtpSslProtocolsParameter)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(smtpSslTrustParameter);
        this.bindConstant().annotatedWith(Names.named(smtpSslTrustParameter)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(smtpUsernameParameter);
        this.bindConstant().annotatedWith(Names.named(smtpUsernameParameter)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(smtpPasswordParameter);
        this.bindConstant().annotatedWith(Names.named(smtpPasswordParameter)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(smtpFromParameter);
        this.bindConstant().annotatedWith(Names.named(smtpFromParameter)).to(opt != null ? opt : "");

        // Other
        opt = this.context.getInitParameter(deviceFastSearchCharsParameter);
        this.bindConstant().annotatedWith(Names.named(deviceFastSearchCharsParameter)).to(opt != null && !opt.equals("") ? Integer.parseInt(opt): 5);
        opt = this.context.getInitParameter(sqlInitScriptPath);
        this.bindConstant().annotatedWith(Names.named(sqlInitScriptPath)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(proxyAddresses);
        this.bindConstant().annotatedWith(Names.named(proxyAddresses)).to(opt != null ? opt : "");
        // 1 on Docker (docker/entrypoint.sh): only the edge reaches the server there, so any private-network peer is
        // the install's proxy. Native leaves it off: a LAN client can reach Tomcat directly (LoginThrottle).
        opt = this.context.getInitParameter(com.hmdm.security.LoginThrottle.PRIVATE_PEERS_ARE_PROXIES);
        this.bindConstant().annotatedWith(Names.named(com.hmdm.security.LoginThrottle.PRIVATE_PEERS_ARE_PROXIES)).to(
                opt != null && (opt.trim().equals("1") || opt.trim().equalsIgnoreCase("true")));
        opt = this.context.getInitParameter(proxyIpHeader);
        this.bindConstant().annotatedWith(Names.named(proxyIpHeader)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(customerAutoStatus);
        this.bindConstant().annotatedWith(Names.named(customerAutoStatus)).to(
                opt != null && (opt.equals("1") || opt.equalsIgnoreCase("true")));
        opt = this.context.getInitParameter(adminEmail);
        this.bindConstant().annotatedWith(Names.named(adminEmail)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(mailchimpUrl);
        this.bindConstant().annotatedWith(Names.named(mailchimpUrl)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(mailchimpKey);
        this.bindConstant().annotatedWith(Names.named(mailchimpKey)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(emailRecoverySubj);
        this.bindConstant().annotatedWith(Names.named(emailRecoverySubj)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(emailRecoveryBody);
        this.bindConstant().annotatedWith(Names.named(emailRecoveryBody)).to(opt != null ? opt : "");

        opt = this.context.getInitParameter(ldapAdminBind);
        this.bindConstant().annotatedWith(Names.named(ldapAdminBind)).to(
                opt != null && (opt.equals("1") || opt.equalsIgnoreCase("true")));
        opt = this.context.getInitParameter(ldapHost);
        this.bindConstant().annotatedWith(Names.named(ldapHost)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(ldapPort);
        this.bindConstant().annotatedWith(Names.named(ldapPort)).to(opt != null && !opt.equals("") ? Integer.parseInt(opt): 389);
        opt = this.context.getInitParameter(ldapBaseDn);
        this.bindConstant().annotatedWith(Names.named(ldapBaseDn)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(ldapAdminDn);
        this.bindConstant().annotatedWith(Names.named(ldapAdminDn)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(ldapAdminPassword);
        this.bindConstant().annotatedWith(Names.named(ldapAdminPassword)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(ldapUsernameAttribute);
        this.bindConstant().annotatedWith(Names.named(ldapUsernameAttribute)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(ldapUserDn);
        this.bindConstant().annotatedWith(Names.named(ldapUserDn)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(ldapDefaultRole);
        this.bindConstant().annotatedWith(Names.named(ldapDefaultRole)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(ldapCustomerId);
        this.bindConstant().annotatedWith(Names.named(ldapCustomerId)).to(opt != null && !opt.equals("") ? Integer.parseInt(opt): 1);
        opt = this.context.getInitParameter(deviceAllowedAddress);
        this.bindConstant().annotatedWith(Names.named(deviceAllowedAddress)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(uiAllowedAddress);
        this.bindConstant().annotatedWith(Names.named(uiAllowedAddress)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(apkTrustedUrlParameter);
        this.bindConstant().annotatedWith(Names.named(apkTrustedUrlParameter)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(jwtSecretKey);
        this.bindConstant().annotatedWith(Names.named(jwtSecretKey)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(jwtValidity);
        this.bindConstant().annotatedWith(Names.named(jwtValidity)).to(opt != null ? opt : "");
        opt = this.context.getInitParameter(jwtValidityForRememberMe);
        this.bindConstant().annotatedWith(Names.named(jwtValidityForRememberMe)).to(opt != null ? opt : "");
    }
}