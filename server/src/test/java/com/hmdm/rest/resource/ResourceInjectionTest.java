package com.hmdm.rest.resource;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import com.hmdm.notification.PushService;
import com.hmdm.persistence.ApplicationDAO;
import com.hmdm.persistence.ConfigurationDAO;
import com.hmdm.persistence.ConfigurationFileDAO;
import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.IconDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.UploadedFileDAO;
import com.hmdm.persistence.UserDAO;
import com.hmdm.rest.resource.support.ExtensionPoints;
import com.hmdm.rest.resource.support.KioskBackgroundImageAssets;
import com.hmdm.service.MailchimpService;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.APKFileAnalyzer;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.Test;
import org.jvnet.hk2.guice.bridge.api.GuiceBridge;
import org.jvnet.hk2.guice.bridge.api.GuiceIntoHK2Bridge;

import java.lang.reflect.Field;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/**
 * In the server Jersey builds the resources through HK2, which resolves their dependencies from Guice through the
 * bridge (HMDMApplication). HK2 honours javax.inject only: a dependency these resources need must be injected the way
 * HK2 sees it, or it silently stays null in production while unit tests (which construct resources by hand) pass.
 */
public class ResourceInjectionTest {

    private static final KioskBackgroundImageAssets ASSETS =
            new KioskBackgroundImageAssets(null, null, "https://mdm.example", "/tmp");
    private static final ConfigurationFileDAO CONFIGURATION_FILES = new ConfigurationFileDAO(null, null, null);
    private static final IconDAO ICONS = new IconDAO(null);

    private static Object build(Class<?> resource) {
        ApplicationHandler app = new ApplicationHandler(new ResourceConfig(resource).register(MultiPartFeature.class));
        ServiceLocator locator = app.getServiceLocator();
        Injector injector = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                bindConstant().annotatedWith(Names.named("base.url")).to("https://mdm.example");
                bindConstant().annotatedWith(Names.named("files.directory")).to("/tmp");
                bind(ConfigurationDAO.class).toInstance(new ConfigurationDAO(null, null, null, null, "https://mdm.example", "/tmp", null));
                bind(ApplicationDAO.class).toInstance(new ApplicationDAO(null, null, "files", "https://mdm.example", "", null));
                bind(PushService.class).toInstance(new PushService(null, null, null, null, false));
                bind(CustomerDAO.class).toInstance(new CustomerDAO(null, null, null, null, null, null, null, "unused", 0, null));
                bind(UserDAO.class).toInstance(new UserDAO(null, 0));
                bind(ExtensionPoints.class).toInstance(Fakes.noExtensionPoints());
                bind(KioskBackgroundImageAssets.class).toInstance(ASSETS);
                bind(ConfigurationFileDAO.class).toInstance(CONFIGURATION_FILES);
                bind(IconDAO.class).toInstance(ICONS);
                bind(UnsecureDAO.class).toInstance(new UnsecureDAO(null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, "/tmp", 0, "com.mdmesh.agent"));
                bind(UploadedFileDAO.class).toInstance(new UploadedFileDAO(null, null, null, "unused"));
                bind(APKFileAnalyzer.class).toInstance(new APKFileAnalyzer("aapt"));
                bind(MailchimpService.class).toInstance(new MailchimpService("https://mdm.example", "", ""));
            }
        });
        // As HMDMApplication does on container startup.
        GuiceBridge.getGuiceBridge().initializeGuiceBridge(locator);
        locator.getService(GuiceIntoHK2Bridge.class).bridgeGuiceInjector(injector);
        return locator.createAndInitialize(resource);
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    @Test
    public void configurationResourceGetsTheFileUsersAndKioskImages() throws Exception {
        Object r = build(ConfigurationResource.class);
        assertSame(ASSETS, field(r, "backgroundImages"));
        assertSame(CONFIGURATION_FILES, field(r, "configurationFileDAO"));
        assertSame(ICONS, field(r, "iconDAO"));
    }

    @Test
    public void filesResourceGetsKioskImages() throws Exception {
        Object r = build(FilesResource.class);
        assertSame(ASSETS, field(r, "kioskImages"));
        assertNotNull(field(r, "configurationDAO"));
    }

    @Test
    public void customerResourceGetsKioskImages() throws Exception {
        Object r = build(CustomerResource.class);
        assertSame(ASSETS, field(r, "kioskImages"));
    }
}
