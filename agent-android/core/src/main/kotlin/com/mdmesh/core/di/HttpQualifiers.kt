package com.mdmesh.core.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApiHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadHttpClient
