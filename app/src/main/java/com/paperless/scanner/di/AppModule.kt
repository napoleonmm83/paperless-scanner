package com.paperless.scanner.di

import android.content.Context
import androidx.room.Room
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.RetryInterceptor
import com.paperless.scanner.data.database.AppDatabase
import com.paperless.scanner.data.database.PendingUploadDao
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.AuthRepository
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideTokenManager(
        @ApplicationContext context: Context
    ): TokenManager = TokenManager(context)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        tokenManager: TokenManager
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
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
            .addInterceptor(RetryInterceptor(maxRetries = 3))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun providePaperlessApi(
        okHttpClient: OkHttpClient,
        tokenManager: TokenManager
    ): PaperlessApi {
        val baseUrl = tokenManager.getServerUrlSync() ?: "http://localhost/"

        return Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PaperlessApi::class.java)
    }

    @Provides
    @Singleton
    fun provideAuthRepository(
        tokenManager: TokenManager
    ): AuthRepository = AuthRepository(tokenManager)

    @Provides
    @Singleton
    fun provideTagRepository(
        api: PaperlessApi
    ): TagRepository = TagRepository(api)

    @Provides
    @Singleton
    fun provideDocumentRepository(
        @ApplicationContext context: Context,
        api: PaperlessApi
    ): DocumentRepository = DocumentRepository(context, api)

    @Provides
    @Singleton
    fun provideDocumentTypeRepository(
        api: PaperlessApi
    ): DocumentTypeRepository = DocumentTypeRepository(api)

    @Provides
    @Singleton
    fun provideCorrespondentRepository(
        api: PaperlessApi
    ): CorrespondentRepository = CorrespondentRepository(api)

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        AppDatabase.DATABASE_NAME
    ).build()

    @Provides
    @Singleton
    fun providePendingUploadDao(
        database: AppDatabase
    ): PendingUploadDao = database.pendingUploadDao()

    @Provides
    @Singleton
    fun provideUploadQueueRepository(
        dao: PendingUploadDao
    ): UploadQueueRepository = UploadQueueRepository(dao)
}
