package com.mdmesh.core.di

import android.content.Context
import com.mdmesh.core.store.DeviceIdStore
import com.mdmesh.core.store.DeviceIdentity
import com.mdmesh.core.store.EnrollTokenProvider
import com.mdmesh.core.store.EnrollTokenStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StoreModule {

    @Provides
    @Singleton
    fun provideDeviceIdStore(
        @ApplicationContext context: Context,
    ): DeviceIdStore = DeviceIdStore(context)

    /** Expose the store behind its interface so sync/enroll logic stays Android-free. */
    @Provides
    fun provideDeviceIdentity(store: DeviceIdStore): DeviceIdentity = store

    @Provides
    @Singleton
    fun provideEnrollTokenStore(
        @ApplicationContext context: Context,
    ): EnrollTokenStore = EnrollTokenStore(context)

    @Provides
    fun provideEnrollTokenProvider(store: EnrollTokenStore): EnrollTokenProvider = store
}
