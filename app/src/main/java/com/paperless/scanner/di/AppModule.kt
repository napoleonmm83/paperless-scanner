package com.paperless.scanner.di

import android.content.Context
import androidx.room.Room
import com.paperless.scanner.data.ai.paperlessgpt.PaperlessGptApi
import com.paperless.scanner.data.ai.paperlessgpt.PaperlessGptBaseUrlInterceptor
import com.paperless.scanner.data.ai.paperlessgpt.PaperlessGptRepository
import com.paperless.scanner.data.api.AdaptiveWriteTimeoutInterceptor
import com.paperless.scanner.data.api.CacheControlInterceptor
import com.paperless.scanner.data.api.CloudflareDetectionInterceptor
import com.paperless.scanner.data.datastore.CloudflareDetectionHolder
import com.paperless.scanner.data.api.DynamicBaseUrlInterceptor
import com.paperless.scanner.data.api.HttpAllowlistInterceptor
import com.paperless.scanner.data.api.PaperlessApi
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
import com.paperless.scanner.data.database.migrations.MIGRATION_11_12
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.network.AcceptedHostTrustManager
import com.paperless.scanner.data.network.AcceptedHostnameVerifier
import com.paperless.scanner.data.network.CertPinStorage
import com.paperless.scanner.data.network.CertificatePinningInterceptor
import com.paperless.scanner.data.network.EncryptedCertPinStorage
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
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.analytics.AuthDebugService
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import com.paperless.scanner.data.analytics.CrashlyticsHelperContract
import com.paperless.scanner.data.analytics.UploadMetricsTracker
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.service.DocumentSerializer
import com.paperless.scanner.data.service.ImageProcessorService
import com.paperless.scanner.data.service.PdfGeneratorService
import com.paperless.scanner.data.service.ProtocolDetector
import com.paperless.scanner.data.sync.SyncManager
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.Module
import dagger.Provides
import javax.inject.Named
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import com.google.gson.Gson
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
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

    // 50 MB disk cache for the Paperless-ngx default OkHttpClient (Issue #131).
    private const val HTTP_CACHE_SIZE_BYTES: Long = 50L * 1024 * 1024

    // ============================================================
    // Helper Functions
    // ============================================================

    /**
     * Creates a configured HttpLoggingInterceptor.
     * - DEBUG builds: Log headers only (not body to avoid memory issues)
     * - RELEASE builds: No logging
     */
    private fun createLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }

    /**
     * Applies standard timeout configuration to an OkHttpClient.Builder.
     * Uses centralized values from NetworkConfig.
     *
     * @param includeWriteTimeout Whether to include write timeout (used for upload clients)
     */
    private fun OkHttpClient.Builder.applyTimeouts(
        includeWriteTimeout: Boolean = true
    ): OkHttpClient.Builder {
        connectTimeout(com.paperless.scanner.util.NetworkConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        readTimeout(com.paperless.scanner.util.NetworkConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (includeWriteTimeout) {
            writeTimeout(com.paperless.scanner.util.NetworkConfig.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        return this
    }

    // ============================================================
    // Core Providers
    // ============================================================

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonProvider.instance

    @Provides
    @Singleton
    fun provideTokenManager(
        @ApplicationContext context: Context
    ): TokenManager = TokenManager(context)

    @Provides
    @Singleton
    fun provideLoginRateLimiter(
        @ApplicationContext context: Context
    ): com.paperless.scanner.util.LoginRateLimiter =
        com.paperless.scanner.util.LoginRateLimiter(context)

    /**
     * OkHttpClient for authentication and server discovery.
     * - Custom SSL/TrustManager for self-signed certificates
     * - No write timeout (read-only operations)
     * - No retry interceptor (discovery should fail fast)
     */
    @Provides
    @Singleton
    @AuthClient
    fun provideAuthOkHttpClient(
        tokenManager: TokenManager,
        httpAllowlistInterceptor: HttpAllowlistInterceptor,
        certificatePinningInterceptor: CertificatePinningInterceptor,
    ): OkHttpClient {
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
            // Allowlist runs first; ordering contract pinned by AppModuleInterceptorOrderTest (#221).
            .addInterceptor(httpAllowlistInterceptor)
            .addInterceptor(createLoggingInterceptor())
            // TOFU pin enforcement runs as a network interceptor so it sees the
            // TLS handshake and aborts before the request is written (Issue #36).
            .addNetworkInterceptor(certificatePinningInterceptor)
            .sslSocketFactory(sslContext.socketFactory, acceptedHostTrustManager)
            .hostnameVerifier(AcceptedHostnameVerifier(tokenManager))
            .applyTimeouts(includeWriteTimeout = false)
            .build()
    }

    @Provides
    @Singleton
    fun provideDynamicBaseUrlInterceptor(
        serverUrlHolder: com.paperless.scanner.data.datastore.ServerUrlHolder
    ): DynamicBaseUrlInterceptor = DynamicBaseUrlInterceptor(serverUrlHolder)

    @Provides
    @Singleton
    fun provideCloudflareDetectionInterceptor(
        tokenManager: TokenManager,
        serverUrlHolder: com.paperless.scanner.data.datastore.ServerUrlHolder,
        @ApplicationScope applicationScope: CoroutineScope
    ): CloudflareDetectionInterceptor = CloudflareDetectionInterceptor(tokenManager, serverUrlHolder, applicationScope)

    @Provides
    @Singleton
    fun provideAdaptiveWriteTimeoutInterceptor(
        cloudflareDetectionHolder: CloudflareDetectionHolder
    ): AdaptiveWriteTimeoutInterceptor = AdaptiveWriteTimeoutInterceptor(cloudflareDetectionHolder)

    @Provides
    @Singleton
    fun provideCacheControlInterceptor(): CacheControlInterceptor = CacheControlInterceptor()

    /**
     * Binds the encrypted persistence for TOFU certificate pins (Issue #36).
     * [CertificatePinStore] and [CertificatePinningInterceptor] are resolved via
     * their `@Inject` constructors; only the interface needs an explicit binding.
     */
    @Provides
    @Singleton
    fun provideCertPinStorage(impl: EncryptedCertPinStorage): CertPinStorage = impl

    /**
     * Disk-backed HTTP cache for the Paperless-ngx default client. 50 MB at
     * `<cacheDir>/http`. Paired with [CacheControlInterceptor], which injects
     * `Cache-Control: public, max-age=300` on successful GETs lacking server
     * hints. Cleared on logout via [com.paperless.scanner.data.repository.AuthRepository.logout].
     *
     * Scoped to the Paperless-ngx client only — auth/discovery is fail-fast,
     * Paperless-GPT responses are unique, and Coil owns its own image cache.
     */
    @Provides
    @Singleton
    fun provideHttpCache(@ApplicationContext context: Context): Cache {
        val cacheDir = File(context.cacheDir, "http")
        return Cache(cacheDir, HTTP_CACHE_SIZE_BYTES)
    }

    /**
     * Default OkHttpClient for Paperless-ngx API.
     * - Dynamic base URL from TokenManager
     * - Auto-injected auth token
     * - Cloudflare detection
     * - Disk cache (50 MB) for safe idempotent GETs (Issue #131)
     *
     * Retry now lives at the suspend boundary in repositories via
     * [com.paperless.scanner.util.withRetry]; uploads rely on
     * [androidx.work.WorkManager] backoff (see UploadWorker). Removing the
     * blocking interceptor means concurrent requests no longer pile up while
     * one connection is sleeping out a backoff window.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(
        tokenManager: TokenManager,
        dynamicBaseUrlInterceptor: DynamicBaseUrlInterceptor,
        httpAllowlistInterceptor: HttpAllowlistInterceptor,
        cloudflareDetectionInterceptor: CloudflareDetectionInterceptor,
        adaptiveWriteTimeoutInterceptor: AdaptiveWriteTimeoutInterceptor,
        cacheControlInterceptor: CacheControlInterceptor,
        certificatePinningInterceptor: CertificatePinningInterceptor,
        cache: Cache,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .cache(cache)
            .addInterceptor(createLoggingInterceptor())
            .addInterceptor(dynamicBaseUrlInterceptor)
            // Allowlist must run AFTER URL rewrite (sees real host) and BEFORE
            // the token interceptor (no auth-token leak to non-allowlisted hosts).
            // This ordering contract is pinned by AppModuleInterceptorOrderTest (#221).
            .addInterceptor(httpAllowlistInterceptor)
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
            .addInterceptor(cloudflareDetectionInterceptor)
            .addInterceptor(adaptiveWriteTimeoutInterceptor)
            // TOFU pin enforcement first among network interceptors: it aborts on a
            // changed cert before the request (with the auth token) is written and
            // before the cache layer runs (Issue #36).
            .addNetworkInterceptor(certificatePinningInterceptor)
            // Network interceptor so the cache layer sees the injected
            // Cache-Control header when writing the response to disk.
            .addNetworkInterceptor(cacheControlInterceptor)
            .applyTimeouts()
            .build()
    }

    @Provides
    @Singleton
    fun providePaperlessApi(
        okHttpClient: OkHttpClient,
        gson: Gson
    ): PaperlessApi {
        // Use placeholder URL - DynamicBaseUrlInterceptor will set the actual URL
        return Retrofit.Builder()
            .baseUrl("http://placeholder.local/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(PaperlessApi::class.java)
    }

    // Paperless-GPT API

    @Provides
    @Singleton
    fun providePaperlessGptBaseUrlInterceptor(
        tokenManager: TokenManager
    ): PaperlessGptBaseUrlInterceptor = PaperlessGptBaseUrlInterceptor(tokenManager)

    /**
     * OkHttpClient for Paperless-GPT AI integration.
     * - Dynamic base URL for Paperless-GPT service
     * - Uses same auth token as Paperless-ngx
     *
     * Retry handled at the suspend boundary by repository callers via
     * [com.paperless.scanner.util.withRetry]; see [provideOkHttpClient] for
     * rationale.
     */
    @Provides
    @Singleton
    @PaperlessGptClient
    fun providePaperlessGptOkHttpClient(
        tokenManager: TokenManager,
        paperlessGptBaseUrlInterceptor: PaperlessGptBaseUrlInterceptor,
        httpAllowlistInterceptor: HttpAllowlistInterceptor,
        certificatePinningInterceptor: CertificatePinningInterceptor,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(createLoggingInterceptor())
            .addInterceptor(paperlessGptBaseUrlInterceptor)
            // Allowlist must run AFTER URL rewrite and BEFORE the token interceptor.
            // Ordering contract pinned by AppModuleInterceptorOrderTest (#221).
            .addInterceptor(httpAllowlistInterceptor)
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
            // TOFU pin enforcement — Paperless-GPT carries the same auth token (Issue #36).
            .addNetworkInterceptor(certificatePinningInterceptor)
            .applyTimeouts()
            .build()
    }

    @Provides
    @Singleton
    fun providePaperlessGptApi(
        @PaperlessGptClient okHttpClient: OkHttpClient,
        gson: Gson
    ): PaperlessGptApi {
        // Use placeholder URL - PaperlessGptBaseUrlInterceptor will set the actual URL
        return Retrofit.Builder()
            .baseUrl("http://placeholder.local/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(PaperlessGptApi::class.java)
    }

    @Provides
    @Singleton
    fun provideAuthRepository(
        @ApplicationContext context: Context,
        tokenManager: TokenManager,
        @AuthClient client: OkHttpClient,
        cloudflareDetectionInterceptor: CloudflareDetectionInterceptor,
        crashlyticsHelper: CrashlyticsHelperContract,
        authDebugService: AuthDebugService,
        httpCache: Cache,
        protocolDetector: ProtocolDetector,
    ): AuthRepository = AuthRepository(context, tokenManager, client, cloudflareDetectionInterceptor, crashlyticsHelper, authDebugService, httpCache, protocolDetector)

    @Provides
    @Singleton
    fun provideTagRepository(
        api: PaperlessApi,
        cachedTagDao: CachedTagDao,
        cachedDocumentDao: CachedDocumentDao,
        pendingChangeDao: PendingChangeDao,
        networkMonitor: NetworkMonitor,
        gson: Gson,
        serializer: DocumentSerializer
    ): TagRepository = TagRepository(api, cachedTagDao, cachedDocumentDao, pendingChangeDao, networkMonitor, gson, serializer)

    @Provides
    @Singleton
    @Named("cacheDir")
    fun provideCacheDir(@ApplicationContext context: Context): File = context.cacheDir

    @Provides
    @Singleton
    fun provideDocumentRepository(
        @Named("cacheDir") cacheDir: File,
        api: PaperlessApi,
        uploadMetricsTracker: UploadMetricsTracker,
        imageProcessor: ImageProcessorService,
        pdfGenerator: PdfGeneratorService,
        serializer: DocumentSerializer,
    ): DocumentRepository = DocumentRepository(cacheDir, api, uploadMetricsTracker, imageProcessor, pdfGenerator, serializer)

    @Provides
    @Singleton
    fun provideDocumentTypeRepository(
        api: PaperlessApi,
        cachedDocumentTypeDao: CachedDocumentTypeDao,
        pendingChangeDao: PendingChangeDao,
        networkMonitor: NetworkMonitor,
        @ApplicationScope applicationScope: CoroutineScope
    ): DocumentTypeRepository = DocumentTypeRepository(api, cachedDocumentTypeDao, pendingChangeDao, networkMonitor, applicationScope)

    @Provides
    @Singleton
    fun provideCorrespondentRepository(
        api: PaperlessApi,
        cachedCorrespondentDao: CachedCorrespondentDao,
        pendingChangeDao: PendingChangeDao,
        networkMonitor: NetworkMonitor,
        @ApplicationScope applicationScope: CoroutineScope
    ): CorrespondentRepository = CorrespondentRepository(api, cachedCorrespondentDao, pendingChangeDao, networkMonitor, applicationScope)

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
            MIGRATION_10_11, // SyncCenter feature: Added sync_history table
            MIGRATION_11_12  // Custom Fields: Added customFields to pending_uploads
        )

        // For debug builds, allow destructive migration if migration fails
        if (BuildConfig.DEBUG) {
            builder.fallbackToDestructiveMigration()
        }

        return builder.build()
    }

    // ============================================================
    // DAO Providers
    // ============================================================
    // NOTE: These providers ARE necessary for Hilt dependency injection.
    // Room DAOs are abstract classes/interfaces, and Hilt cannot automatically
    // resolve them from AppDatabase. Each DAO must be explicitly provided.
    //
    // Alternative approaches considered:
    // - @Binds abstract methods: Don't work for Room DAOs
    // - Direct AppDatabase injection: Forces consumers to know about database internals
    // - @InstallIn on AppDatabase: Room doesn't support Hilt annotations on @Database
    //
    // Current approach is the recommended pattern for Room + Hilt.

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
        @ApplicationContext context: Context,
        api: PaperlessGptApi,
        tokenManager: TokenManager
    ): PaperlessGptRepository = PaperlessGptRepository(context, api, tokenManager)

    @Provides
    @Singleton
    fun provideSyncManager(
        api: PaperlessApi,
        cachedDocumentDao: CachedDocumentDao,
        cachedTagDao: CachedTagDao,
        cachedCorrespondentDao: CachedCorrespondentDao,
        cachedDocumentTypeDao: CachedDocumentTypeDao,
        pendingChangeDao: PendingChangeDao,
        syncMetadataDao: SyncMetadataDao,
        gson: Gson,
        db: AppDatabase,
        serializer: DocumentSerializer
    ): SyncManager = SyncManager(
        api,
        cachedDocumentDao,
        cachedTagDao,
        cachedCorrespondentDao,
        cachedDocumentTypeDao,
        pendingChangeDao,
        syncMetadataDao,
        gson,
        db,
        serializer
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

    @Provides
    @Singleton
    fun provideCoroutineDispatchers(): com.paperless.scanner.util.CoroutineDispatchers =
        com.paperless.scanner.util.CoroutineDispatchers()

    // Crashlytics Helper for breadcrumb logging

    @Provides
    @Singleton
    fun provideCrashlyticsHelper(
        analyticsService: AnalyticsService
    ): CrashlyticsHelper = CrashlyticsHelper(analyticsService)

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

    // Coil Image Loading

    /**
     * OkHttpClient for Coil image loading (thumbnails).
     * Combines Auth token injection + SSL trust for self-signed certificates.
     */
    @Provides
    @Singleton
    @Named("CoilOkHttpClient")
    fun provideCoilOkHttpClient(
        tokenManager: TokenManager,
        httpAllowlistInterceptor: HttpAllowlistInterceptor,
        certificatePinningInterceptor: CertificatePinningInterceptor,
    ): OkHttpClient {
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
            .addInterceptor(createLoggingInterceptor())
            // Allowlist must run BEFORE the auth-token interceptor.
            // Ordering contract pinned by AppModuleInterceptorOrderTest (#221).
            .addInterceptor(httpAllowlistInterceptor)
            // Auth token interceptor for Paperless-ngx API
            .addInterceptor { chain ->
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
            // TOFU pin enforcement for thumbnail/document content (Issue #36).
            .addNetworkInterceptor(certificatePinningInterceptor)
            // SSL support for self-signed certificates
            .sslSocketFactory(sslContext.socketFactory, acceptedHostTrustManager)
            .hostnameVerifier(AcceptedHostnameVerifier(tokenManager))
            .applyTimeouts()
            .build()
    }

    /**
     * Coil ImageLoader for AsyncImage components.
     * Uses custom OkHttpClient with Auth + SSL support for thumbnails.
     */
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        @Named("CoilOkHttpClient") okHttpClient: OkHttpClient
    ): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = okHttpClient))
            }
            .build()
    }
}
