package com.mdmesh.core.di

import javax.inject.Qualifier

/**
 * The client for MDMesh server endpoints (Retrofit, the wake socket). It carries BaseUrlInterceptor,
 * which points every request at the provisioned server, so use it for nothing else.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApiHttpClient

/**
 * The client for APK downloads, which may be hosted anywhere. It must never carry BaseUrlInterceptor:
 * absolute URLs are fetched from the host they name.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadHttpClient
