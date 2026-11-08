package com.hmdm.extension;

import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Adds a server plugin's policy keys to the {@code config.apply} document a configuration becomes
 * ({@link com.hmdm.util.DesiredConfigBuilder}). Bind one with Guice's
 * {@code Multibinder.newSetBinder(binder(), DesiredConfigContributor.class).addBinding()}; core binds none.
 *
 * <p>Each key it returns is sent as {@code policies.x.<pluginId>.<key>}, so a plugin can only add keys under its own
 * prefix and never touch core's. An agent without a toggle for the key reports it {@code unsupported} and applies the
 * rest of the document; a fork that wants the policy enforced adds that toggle to its agent.</p>
 *
 * <p>It runs only for the configurations of customers its plugin is on for: it has its {@code plugins} row, and the
 * customer hasn't turned it off ({@code pluginsDisabled}). Turning the plugin off for a customer therefore removes its
 * keys from those documents, and the agent releases them as for any key a document stops sending.</p>
 *
 * <p>The keys are part of the document's revision. A null value, or no key at all, leaves the document as it would be
 * without this plugin, so installing it re-applies only the configurations it has something to say about. It runs on
 * every check-in of a capable device: keep it pure and fast (no I/O beyond what the configuration already carries,
 * such as its {@code extensions}), and read {@code extensions} defensively: data stored before the plugin was installed,
 * or by a copy, was never checked by its validator. A throw (an Error included, or a failure while the returned map
 * is read), or a key that breaks the rules below, stops config.apply for the devices on that configuration until it is
 * fixed, rather than sending a document without the key; the server logs it, and the console shows those devices out
 * of sync. A pluginId that breaks the rules fails the server's start.</p>
 *
 * <p>The values are Boolean only, because {@code policies} is a map of booleans on the wire: the agent decodes the
 * document strictly, so one value of another type fails the whole document (every core policy and the kiosk with it),
 * not just the key. A value that isn't a Boolean (a raw-typed map can carry one) is therefore treated as a failure of
 * this contributor and never sent. Settings that aren't booleans (a hostname, a VPN profile) will come later as a
 * new top-level section of the document, through a new default method on this interface, so implementations of this
 * one keep working unchanged (proto/registry.md).</p>
 *
 * <p>The configuration is loaded for this document alone, and the apps are a read-only list: treat both as read-only.
 * Core's sections are built before contributors run, so changing them changes nothing that is sent.</p>
 */
public interface DesiredConfigContributor {

    /**
     * Lowercase kebab-case, as an extension id: the keys go under {@code x.<pluginId>.}. It must be your plugin's id
     * (its PluginConfiguration's {@code getPluginId()} and its {@code plugins} row's identifier): the server runs it
     * only for the customers the plugin of that id is on for, so under any other id it runs for none (the server warns
     * at start). It is read once, at start.
     */
    String pluginId();

    /**
     * @param configuration the configuration, {@code extensions} included
     * @param applications  its apps, as the core document reads them
     * @return keys (letters and digits, starting with a lowercase letter, at most 64 characters; no dots) and their
     *         values, true = allowed/enabled as for core's policies. Null or empty for none.
     */
    Map<String, Boolean> policies(Configuration configuration, List<Application> applications);
}
