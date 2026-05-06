package com.paperless.scanner.data.repository

import android.content.Context
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.Group
import com.paperless.scanner.data.api.models.GroupsResponse
import com.paperless.scanner.data.api.models.User
import com.paperless.scanner.data.api.models.UsersResponse
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
    fun `getUsers online returns DTO list from response results`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        val users = listOf(mockk<User>(relaxed = true), mockk<User>(relaxed = true))
        coEvery { api.getUsers(any(), any()) } returns
            UsersResponse(count = 2, next = null, previous = null, results = users)

        val result = repo.getUsers()

        assertTrue(result.isSuccess)
        assertEquals(users, result.getOrNull())
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
    fun `getGroups online returns DTO list from response results`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        val groups = listOf(mockk<Group>(relaxed = true))
        coEvery { api.getGroups(any(), any()) } returns
            GroupsResponse(count = 1, next = null, previous = null, results = groups)

        val result = repo.getGroups()

        assertTrue(result.isSuccess)
        assertEquals(groups, result.getOrNull())
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
    fun `getUsers online HttpException maps to PaperlessException via fromHttpCode`() = runTest {
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
