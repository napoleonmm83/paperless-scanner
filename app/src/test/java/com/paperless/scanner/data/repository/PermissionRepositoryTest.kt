package com.paperless.scanner.data.repository

import android.content.Context
import androidx.test.filters.SmallTest
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.Group as ApiGroup
import com.paperless.scanner.data.api.models.GroupsResponse
import com.paperless.scanner.data.api.models.User as ApiUser
import com.paperless.scanner.data.api.models.UsersResponse
import com.paperless.scanner.domain.model.Group as DomainGroup
import com.paperless.scanner.domain.model.User as DomainUser
import com.paperless.scanner.data.network.NetworkMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response

/**
 * Repository tests for [PermissionRepository].
 *
 * Marked `@SmallTest` because [PermissionRepository] depends only on
 * `Context`, `PaperlessApi`, and `NetworkMonitor` — no Room DAO. Robolectric
 * is still required for `Context` access. Pure unit test scope per Issue #137.
 */
@SmallTest
@RunWith(RobolectricTestRunner::class)
class PermissionRepositoryTest {

    private lateinit var context: Context
    private lateinit var api: PaperlessApi
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var repo: PermissionRepository

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        every { context.getString(any()) } returns "offline"
        api = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        repo = PermissionRepository(context, api, networkMonitor)
    }

    @Test
    fun `getUsers online returns domain User list mapped from DTO`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        val apiUsers = listOf(
            ApiUser(id = 1, username = "alice", email = "alice@example.com", isStaff = true),
            ApiUser(id = 2, username = "bob"),
        )
        coEvery { api.getUsers(any(), any()) } returns
            UsersResponse(count = 2, next = null, previous = null, results = apiUsers)

        val result = repo.getUsers()

        assertTrue(result.isSuccess)
        val domainUsers = result.getOrNull()!!
        assertEquals(2, domainUsers.size)
        assertEquals(DomainUser(id = 1, username = "alice", email = "alice@example.com", isStaff = true), domainUsers[0])
        assertEquals(DomainUser(id = 2, username = "bob"), domainUsers[1])
        coVerify { api.getUsers(any(), any()) }
    }

    @Test
    fun `getUsers walks all pages and returns every user when results span multiple pages`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        val page1 = (1..100).map { ApiUser(id = it, username = "user$it") }
        val page2 = (101..150).map { ApiUser(id = it, username = "user$it") }
        coEvery { api.getUsers(page = 1, pageSize = 100) } returns
            UsersResponse(count = 150, next = "https://example.test/api/users/?page=2", previous = null, results = page1)
        coEvery { api.getUsers(page = 2, pageSize = 100) } returns
            UsersResponse(count = 150, next = null, previous = null, results = page2)

        val result = repo.getUsers()

        assertTrue(result.isSuccess)
        // Issue #126: all 150 returned, not just the first page of 100.
        assertEquals(150, result.getOrNull()?.size)
        coVerify(exactly = 1) { api.getUsers(page = 1, pageSize = 100) }
        coVerify(exactly = 1) { api.getUsers(page = 2, pageSize = 100) }
        // Mapping verification: first + last DTOs mapped to domain across the page boundary.
        assertEquals(DomainUser(id = 1, username = "user1"), result.getOrNull()?.first())
        assertEquals(DomainUser(id = 150, username = "user150"), result.getOrNull()?.last())
    }

    @Test
    fun `getGroups walks all pages and returns every group when results span multiple pages`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        val page1 = (1..100).map { ApiGroup(id = it, name = "group$it") }
        val page2 = (101..150).map { ApiGroup(id = it, name = "group$it") }
        coEvery { api.getGroups(page = 1, pageSize = 100) } returns
            GroupsResponse(count = 150, next = "https://example.test/api/groups/?page=2", previous = null, results = page1)
        coEvery { api.getGroups(page = 2, pageSize = 100) } returns
            GroupsResponse(count = 150, next = null, previous = null, results = page2)

        val result = repo.getGroups()

        assertTrue(result.isSuccess)
        // Issue #126: all 150 returned, not just the first page of 100.
        assertEquals(150, result.getOrNull()?.size)
        coVerify(exactly = 1) { api.getGroups(page = 1, pageSize = 100) }
        coVerify(exactly = 1) { api.getGroups(page = 2, pageSize = 100) }
        assertEquals(DomainGroup(id = 1, name = "group1"), result.getOrNull()?.first())
        assertEquals(DomainGroup(id = 150, name = "group150"), result.getOrNull()?.last())
    }

    @Test
    fun `getUsers offline returns NetworkError`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false

        val result = repo.getUsers()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PaperlessException.NetworkError)
        coVerify(exactly = 0) { api.getUsers(any(), any()) }
    }

    @Test
    fun `getGroups online returns domain Group list mapped from DTO`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        val apiGroups = listOf(ApiGroup(id = 10, name = "editors"))
        coEvery { api.getGroups(any(), any()) } returns
            GroupsResponse(count = 1, next = null, previous = null, results = apiGroups)

        val result = repo.getGroups()

        assertTrue(result.isSuccess)
        val domainGroups = result.getOrNull()!!
        assertEquals(1, domainGroups.size)
        assertEquals(DomainGroup(id = 10, name = "editors"), domainGroups[0])
        coVerify { api.getGroups(any(), any()) }
    }

    @Test
    fun `getGroups offline returns NetworkError`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false

        val result = repo.getGroups()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PaperlessException.NetworkError)
        coVerify(exactly = 0) { api.getGroups(any(), any()) }
    }

    @Test
    fun `getUsers online HttpException maps to PaperlessException`() = runTest {
        // Covers the HttpException catch branch (shared by both methods); one test
        // is sufficient because the catch logic is identical across getUsers and
        // getGroups. (DRY pattern from Phase 3.2 AuditRepositoryTest.)
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        val errorBody = "{}".toResponseBody("application/json".toMediaTypeOrNull())
        coEvery { api.getUsers(any(), any()) } throws HttpException(Response.error<Any>(403, errorBody))

        val result = repo.getUsers()

        assertTrue(result.isFailure)
        assertTrue(
            "expected PaperlessException, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is PaperlessException
        )
    }
}
