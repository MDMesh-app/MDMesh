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
import javax.inject.Singleton

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
            .addInterceptor(BaseUrlInterceptor(serverConfig))
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    @DownloadHttpClient
    fun provideDownloadOkHttp(): OkHttpClient =
        OkHttpClient.Builder().build()

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
    fun provideMdmApi(retrofit: Retrofit): MdmApi =
        retrofit.create(MdmApi::class.java)
}
