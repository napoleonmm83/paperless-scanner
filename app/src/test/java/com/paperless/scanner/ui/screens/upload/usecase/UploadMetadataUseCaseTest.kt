package com.paperless.scanner.ui.screens.upload.usecase

import android.content.Context
import android.util.Log
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.CustomFieldRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.domain.error.PaperlessException
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.Tag
import java.io.IOException
import com.paperless.scanner.util.CoroutineDispatchers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UploadMetadataUseCaseTest {

    private lateinit var context: Context
    private lateinit var tagRepository: TagRepository
    private lateinit var documentTypeRepository: DocumentTypeRepository
    private lateinit var correspondentRepository: CorrespondentRepository
    private lateinit var customFieldRepository: CustomFieldRepository
    private lateinit var analyticsService: AnalyticsService
    private lateinit var useCase: UploadMetadataUseCase

    private val dispatchers = UnconfinedTestDispatcher().let {
        CoroutineDispatchers(io = it, default = it, main = it)
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        context = mockk(relaxed = true)
        every { context.getString(R.string.error_create_tag) } returns "Could not create tag"

        tagRepository = mockk(relaxed = true)
        documentTypeRepository = mockk(relaxed = true)
        correspondentRepository = mockk(relaxed = true)
        customFieldRepository = mockk(relaxed = true)
        analyticsService = mockk(relaxed = true)
        every { tagRepository.observeTags() } returns flowOf(emptyList())

        useCase = UploadMetadataUseCase(
            context = context,
            tagRepository = tagRepository,
            documentTypeRepository = documentTypeRepository,
            correspondentRepository = correspondentRepository,
            customFieldRepository = customFieldRepository,
            analyticsService = analyticsService,
            dispatchers = dispatchers,
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `createTag success returns Success and tracks analytics`() = runTest {
        val newTag = Tag(id = 7, name = "Invoice", color = "#FFFFFF")
        coEvery { tagRepository.createTag(name = "Invoice", color = "#FFFFFF") } returns Result.success(newTag)

        val result = useCase.createTag("Invoice", "#FFFFFF")

        assertTrue(result is CreateTagResult.Success)
        assertEquals(newTag, (result as CreateTagResult.Success).tag)
        coVerify { analyticsService.trackEvent(AnalyticsEvent.TagCreated) }
    }

    @Test
    fun `createTag generic failure returns Failure with message`() = runTest {
        coEvery { tagRepository.createTag(name = any(), color = any()) } returns
            Result.failure(Exception("Network error"))

        val result = useCase.createTag("Invoice", null)

        assertTrue(result is CreateTagResult.Failure)
        assertEquals("Network error", (result as CreateTagResult.Failure).message)
    }

    @Test
    fun `createTag duplicate error recovers existing tag`() = runTest {
        val existing = Tag(id = 42, name = "Existing", color = "#FF0000")
        coEvery { tagRepository.createTag(name = any(), color = any()) } returns
            Result.failure(Exception("name unique constraint violated"))
        coEvery { tagRepository.getTags(forceRefresh = true) } returns Result.success(listOf(existing))
        every { tagRepository.observeTags() } returns flowOf(listOf(existing))

        val result = useCase.createTag("existing", null)

        assertTrue(result is CreateTagResult.Success)
        assertEquals(existing, (result as CreateTagResult.Success).tag)
    }

    @Test
    fun `createCorrespondent trims the name and returns Success`() = runTest {
        val created = Correspondent(id = 9, name = "ACME AG")
        coEvery { correspondentRepository.createCorrespondent("ACME AG") } returns Result.success(created)

        val result = useCase.createCorrespondent("  ACME AG  ")

        assertEquals(CreateCorrespondentResult.Success(created), result)
    }

    @Test
    fun `createCorrespondent offline returns the localized network message`() = runTest {
        every { context.getString(R.string.error_network_check_internet) } returns "No internet connection"
        coEvery { correspondentRepository.createCorrespondent(any()) } returns
            Result.failure(PaperlessException.NetworkError(IOException("Unable to resolve host")))

        val result = useCase.createCorrespondent("ACME AG")

        assertEquals(CreateCorrespondentResult.Failure("No internet connection"), result)
        // Not a 4xx: must not try to resolve a duplicate.
        coVerify(exactly = 0) { correspondentRepository.getCorrespondents(any()) }
    }

    @Test
    fun `createCorrespondent 400 for an existing name resolves it case-insensitively`() = runTest {
        val existing = Correspondent(id = 3, name = "ACME AG")
        coEvery { correspondentRepository.createCorrespondent(any()) } returns
            Result.failure(PaperlessException.ClientError(400, "correspondent with this name already exists"))
        coEvery { correspondentRepository.getCorrespondents(forceRefresh = true) } returns
            Result.success(listOf(existing))

        val result = useCase.createCorrespondent("acme ag")

        assertEquals(CreateCorrespondentResult.Success(existing), result)
    }

    @Test
    fun `createCorrespondent 400 without a matching name surfaces the localized error`() = runTest {
        every { context.getString(R.string.error_invalid_request) } returns "Invalid request"
        coEvery { correspondentRepository.createCorrespondent(any()) } returns
            Result.failure(PaperlessException.ClientError(400))
        coEvery { correspondentRepository.getCorrespondents(forceRefresh = true) } returns
            Result.success(emptyList())

        val result = useCase.createCorrespondent("ACME AG")

        assertEquals(CreateCorrespondentResult.Failure("Invalid request"), result)
    }

    @Test
    fun `observe passthroughs delegate to repositories`() {
        val tagsFlow = flowOf(listOf(Tag(id = 1, name = "A")))
        every { tagRepository.observeTags() } returns tagsFlow

        assertEquals(tagsFlow, useCase.observeTags())
    }
}
