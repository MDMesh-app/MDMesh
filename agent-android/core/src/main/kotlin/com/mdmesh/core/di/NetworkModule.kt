package com.mdmesh.core.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.mdmesh.core.BuildConfig
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.core.net.BaseUrlInterceptor
import com.mdmesh.core.net.MdmApi
import com.mdmesh.proto.ProtocolJson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Provides the networking stack: OkHttp + Retrofit wired to the shared
 * [ProtocolJson] instance via the kotlinx-serialization converter. Base URL comes
 * from [BuildConfig.MDM_BASE_URL] so it varies per build type without code change.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val jsonMediaType = "application/json".toMediaType()

    @Provides
    @Singleton
    @ApiHttpClient
    fun provideApiOkHttp(serverConfig: ServerConfigStore): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            // Resolve the real server (provisioned at enrollment) per-request, so the Retrofit base
            // below is only a placeholder and one APK serves every deployment.
            .addInterceptor(BaseUrlInterceptor(serverConfig))
            .addInterceptor(logging)
            // OkHttp's default 10 s gave up on a busy server's answer, and with it on the commands in it.
            .readTimeout(API_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    // APK downloads only. Never add BaseUrlInterceptor here: it rewrites every request's host, so an
    // APK hosted elsewhere (a vendor CDN, a presigned URL) would be fetched from our server instead.
    // No logging interceptor either: at BODY level it would buffer whole APKs in memory.
    //
    // Timeouts are for large bodies on slow, shared Wi-Fi (#174). OkHttp's default 10 s read timeout failed a 125 MB
    // APK that was still arriving whenever the link went quiet for a moment. The read timeout is per read: a minute
    // of silence fails a stalled download, however long a moving one takes. No call timeout, since any total limit
    // also fails a large APK on a slow but healthy link; a caller with a size cap adds its own (the kiosk background).
    @Provides
    @Singleton
    @DownloadHttpClient
    fun provideDownloadOkHttp(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(DOWNLOAD_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(DOWNLOAD_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()

    private const val API_READ_TIMEOUT_S = 30L
    private const val DOWNLOAD_CONNECT_TIMEOUT_S = 30L
    private const val DOWNLOAD_READ_TIMEOUT_S = 60L

    @Provides
    @Singleton
    fun provideRetrofit(
        @ApiHttpClient client: OkHttpClient
    ): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.MDM_BASE_URL)
        .client(client)
        .addConverterFactory(ProtocolJson.json.asConverterFactory(jsonMediaType))
        .build()

    @Provides
    @Singleton
    fun provideMdmApi(retrofit: Retrofit): MdmApi = retrofit.create(MdmApi::class.java)
}
