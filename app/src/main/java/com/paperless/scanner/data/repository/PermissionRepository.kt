package com.paperless.scanner.data.repository

import android.content.Context
import com.paperless.scanner.R
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.Group
import com.paperless.scanner.data.api.models.User
import com.paperless.scanner.data.network.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4 of #51 — extracted from DocumentRepository.
 *
 * Owns user/group lookup for permission management: getUsers, getGroups.
 * Both methods are online-only thin wrappers around PaperlessApi; offline
 * branches return PaperlessException.NetworkError.
 *
 * NOTE: Return types `Result<List<User>>` and `Result<List<Group>>` use the
 * data-layer DTO types (not domain models). This is pre-existing behavior
 * preserved byte-identically; Phase 5 (#171) will migrate the call site
 * (DocumentDetailViewModel) and switch the return types to domain.
 */
@Singleton
class PermissionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessApi,
    private val networkMonitor: NetworkMonitor,
) {

    suspend fun getUsers(): Result<List<User>> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val response = api.getUsers()
                Result.success(response.results)
            } else {
                Result.failure(
                    PaperlessException.NetworkError(IOException(context.getString(R.string.error_offline)))
                )
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun getGroups(): Result<List<Group>> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val response = api.getGroups()
                Result.success(response.results)
            } else {
                Result.failure(
                    PaperlessException.NetworkError(IOException(context.getString(R.string.error_offline)))
                )
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }
}
