package com.hmdm.extension;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Checks a server plugin's data in a configuration's {@code extensions} before a save stores it. Bind one with Guice's
 * {@code Multibinder.newSetBinder(binder(), ConfigurationExtensionValidator.class).addBinding()}; core binds none.
 *
 * <p>A save that carries {@code extensions} calls it for each namespace its extension owns ({@code <id>} and
 * {@code <id>.<name>}, see {@link ConfigurationExtensions}) that the save changes, after core's own checks
 * (namespaces, JSON objects, sizes) passed: every one for a new configuration, and on an update those whose value
 * differs from what is stored, so data your plugin would now refuse never blocks a save that leaves it as it is. Any
 * problem refuses the whole save: the response's message is the first problem's key,
 * {@code error.extension.<namespace>.<code>}, and its data lists them all ({@link ExtensionError}). It runs only for
 * the saves of customers its plugin is on for: it has its {@code plugins} row, and the customer hasn't turned it off
 * ({@code pluginsDisabled}).</p>
 *
 * <p>The console sends every namespace it loaded back on a save, as a browser's JSON reads it, so keep values a
 * browser holds exactly: integers within &plusmn;2<sup>53</sup> (put a 64-bit id in a string). Numbers compare by
 * value, so a {@code 1.0} stored and sent back as {@code 1} is unchanged and not checked again. The same holds the
 * other way: an API client that sends {@code 1.0} where {@code 1} is stored is not checked, and {@code 1.0} is
 * written, so a check such as {@code isIntegralNumber()} must not be relied on for stored data.</p>
 */
public interface ConfigurationExtensionValidator {

    /**
     * The extension whose namespaces this checks: lowercase kebab-case, as the console extension's id. It must be your
     * plugin's id too (its PluginConfiguration's {@code getPluginId()} and its {@code plugins} row's identifier): the
     * server runs it only for the customers the plugin of that id is on for, so under any other id it runs for none
     * (the server warns at start). It is read once, at start.
     */
    String extensionId();

    /**
     * @param namespace the namespace, {@code extensionId()} or {@code extensionId() + "." + name}
     * @param value     its JSON object, as the save would store it. Read-only: it is a copy, so a change to it is
     *                  never stored (a validator checks the data, it doesn't normalize it)
     * @return the problems, empty (or null) when it is valid. Never throw for bad data: a throw (an Error or an
     *         undeclared checked exception included) refuses the save with {@code error.internal.server} (an
     *         {@code ERROR} envelope with HTTP status 200, not a 500), and the server logs it naming this extension.
     */
    List<Problem> validate(String namespace, JsonNode value);

    /** One reason to refuse the save. */
    final class Problem {
        private final String code;
        private final String message;

        /**
         * @param code    lowercase kebab-case, e.g. {@code port-range}: the key ends with it
         * @param message one sentence for people, e.g. "The VPN port must be 1 to 65535." Null or blank gives a
         *                default one: {@code The "<namespace>" settings were refused (<code>).}
         */
        public Problem(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }
}
