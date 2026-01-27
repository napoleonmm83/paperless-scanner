package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.CreateCustomFieldRequest
import com.paperless.scanner.data.api.models.CustomField
import com.paperless.scanner.data.api.models.CustomFieldsResponse
import com.paperless.scanner.data.network.NetworkMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class CustomFieldRepositoryTest {

    private lateinit var api: PaperlessApi
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var customFieldRepository: CustomFieldRepository

    @Before
    fun setup() {
        api = mockk()
        networkMonitor = mockk(relaxed = true)
        customFieldRepository = CustomFieldRepository(
            api,
            networkMonitor
        )
    }

    // Flow Reactivity Tests (In-Memory Cache)
    @Test
    fun `observeCustomFields returns Flow from in-memory cache`() = runTest {
        // Setup: Populate cache first via getCustomFields
        val apiFields = listOf(
            CustomField(id = 1, name = "Betrag", dataType = "monetary")
        )
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getCustomFields(page = 1, pageSize = 100) } returns CustomFieldsResponse(
            count = 1,
            results = apiFields
        )
        customFieldRepository.getCustomFields(forceRefresh = true)

        // Test: Observe returns cached data
        val result = customFieldRepository.observeCustomFields().first()

        assertEquals(1, result.size)
        assertEquals("Betrag", result[0].name)
    }

    // Get Tests (In-Memory Cache)
    @Test
    fun `getCustomFields returns cached data when available and not forcing refresh`() = runTest {
        // Populate cache
        val apiFields = listOf(CustomField(id = 1, name = "Cached", dataType = "string"))
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getCustomFields(page = 1, pageSize = 100) } returns CustomFieldsResponse(
            count = 1,
            results = apiFields
        )
        customFieldRepository.getCustomFields(forceRefresh = true)

        // Get without forceRefresh
        val result = customFieldRepository.getCustomFields(forceRefresh = false)

        assertTrue(result.isSuccess)
        assertEquals("Cached", result.getOrNull()?.get(0)?.name)
        coVerify(exactly = 1) { api.getCustomFields(any(), any()) } // Only called once (initial)
    }

    @Test
    fun `getCustomFields with forceRefresh fetches from API when online`() = runTest {
        val apiFields = listOf(
            CustomField(id = 1, name = "Field1", dataType = "string"),
            CustomField(id = 2, name = "Field2", dataType = "integer")
        )
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getCustomFields(page = 1, pageSize = 100) } returns CustomFieldsResponse(
            count = 2,
            results = apiFields
        )

        val result = customFieldRepository.getCustomFields(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()?.size)
        coVerify(exactly = 1) { api.getCustomFields(page = 1, pageSize = 100) }
    }

    @Test
    fun `getCustomFields returns empty list when offline and cache empty`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns false

        val result = customFieldRepository.getCustomFields(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<CustomField>(), result.getOrNull())
    }

    // Feature Detection (404 Handling)
    @Test
    fun `getCustomFields returns empty list on 404 (feature not available)`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns true
        val http404 = HttpException(Response.error<Any>(404, okhttp3.ResponseBody.create(null, "")))
        coEvery { api.getCustomFields(page = 1, pageSize = 100) } throws http404

        val result = customFieldRepository.getCustomFields(forceRefresh = true)

        // Feature detection: 404 returns success with empty list
        assertTrue(result.isSuccess)
        assertEquals(emptyList<CustomField>(), result.getOrNull())
    }

    @Test
    fun `getCustomFields returns failure on non-404 error`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getCustomFields(page = 1, pageSize = 100) } throws IOException("Network error")

        val result = customFieldRepository.getCustomFields(forceRefresh = true)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Network error") == true)
    }

    // Create Tests (In-Memory Cache Update for Flow Trigger)
    @Test
    fun `createCustomField success returns new field and updates in-memory cache`() = runTest {
        val newField = CustomField(id = 10, name = "NewField", dataType = "date")
        coEvery { api.createCustomField(any()) } returns newField

        val result = customFieldRepository.createCustomField("NewField", "date")

        assertTrue(result.isSuccess)
        assertEquals("NewField", result.getOrNull()?.name)
        assertEquals("date", result.getOrNull()?.dataType)
        coVerify { api.createCustomField(CreateCustomFieldRequest(name = "NewField", dataType = "date")) }

        // Verify cache updated (Flow trigger)
        val cached = customFieldRepository.observeCustomFields().first()
        assertEquals(1, cached.size)
        assertEquals("NewField", cached[0].name)
    }

    @Test
    fun `createCustomField with default dataType uses string`() = runTest {
        val newField = CustomField(id = 11, name = "DefaultField", dataType = "string")
        coEvery { api.createCustomField(any()) } returns newField

        val result = customFieldRepository.createCustomField("DefaultField")

        assertTrue(result.isSuccess)
        coVerify { api.createCustomField(CreateCustomFieldRequest(name = "DefaultField", dataType = "string")) }
    }

    @Test
    fun `createCustomField network error returns failure`() = runTest {
        coEvery { api.createCustomField(any()) } throws IOException("Connection refused")

        val result = customFieldRepository.createCustomField("FailField")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Connection refused") == true)
    }

    // Delete Tests (In-Memory Cache Removal for Flow Trigger)
    @Test
    fun `deleteCustomField success removes from in-memory cache`() = runTest {
        // Setup: Add field to cache
        val field = CustomField(id = 1, name = "ToDelete", dataType = "string")
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getCustomFields(page = 1, pageSize = 100) } returns CustomFieldsResponse(
            count = 1,
            results = listOf(field)
        )
        customFieldRepository.getCustomFields(forceRefresh = true)

        // Delete
        val mockResponse = mockk<retrofit2.Response<Unit>>(relaxed = true)
        coEvery { api.deleteCustomField(1) } returns mockResponse
        val result = customFieldRepository.deleteCustomField(1)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { api.deleteCustomField(1) }

        // Verify removed from cache (Flow trigger)
        val cached = customFieldRepository.observeCustomFields().first()
        assertEquals(0, cached.size)
    }

    @Test
    fun `deleteCustomField network error returns failure`() = runTest {
        coEvery { api.deleteCustomField(any()) } throws IOException("Server error")

        val result = customFieldRepository.deleteCustomField(1)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Server error") == true)
    }

    // Feature Detection API Availability
    @Test
    fun `isCustomFieldsApiAvailable returns true when API is available`() = runTest {
        coEvery { api.getCustomFields(page = 1, pageSize = 1) } returns CustomFieldsResponse(
            count = 0,
            results = emptyList()
        )

        val result = customFieldRepository.isCustomFieldsApiAvailable()

        assertTrue(result)
    }

    @Test
    fun `isCustomFieldsApiAvailable returns false on 404`() = runTest {
        val http404 = HttpException(Response.error<Any>(404, okhttp3.ResponseBody.create(null, "")))
        coEvery { api.getCustomFields(page = 1, pageSize = 1) } throws http404

        val result = customFieldRepository.isCustomFieldsApiAvailable()

        assertFalse(result)
    }

    @Test
    fun `isCustomFieldsApiAvailable returns true on non-404 HttpException`() = runTest {
        val http500 = HttpException(Response.error<Any>(500, okhttp3.ResponseBody.create(null, "")))
        coEvery { api.getCustomFields(page = 1, pageSize = 1) } throws http500

        val result = customFieldRepository.isCustomFieldsApiAvailable()

        assertTrue(result) // Non-404 errors mean API exists but has other issues
    }

    @Test
    fun `isCustomFieldsApiAvailable returns false on generic exception`() = runTest {
        coEvery { api.getCustomFields(page = 1, pageSize = 1) } throws IOException("Network error")

        val result = customFieldRepository.isCustomFieldsApiAvailable()

        assertFalse(result)
    }
}
