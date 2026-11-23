package com.mdmesh.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** The dispatcher for disk, network and other blocking work. Injected so tests can run it on virtual time. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * The agent's own long-lived scope, for work a component starts but must not own the lifetime of (a broadcast
 * receiver is gone once it returns). A SupervisorJob, so one failure doesn't cancel the rest.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AgentScope

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @AgentScope
    fun provideAgentScope(@IoDispatcher io: CoroutineDispatcher): CoroutineScope = CoroutineScope(SupervisorJob() + io)
}
