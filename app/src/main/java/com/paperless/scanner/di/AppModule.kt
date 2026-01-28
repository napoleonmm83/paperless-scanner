package com.paperless.scanner.di

import android.content.Context
import androidx.room.Room
import com.paperless.scanner.data.ai.paperlessgpt.PaperlessGptApi
import com.paperless.scanner.data.ai.paperlessgpt.PaperlessGptBaseUrlInterceptor
import com.paperless.scanner.data.ai.paperlessgpt.PaperlessGptRepository
import com.paperless.scanner.data.api.CloudflareDetectionInterceptor
import com.paperless.scanner.data.api.DynamicBaseUrlInterceptor
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.RetryInterceptor
import com.paperless.scanner.data.database.AppDatabase
import com.paperless.scanner.data.database.PendingUploadDao
import com.paperless.scanner.data.database.dao.AiUsageDao
import com.paperless.scanner.data.database.dao.CachedCorrespondentDao
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedDocumentTypeDao
import com.paperless.scanner.data.database.dao.CachedTagDao
import com.paperless.scanner.data.database.dao.CachedTaskDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.dao.SyncHistoryDao
import com.paperless.scanner.data.database.dao.SyncMetadataDao
import com.paperless.scanner.data.database.migrations.MIGRATION_1_2
import com.paperless.scanner.data.database.migrations.MIGRATION_2_3
import com.paperless.scanner.data.database.migrations.MIGRATION_3_4
import com.paperless.scanner.data.database.migrations.MIGRATION_4_5
import com.paperless.scanner.data.database.migrations.MIGRATION_5_6
import com.paperless.scanner.data.database.migrations.MIGRATION_6_7
import com.paperless.scanner.data.database.migrations.MIGRATION_7_8
import com.paperless.scanner.data.database.migrations.MIGRATION_8_9
import com.paperless.scanner.data.database.migrations.MIGRATION_9_10
import com.paperless.scanner.data.database.migrations.MIGRATION_10_11
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.network.AcceptedHostTrustManager
import com.paperless.scanner.data.network.AcceptedHostnameVerifier
import com.paperless.scanner.data.repository.AiUsageRepository
import com.paperless.scanner.data.repository.AuthRepository
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.BuildConfig
import com.paperless.scanner.data.repository.TaskRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.data.health.ServerHealthMonitor
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.sync.SyncManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PaperlessGptClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Provides
    @Singleton
    fun provideTokenManager(
        @ApplicationContext context: Context
    ): TokenManager = TokenManager(context)

    @Provides
    @Singleton
    @AuthClient
    fun provideAuthOkHttpClient(tokenManager: TokenManager): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        // Get default TrustManager
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(null as java.security.KeyStore?)
        val trustManagers = trustManagerFactory.trustManagers
        val defaultTrustManager = trustManagers.first { it is X509TrustManager } as X509TrustManager

        // Create custom TrustManager that checks accepted hosts
        val acceptedHostTrustManager = AcceptedHostTrustManager(tokenManager, defaultTrustManager)

        // Create SSL context with custom TrustManager
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(acceptedHostTrustManager), SecureRandom())

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .sslSocketFactory(sslContext.socketFactory, acceptedHostTrustManager)
            .hostnameVerifier(AcceptedHostnameVerifier(tokenManager))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideDynamicBaseUrlInterceptor(
        tokenManager: TokenManager
    ): DynamicBaseUrlInterceptor = DynamicBaseUrlInterceptor(tokenManager)

    @Provides
    @Singleton
    fun provideCloudflareDetectionInterceptor(
        tokenManager: TokenManager,
        @ApplicationScope applicationScope: CoroutineScope
    ): CloudflareDetectionInterceptor = CloudflareDetectionInterceptor(tokenManager, applicationScope)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        tokenManager: TokenManager,
        dynamicBaseUrlInterceptor: DynamicBaseUrlInterceptor,
        cloudflareDetectionInterceptor: CloudflareDetectionInterceptor
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor(dynamicBaseUrlInterceptor)
            .addInterceptor { chain ->
                // Token interceptor - runs on OkHttp thread pool, not main thread
                val token = tokenManager.getTokenSync()
                val request = if (token != null) {
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Token $token")
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .addInterceptor(cloudflareDetectionInterceptor)  // Detect Cloudflare usage via cf-ray header
            .addInterceptor(RetryInterceptor(maxRetries = 3))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun providePaperlessApi(
        okHttpClient: OkHttpClient
    ): PaperlessApi {
        // Use placeholder URL - DynamicBaseUrlInterceptor will set the actual URL
        return Retrofit.Builder()
            .baseUrl("http://placeholder.local/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PaperlessApi::class.java)
    }

    // Paperless-GPT API

    @Provides
    @Singleton
    fun providePaperlessGptBaseUrlInterceptor(
        tokenManager: TokenManager
    ): PaperlessGptBaseUrlInterceptor = PaperlessGptBaseUrlInterceptor(tokenManager)

    @Provides
    @Singleton
    @PaperlessGptClient
    fun providePaperlessGptOkHttpClient(
        tokenManager: TokenManager,
        paperlessGptBaseUrlInterceptor: PaperlessGptBaseUrlInterceptor
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor(paperlessGptBaseUrlInterceptor)
            .addInterceptor { chain ->
                // Token interceptor - uses same token as Paperless-ngx
                val token = tokenManager.getTokenSync()
                val request = if (token != null) {
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Token $token")
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .addInterceptor(RetryInterceptor(maxRetries = 3))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun providePaperlessGptApi(
        @PaperlessGptClient okHttpClient: OkHttpClient
    ): PaperlessGptApi {
        // Use placeholder URL - PaperlessGptBaseUrlInterceptor will set the actual URL
        return Retrofit.Builder()
            .baseUrl("http://placeholder.local/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PaperlessGptApi::class.java)
    }

    @Provides
    @Singleton
    fun provideAuthRepository(
        tokenManager: TokenManager,
        @AuthClient client: OkHttpClient,
        cloudflareDetectionInterceptor: CloudflareDetectionInterceptor
    ): AuthRepository = AuthRepository(tokenManager, client, cloudflareDetectionInterceptor)

    @Provides
    @Singleton
    fun provideTagRepository(
        api: PaperlessApi,
        cachedTagDao: CachedTagDao,
        cachedDocumentDao: CachedDocumentDao,
        pendingChangeDao: PendingChangeDao,
        networkMonitor: NetworkMonitor
    ): TagRepository = TagRepository(api, cachedTagDao, cachedDocumentDao, pendingChangeDao, networkMonitor)

    @Provides
    @Singleton
    fun provideDocumentRepository(
        @ApplicationContext context: Context,
        api: PaperlessApi,
        cachedDocumentDao: CachedDocumentDao,
        cachedTagDao: CachedTagDao,
        cachedTaskDao: CachedTaskDao,
        pendingChangeDao: PendingChangeDao,
        networkMonitor: NetworkMonitor,
        serverHealthMonitor: ServerHealthMonitor
    ): DocumentRepository = DocumentRepository(context, api, cachedDocumentDao, cachedTagDao, cachedTaskDao, pendingChangeDao, networkMonitor, serverHealthMonitor)

    @Provides
    @Singleton
    fun provideDocumentTypeRepository(
        api: PaperlessApi,
        cachedDocumentTypeDao: CachedDocumentTypeDao,
        pendingChangeDao: PendingChangeDao,
        networkMonitor: NetworkMonitor
    ): DocumentTypeRepository = DocumentTypeRepository(api, cachedDocumentTypeDao, pendingChangeDao, networkMonitor)

    @Provides
    @Singleton
    fun provideCorrespondentRepository(
        api: PaperlessApi,
        cachedCorrespondentDao: CachedCorrespondentDao,
        pendingChangeDao: PendingChangeDao,
        networkMonitor: NetworkMonitor
    ): CorrespondentRepository = CorrespondentRepository(api, cachedCorrespondentDao, pendingChangeDao, networkMonitor)

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        val builder = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,  // Trash feature: Added deletedAt timestamp
            MIGRATION_10_11  // SyncCenter feature: Added sync_history table
        )

        // For debug builds, allow destructive migration if migration fails
        if (BuildConfig.DEBUG) {
            builder.fallbackToDestructiveMigration()
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun providePendingUploadDao(
        database: AppDatabase
    ): PendingUploadDao = database.pendingUploadDao()

    @Provides
    @Singleton
    fun provideCachedDocumentDao(
        database: AppDatabase
    ): CachedDocumentDao = database.cachedDocumentDao()

    @Provides
    @Singleton
    fun provideCachedTagDao(
        database: AppDatabase
    ): CachedTagDao = database.cachedTagDao()

    @Provides
    @Singleton
    fun provideCachedCorrespondentDao(
        database: AppDatabase
    ): CachedCorrespondentDao = database.cachedCorrespondentDao()

    @Provides
    @Singleton
    fun provideCachedDocumentTypeDao(
        database: AppDatabase
    ): CachedDocumentTypeDao = database.cachedDocumentTypeDao()

    @Provides
    @Singleton
    fun provideCachedTaskDao(
        database: AppDatabase
    ): CachedTaskDao = database.cachedTaskDao()

    @Provides
    @Singleton
    fun providePendingChangeDao(
        database: AppDatabase
    ): PendingChangeDao = database.pendingChangeDao()

    @Provides
    @Singleton
    fun provideSyncMetadataDao(
        database: AppDatabase
    ): SyncMetadataDao = database.syncMetadataDao()

    @Provides
    @Singleton
    fun provideAiUsageDao(
        database: AppDatabase
    ): AiUsageDao = database.aiUsageDao()

    @Provides
    @Singleton
    fun provideSyncHistoryDao(
        database: AppDatabase
    ): SyncHistoryDao = database.syncHistoryDao()

    @Provides
    @Singleton
    fun provideAiUsageRepository(
        aiUsageDao: AiUsageDao
    ): AiUsageRepository = AiUsageRepository(aiUsageDao)

    @Provides
    @Singleton
    fun provideUploadQueueRepository(
        dao: PendingUploadDao
    ): UploadQueueRepository = UploadQueueRepository(dao)

    @Provides
    @Singleton
    fun provideTaskRepository(
        api: PaperlessApi,
        cachedTaskDao: CachedTaskDao,
        networkMonitor: NetworkMonitor
    ): TaskRepository = TaskRepository(api, cachedTaskDao, networkMonitor)

    @Provides
    @Singleton
    fun providePaperlessGptRepository(
        api: PaperlessGptApi,
        tokenManager: TokenManager
    ): PaperlessGptRepository = PaperlessGptRepository(api, tokenManager)

    @Provides
    @Singleton
    fun provideSyncManager(
        api: PaperlessApi,
        cachedDocumentDao: CachedDocumentDao,
        cachedTagDao: CachedTagDao,
        cachedCorrespondentDao: CachedCorrespondentDao,
        cachedDocumentTypeDao: CachedDocumentTypeDao,
        pendingChangeDao: PendingChangeDao,
        syncMetadataDao: SyncMetadataDao
    ): SyncManager = SyncManager(
        api,
        cachedDocumentDao,
        cachedTagDao,
        cachedCorrespondentDao,
        cachedDocumentTypeDao,
        pendingChangeDao,
        syncMetadataDao
    )

    @Provides
    @Singleton
    fun provideNetworkMonitor(
        @ApplicationContext context: Context,
        syncManager: SyncManager
    ): NetworkMonitor = NetworkMonitor(context, syncManager)

    @Provides
    @Singleton
    fun provideServerHealthMonitor(
        api: PaperlessApi,
        tokenManager: TokenManager,
        networkMonitor: NetworkMonitor
    ): ServerHealthMonitor = ServerHealthMonitor(api, tokenManager, networkMonitor)

    @Provides
    fun provideIODispatcher(): CoroutineDispatcher = Dispatchers.IO

    // Premium/Billing

    @Provides
    @Singleton
    fun provideBillingManager(
        @ApplicationContext context: Context
    ): com.paperless.scanner.data.billing.BillingManager =
        com.paperless.scanner.data.billing.BillingManager(context)

    @Provides
    @Singleton
    fun providePremiumFeatureManager(
        billingManager: com.paperless.scanner.data.billing.BillingManager,
        tokenManager: TokenManager
    ): com.paperless.scanner.data.billing.PremiumFeatureManager =
        com.paperless.scanner.data.billing.PremiumFeatureManager(billingManager, tokenManager)
}
