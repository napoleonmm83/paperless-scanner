package com.paperless.scanner.ui.screens.upload.usecase

import android.content.Context
import org.robolectric.RuntimeEnvironment
import androidx.test.filters.LargeTest
import com.paperless.scanner.R
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.CorrespondentsResponse
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.testing.BaseRoomRepositoryTest
import com.paperless.scanner.util.CoroutineDispatchers
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import com.paperless.scanner.data.api.models.Correspondent as ApiCorrespondent

/**
 * Creation boundary end to end: real [UploadMetadataUseCase] → real [CorrespondentRepository]
 * → real in-memory Room. Only the HTTP boundary ([PaperlessApi]) and connectivity are mocked,
 * so the error mapping (Retrofit/IO exception → PaperlessException → localized text) is exercised
 * for real rather than stubbed as a Result.
 */
@LargeTest
class CreateCorrespondentIntegrationTest : BaseRoomRepositoryTest() {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val api: PaperlessApi = mockk()
    private val networkMonitor: NetworkMonitor = mockk(relaxed = true)
    private lateinit var repository: CorrespondentRepository

    @Before
    fun setup() {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
    }

    // Everything MUST run on runTest's scheduler: on a foreign scheduler the refresh path's
    // timeout/retry delays are never advanced and the test hangs (measured: 20-min task timeout).
    // backgroundScope is on that scheduler and is cancelled by runTest, so no @After is needed.
    private fun TestScope.useCase(): UploadMetadataUseCase {
        repository = CorrespondentRepository(
            api, database.cachedCorrespondentDao(), database.pendingChangeDao(), networkMonitor, backgroundScope,
        )
        val io = UnconfinedTestDispatcher(testScheduler)
        return UploadMetadataUseCase(
            context = context,
            tagRepository = mockk(relaxed = true),
            documentTypeRepository = mockk(relaxed = true),
            correspondentRepository = repository,
            customFieldRepository = mockk(relaxed = true),
            analyticsService = mockk(relaxed = true),
            dispatchers = CoroutineDispatchers(io = io, default = io, main = io),
        )
    }

    @Test
    fun `created correspondent is returned and lands in the cache the dropdown observes`() = runTest {
        coEvery { api.createCorrespondent(any()) } returns ApiCorrespondent(id = 11, name = "ACME AG")

        val result = useCase().createCorrespondent(" ACME AG ")

        assertEquals(CreateCorrespondentResult.Success(Correspondent(id = 11, name = "ACME AG")), result)
        assertEquals(listOf("ACME AG"), repository.getCachedCorrespondents().map { it.name })
    }

    @Test
    fun `network failure yields the localized connectivity message`() = runTest {
        coEvery { api.createCorrespondent(any()) } throws IOException("connection reset")

        val result = useCase().createCorrespondent("ACME AG")

        assertEquals(
            CreateCorrespondentResult.Failure(context.getString(R.string.error_network_check_internet)),
            result,
        )
    }

    @Test
    fun `server 400 for a name that exists remotely selects the existing one`() = runTest {
        coEvery { api.createCorrespondent(any()) } throws
            HttpException(Response.error<Any>(400, """{"name":["already exists"]}""".toResponseBody(null)))
        coEvery { api.getCorrespondents(page = 1, pageSize = any()) } returns
            CorrespondentsResponse(count = 1, results = listOf(ApiCorrespondent(id = 4, name = "ACME AG")))

        val result = useCase().createCorrespondent("acme ag")

        assertEquals(4, (result as CreateCorrespondentResult.Success).correspondent.id)
    }
}
