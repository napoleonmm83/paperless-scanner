package com.paperless.scanner.data.repository

import android.content.Context
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
