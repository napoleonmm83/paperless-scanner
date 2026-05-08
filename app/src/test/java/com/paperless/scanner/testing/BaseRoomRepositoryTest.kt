package com.paperless.scanner.testing

import androidx.room.Room
import com.paperless.scanner.data.database.AppDatabase
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Base class for repository tests that exercise real Room DAOs against an
 * in-memory database, per CLAUDE.md "don't mock the database" rule
 * (Issue #137).
 *
 * Subclasses inherit a fresh [database] for each test and should mock only
 * boundary collaborators (`PaperlessApi`, `NetworkMonitor`, `CrashlyticsHelper`,
 * etc.). DAOs come from the real Room schema, so insert/query semantics,
 * onConflict strategies, and Flow emissions are all verified end-to-end.
 *
 * Per project convention (.coderabbit.yaml), tests using this base wrap
 * coroutine code in `kotlinx.coroutines.test.runTest`, not `runBlocking`.
 *
 * Tests built on this class are integration tests against the real DB
 * (heavier than pure unit tests). They still run on the JVM via Robolectric
 * — no emulator required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
abstract class BaseRoomRepositoryTest {

    protected lateinit var database: AppDatabase

    @Before
    fun setUpDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDownDatabase() {
        database.close()
    }
}
