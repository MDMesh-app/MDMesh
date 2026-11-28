package com.mdmesh.agent.localapi

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.mdmesh.core.localapi.LocalApi
import com.mdmesh.core.localapi.LocalApiArgs
import com.mdmesh.core.localapi.LocalApiReply
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

/**
 * The [LocalApi] as an exported content provider (`content://<applicationId>.localapi`), reached with
 * `ContentResolver.call`. Only [call] does anything; there is no data to query.
 *
 * Exported with no permission on purpose: [LocalApi] authorizes every call in code, from the calling package the
 * platform verified against the calling UID. Nothing is logged but the method and the reply code.
 */
class LocalApiProvider : ContentProvider() {

    /** Providers are created before the application is injected, so the graph is reached through this, per call. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface LocalApiEntryPoint {
        fun localApi(): LocalApi
    }

    override fun onCreate(): Boolean = true

    /** Runs on a binder thread, never the main one, so blocking on the kiosk-state read is fine here. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // First, on this binder thread: the platform checks the name against the calling UID (and throws on a lie).
        val caller = runCatching { callingPackage }.getOrNull()
        val args = runCatching { argsOf(extras) }.getOrNull()
        val reply = if (args == null) {
            LocalApiReply.badRequest("the extras could not be read")
        } else {
            runCatching { runBlocking { localApi().call(method, caller, args) } }
                .getOrElse { LocalApiReply.error("${LocalApi.logName(method)} failed: ${it.javaClass.simpleName}") }
        }
        Log.i(TAG, "${LocalApi.logName(method)} ${reply.code}")
        return bundleOf(reply)
    }

    private fun localApi(): LocalApi =
        EntryPointAccessors.fromApplication(checkNotNull(context), LocalApiEntryPoint::class.java).localApi()

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val TAG = "LocalApi"

        /** The untyped [Bundle.get]: a typed getter cannot tell a missing or mistyped value from its default. */
        @Suppress("DEPRECATION")
        fun argsOf(extras: Bundle?): LocalApiArgs =
            LocalApiArgs(extras?.keySet().orEmpty().associateWith { extras?.get(it) })

        fun bundleOf(reply: LocalApiReply): Bundle = Bundle().apply {
            reply.fields().forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is String -> putString(key, value)
                    is List<*> -> putStringArrayList(key, ArrayList(value.map { it.toString() }))
                }
            }
        }
    }
}
