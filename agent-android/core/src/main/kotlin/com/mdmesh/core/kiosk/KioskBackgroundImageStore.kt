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
        val request = validRequest(image) ?: return@withContext null

        val dir = File(context.filesDir, "kiosk-background").also { it.mkdirs() }
        val target = File(dir, "${request.hash}.image")
        if (validImage(target)) return@withContext target
        target.delete()
        download(request, target)
    }

    private fun validRequest(image: KioskBackgroundImageDto): BackgroundRequest? {
        val url = image.url.trim()
        val hash = image.sha256.trim().lowercase()
        return if (url.startsWith("https://", ignoreCase = true) && HASH.matches(hash)) {
            BackgroundRequest(url, hash)
        } else {
            null
        }
    }

    private fun download(request: BackgroundRequest, target: File): File? {
        val temporary = File(target.parentFile, ".${request.hash}.download")
        temporary.delete()
        return try {
            val digest = http.newCall(Request.Builder().url(request.url).get().build()).execute().use { response ->
                response.body?.takeIf { response.isSuccessful }?.let { body ->
                    copyAndDigest(body.byteStream(), temporary)
                }
            }
            if (digest == request.hash && validImage(temporary) && temporary.renameTo(target)) target else null
        } catch (_: Exception) {
            null
        } finally {
            temporary.delete()
        }
    }

    private fun copyAndDigest(input: java.io.InputStream, outputFile: File): String? = input.use { source ->
        FileOutputStream(outputFile).use { output ->
            val digest = MessageDigest.getInstance("SHA-256")
            if (copyBounded(source, output, digest)) digest.digest().joinToString("") { "%02x".format(it) } else null
        }
    }

    private fun copyBounded(input: java.io.InputStream, output: FileOutputStream, digest: MessageDigest): Boolean {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) return false
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
        return true
    }

    private fun validImage(file: File): Boolean {
        if (!file.isFile || file.length() !in 1..MAX_BYTES) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return options.outWidth > 0 && options.outHeight > 0 &&
            options.outWidth.toLong() * options.outHeight.toLong() <= MAX_PIXELS
    }

    private companion object {
        data class BackgroundRequest(val url: String, val hash: String)
        val HASH = Regex("[0-9a-f]{64}")
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_BYTES = 10L * 1024 * 1024
        const val MAX_PIXELS = 16L * 1024 * 1024
    }
}
