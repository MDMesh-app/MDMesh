package com.mdmesh.core.config

import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.policy.AppRestrictionOutcome
import com.mdmesh.policy.AppRestrictions
import com.mdmesh.policy.RestrictionValue
import com.mdmesh.proto.ConfigAppRestrictions
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.ConfigRestrictionValue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The `appRestrictions` section of config.apply (1.8), one outcome per package (`appRestrictions.<package>`), for
 * [ConfigApplier]:
 * - absent: nothing changes (an older server), and [ConfigStateStore.activeAppRestrictions] is kept;
 * - a listed package gets exactly its values as its whole bundle, or `unsupported` with nothing set when a value has
 *   a type this agent doesn't know or doesn't match its type (a partial bundle is never set);
 * - a package set earlier (the durable record, the previous documents, a failed clear) and not listed is cleared.
 *
 * Values can be secrets: none goes to a log line or an outcome.
 */
internal class AppRestrictionsSection(
    private val restrictions: AppRestrictions,
    private val store: ConfigStateStore,
) {
    /**
     * @param previous the documents whose packages this one may stop listing.
     * @param unreleased outcome keys whose clear failed before: cleared again unless listed.
     */
    fun apply(
        doc: ConfigApplyPayload,
        previous: List<ConfigApplyPayload>,
        unreleased: Set<String>,
        outcomes: MutableMap<String, String>,
    ) {
        val desired = doc.appRestrictions ?: return
        desired.forEach { entry -> outcomes[key(entry.packageName)] = set(entry) }
        val listed = desired.map { it.packageName }.toSet()
        val prior = store.activeAppRestrictions +
            previous.flatMap { it.appRestrictions.orEmpty() }.map { it.packageName } +
            unreleased.filter(::isKey).map { it.removePrefix(PREFIX) }
        val failedClears = (prior - listed).filter { packageName ->
            val outcome = outcome(restrictions.clear(packageName))
            ConfigOutcome.isFailed(outcome).also { failed -> if (failed) outcomes[key(packageName)] = outcome }
        }
        // What may still hold a bundle MDMesh set: a listed package (even a failed or unsupported one) or a failed
        // clear. A later document clears what it leaves out, also after older-server documents in between.
        store.activeAppRestrictions = listed + failedClears
    }

    fun lists(doc: ConfigApplyPayload, packageName: String): Boolean =
        doc.appRestrictions.orEmpty().any { it.packageName == packageName }

    /** Sets [packageName]'s bundle from [doc] again (after its install); empty when [doc] doesn't list it. */
    fun applyPackage(doc: ConfigApplyPayload, packageName: String): Map<String, String> =
        doc.appRestrictions.orEmpty().filter { it.packageName == packageName }
            .associate { key(it.packageName) to set(it) }

    fun manages(doc: ConfigApplyPayload, outcomeKey: String): Boolean =
        doc.appRestrictions.orEmpty().any { key(it.packageName) == outcomeKey }

    private fun set(entry: ConfigAppRestrictions): String {
        val values = entry.values.mapValues { (_, value) -> typed(value) ?: return ConfigOutcome.UNSUPPORTED }
        return outcome(restrictions.set(entry.packageName, values))
    }

    private fun outcome(outcome: AppRestrictionOutcome): String = when (outcome) {
        AppRestrictionOutcome.Applied -> ConfigOutcome.APPLIED
        AppRestrictionOutcome.Unsupported -> ConfigOutcome.UNSUPPORTED
        AppRestrictionOutcome.NotInstalled -> ConfigOutcome.failed("app not installed")
        is AppRestrictionOutcome.Failed -> ConfigOutcome.failed(outcome.reason)
    }

    companion object {
        private const val PREFIX = "appRestrictions."

        fun isKey(outcomeKey: String): Boolean = outcomeKey.startsWith(PREFIX)

        private fun key(packageName: String): String = "$PREFIX$packageName"

        /** Null for a type this agent doesn't know, or a value that isn't the JSON value of its type. */
        private fun typed(v: ConfigRestrictionValue): RestrictionValue? {
            val p = v.value as? JsonPrimitive ?: return null
            return when (v.type) {
                ConfigRestrictionValue.BOOL -> p.takeUnless { it.isString }?.booleanOrNull?.let(RestrictionValue::Bool)
                ConfigRestrictionValue.STRING -> p.takeIf { it.isString }?.content?.let(RestrictionValue::Str)
                ConfigRestrictionValue.INT -> p.takeUnless { it.isString }?.intOrNull?.let(RestrictionValue::Int32)
                else -> null
            }
        }
    }
}
