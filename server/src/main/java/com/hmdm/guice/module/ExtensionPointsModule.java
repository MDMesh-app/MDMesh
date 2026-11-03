package com.hmdm.guice.module;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import com.hmdm.extension.ConfigurationExtensionValidator;
import com.hmdm.extension.DesiredConfigContributor;

/**
 * Declares the server's extension points, each a Guice set that server plugins add to from their own modules
 * ({@code Multibinder.newSetBinder(binder(), X.class).addBinding().to(...)}). Core adds nothing, so each set is empty
 * without plugins. Core reads them through ExtensionPoints, which leaves out the plugins turned off for a customer.
 * See web/EXTENDING.md ("Server plugin").
 */
public class ExtensionPointsModule extends AbstractModule {

    @Override
    protected void configure() {
        Multibinder.newSetBinder(binder(), ConfigurationExtensionValidator.class);
        Multibinder.newSetBinder(binder(), DesiredConfigContributor.class);
    }
}
