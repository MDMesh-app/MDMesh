package com.hmdm;

import com.google.inject.Injector;
import io.swagger.v3.jaxrs2.integration.JaxrsOpenApiContextBuilder;
import io.swagger.v3.jaxrs2.integration.resources.OpenApiResource;
import io.swagger.v3.oas.integration.OpenApiConfigurationException;
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.spi.Container;
import org.glassfish.jersey.server.spi.ContainerLifecycleListener;
import org.glassfish.jersey.servlet.ServletContainer;
import org.jvnet.hk2.guice.bridge.api.GuiceBridge;
import org.jvnet.hk2.guice.bridge.api.GuiceIntoHK2Bridge;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;

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
        packages("com.hmdm");
        register(MultiPartFeature.class);
        register(new ContainerLifecycleListener() {
            public void onStartup(Container container) {
                ServletContainer servletContainer = (ServletContainer) container;
                GuiceBridge.getGuiceBridge().initializeGuiceBridge(serviceLocator);
                GuiceIntoHK2Bridge guiceBridge = serviceLocator.getService(GuiceIntoHK2Bridge.class);
                Injector injector = (Injector) servletContainer.getServletContext().getAttribute(Injector.class.getName());
                guiceBridge.bridgeGuiceInjector(injector);

                SwaggerConfiguration openApiConfiguration = new SwaggerConfiguration()
                        .resourcePackages(Set.of("com.hmdm"))
                        .prettyPrint(true)
                        .openAPI(new OpenAPI()
                                .info(new Info().title("Headwind MDM API").version("0.0.2"))
                                .servers(List.of(new Server().url(
                                        servletContainer.getServletContext().getContextPath() + "/rest"))));
                try {
                    new JaxrsOpenApiContextBuilder<>()
                            .application(HMDMApplication.this)
                            .servletConfig(servletContainer.getServletConfig())
                            .openApiConfiguration(openApiConfiguration)
                            .buildContext(true);
                } catch (OpenApiConfigurationException e) {
                    throw new IllegalStateException("Unable to initialize the OpenAPI endpoint", e);
                }

            }

            public void onReload(Container container) {
            }

            public void onShutdown(Container container) {
            }
        });

        register(OpenApiResource.class);

    }

}
