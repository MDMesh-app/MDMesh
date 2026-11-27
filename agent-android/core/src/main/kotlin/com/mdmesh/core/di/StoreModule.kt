package com.mdmesh.core.di

import com.mdmesh.core.config.ServerAddress
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.core.kiosk.DataStoreGuardRecordStore
import com.mdmesh.core.kiosk.GuardRecordStore
import com.mdmesh.core.store.AgentDataStores
import com.mdmesh.core.store.DataStoreKioskStateStore
import com.mdmesh.core.store.DeviceIdStore
import com.mdmesh.core.store.DeviceIdentity
import com.mdmesh.core.store.EnrollTokenProvider
import com.mdmesh.core.store.EnrollTokenStore
import com.mdmesh.core.store.KioskStateStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The stores over [AgentDataStores], the one place their DataStore files are opened. */
@Module
@InstallIn(SingletonComponent::class)
object StoreModule {

    @Provides
    @Singleton
    fun provideDeviceIdStore(stores: AgentDataStores): DeviceIdStore = DeviceIdStore(stores.identity)

    /** Expose the store behind its interface so sync/enroll logic stays Android-free. */
    @Provides
    fun provideDeviceIdentity(store: DeviceIdStore): DeviceIdentity = store

    @Provides
    @Singleton
    fun provideEnrollTokenStore(stores: AgentDataStores): EnrollTokenStore = EnrollTokenStore(stores.enroll)

    @Provides
    fun provideEnrollTokenProvider(store: EnrollTokenStore): EnrollTokenProvider = store

    @Provides
    @Singleton
    fun provideKioskStateStore(stores: AgentDataStores): KioskStateStore = DataStoreKioskStateStore(stores.kiosk)

    /** The kiosk guard's record, on the mirrored store like the kiosk's own state. */
    @Provides
    @Singleton
    fun provideGuardRecordStore(stores: AgentDataStores): GuardRecordStore = DataStoreGuardRecordStore(stores.guard)

    @Provides
    fun provideServerAddress(store: ServerConfigStore): ServerAddress = store
}
