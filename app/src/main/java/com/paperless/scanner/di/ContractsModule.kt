package com.paperless.scanner.di

import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.analytics.AnalyticsServiceContract
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import com.paperless.scanner.data.analytics.CrashlyticsHelperContract
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.datastore.TokenManagerContract
import com.paperless.scanner.data.health.ServerHealthMonitor
import com.paperless.scanner.data.health.ServerHealthMonitorContract
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.network.NetworkMonitorContract
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.DocumentRepositoryContract
import com.paperless.scanner.data.repository.SyncHistoryRepository
import com.paperless.scanner.data.repository.SyncHistoryRepositoryContract
import com.paperless.scanner.data.repository.TaskRepository
import com.paperless.scanner.data.repository.TaskRepositoryContract
import com.paperless.scanner.data.repository.TrashRepository
import com.paperless.scanner.data.repository.TrashRepositoryContract
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.data.repository.UploadQueueRepositoryContract
import com.paperless.scanner.data.sync.SyncManager
import com.paperless.scanner.data.sync.SyncManagerContract
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Exposes the #321 *Contract test-double seams. Delegating @Provides (rather than
 * @Binds) on purpose: it works identically whether the implementation is bound via an
 * @Inject constructor or an explicit @Provides in AppModule, so adding/removing a
 * provider there can never break these bindings. Each provider just returns the
 * existing singleton instance.
 */
@Module
@InstallIn(SingletonComponent::class)
object ContractsModule {

    @Provides
    @Singleton
    fun provideUploadQueueRepositoryContract(impl: UploadQueueRepository): UploadQueueRepositoryContract = impl

    @Provides
    @Singleton
    fun provideSyncHistoryRepositoryContract(impl: SyncHistoryRepository): SyncHistoryRepositoryContract = impl

    @Provides
    @Singleton
    fun provideDocumentRepositoryContract(impl: DocumentRepository): DocumentRepositoryContract = impl

    @Provides
    @Singleton
    fun provideTaskRepositoryContract(impl: TaskRepository): TaskRepositoryContract = impl

    @Provides
    @Singleton
    fun provideTrashRepositoryContract(impl: TrashRepository): TrashRepositoryContract = impl

    @Provides
    @Singleton
    fun provideSyncManagerContract(impl: SyncManager): SyncManagerContract = impl

    @Provides
    @Singleton
    fun provideNetworkMonitorContract(impl: NetworkMonitor): NetworkMonitorContract = impl

    @Provides
    @Singleton
    fun provideServerHealthMonitorContract(impl: ServerHealthMonitor): ServerHealthMonitorContract = impl

    @Provides
    @Singleton
    fun provideTokenManagerContract(impl: TokenManager): TokenManagerContract = impl

    @Provides
    @Singleton
    fun provideCrashlyticsHelperContract(impl: CrashlyticsHelper): CrashlyticsHelperContract = impl

    @Provides
    @Singleton
    fun provideAnalyticsServiceContract(impl: AnalyticsService): AnalyticsServiceContract = impl
}
