package com.hmdm;

import com.google.inject.Injector;
import com.hmdm.plugin.PluginList;
import io.swagger.jaxrs.config.BeanConfig;
import io.swagger.jaxrs.listing.ApiListingResource;
import io.swagger.jaxrs.listing.SwaggerSerializers;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.spi.Container;
import org.glassfish.jersey.server.spi.ContainerLifecycleListener;
import org.glassfish.jersey.servlet.ServletContainer;
import org.jvnet.hk2.guice.bridge.api.GuiceBridge;
import org.jvnet.hk2.guice.bridge.api.GuiceIntoHK2Bridge;

import javax.inject.Inject;

/**
 * <p>A configuration for HMDM server application.</p>
 *
 * @author isv
 */
public class HMDMApplication extends ResourceConfig {

    /**
     * <p>Constructs new <code>HMDMApplication</code> instance and initializes the Guice-HK2 bridge.</p>
     */
    @Inject
    public HMDMApplication(final ServiceLocator serviceLocator) {
        // Resources and providers: core's, and a fork plugin's (web/EXTENDING.md); the plugin scan's packages.
        packages(PluginList.PACKAGES.toArray(new String[0]));
        register(MultiPartFeature.class);
        register(new ContainerLifecycleListener() {
            public void onStartup(Container container) {
                ServletContainer servletContainer = (ServletContainer) container;
                GuiceBridge.getGuiceBridge().initializeGuiceBridge(serviceLocator);
                GuiceIntoHK2Bridge guiceBridge = serviceLocator.getService(GuiceIntoHK2Bridge.class);
                Injector injector = (Injector) servletContainer.getServletContext().getAttribute(Injector.class.getName());
                guiceBridge.bridgeGuiceInjector(injector);

                BeanConfig beanConfig = new BeanConfig();
                beanConfig.setTitle("Headwind MDM API");
                beanConfig.setVersion("0.0.2");
                beanConfig.setSchemes(new String[]{"http"});
                beanConfig.setBasePath(servletContainer.getServletContext().getContextPath() + "/rest");
                beanConfig.setResourcePackage(swaggerResourcePackage());
                beanConfig.setScan(true);
                beanConfig.setPrettyPrint(true);

            }

            public void onReload(Container container) {
            }

            public void onShutdown(Container container) {
            }
        });

        register(ApiListingResource.class);
        register(SwaggerSerializers.class);

    }

    /**
     * <p>{@link PluginList#PACKAGES} as Swagger's {@code BeanConfig.setResourcePackage} takes them,
     * comma-separated.</p>
     */
    static String swaggerResourcePackage() {
        return String.join(",", PluginList.PACKAGES);
    }

}
