package com.paperless.scanner.data.repository

import android.content.Context
import com.paperless.scanner.R
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.fetchAllPages
import com.paperless.scanner.data.api.models.Group as ApiGroup
import com.paperless.scanner.data.api.models.User as ApiUser
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.Group
import com.paperless.scanner.domain.model.User
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.util.NetworkConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Phase 4 of #51 — extracted from DocumentRepository.
 *
 * Owns user/group lookup for permission management: getUsers, getGroups.
 * Both methods are online-only thin wrappers around PaperlessApi; offline
 * branches return PaperlessException.NetworkError.
 *
 * Return types `Result<List<User>>` and `Result<List<Group>>` use domain models
 * (issue #192: DTO→Domain mapping).
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
                // Walk ALL pages — fetching only page 1 silently truncates the user list
                // on servers with more users than the page size (Issue #126).
                val users = fetchAllPages { page ->
                    api.getUsers(page = page, pageSize = NetworkConfig.DEFAULT_PAGE_SIZE)
                }
                Result.success(users.map { it.toDomain() })
            } else {
                Result.failure(
                    PaperlessException.NetworkError(IOException(context.getString(R.string.error_offline)))
                )
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun getGroups(): Result<List<Group>> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                // Walk ALL pages — fetching only page 1 silently truncates the group list
                // on servers with more groups than the page size (Issue #126).
                val groups = fetchAllPages { page ->
                    api.getGroups(page = page, pageSize = NetworkConfig.DEFAULT_PAGE_SIZE)
                }
                Result.success(groups.map { it.toDomain() })
            } else {
                Result.failure(
                    PaperlessException.NetworkError(IOException(context.getString(R.string.error_offline)))
                )
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }
}
