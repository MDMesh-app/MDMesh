package com.mdmesh.core.kiosk

import android.content.Context
import android.graphics.BitmapFactory
import com.mdmesh.core.di.DownloadHttpClient
import com.mdmesh.proto.KioskBackgroundImageDto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Retrieves a server-managed kiosk image into private app storage. A failed validation is deliberately
 * indistinguishable from no image to callers: the launcher must keep its configured color usable.
 */
@Singleton
class KioskBackgroundImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @DownloadHttpClient private val http: OkHttpClient,
) {
    suspend fun obtain(image: KioskBackgroundImageDto): File? = withContext(Dispatchers.IO) {
        val url = image.url.trim()
        val hash = image.sha256.trim().lowercase()
        if (!url.startsWith("https://", ignoreCase = true) || !HASH.matches(hash)) return@withContext null

        val dir = File(context.filesDir, "kiosk-background").also { it.mkdirs() }
        val target = File(dir, "$hash.image")
        if (validImage(target)) return@withContext target
        target.delete()

        val temporary = File(dir, ".$hash.download")
        temporary.delete()
        try {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_BYTES) return@withContext null
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (digest.digest().joinToString("") { "%02x".format(it) } != hash || !validImage(temporary)) {
                    return@withContext null
                }
                if (!temporary.renameTo(target)) return@withContext null
                target
            }
        } catch (_: Exception) {
            null
        } finally {
            temporary.delete()
        }
    }

    private fun validImage(file: File): Boolean {
        if (!file.isFile || file.length() !in 1..MAX_BYTES) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return options.outWidth > 0 && options.outHeight > 0 &&
            options.outWidth.toLong() * options.outHeight.toLong() <= MAX_PIXELS
    }

    private companion object {
        val HASH = Regex("[0-9a-f]{64}")
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_BYTES = 10L * 1024 * 1024
        const val MAX_PIXELS = 16L * 1024 * 1024
    }
}
