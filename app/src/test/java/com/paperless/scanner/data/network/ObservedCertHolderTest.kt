package com.paperless.scanner.data.network

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #249: the reactive [ObservedCertHolder.latest] surface that drives the app-wide
 * re-trust dialog, plus the #36 peek/consume map behavior. Flow assertions use
 * Turbine to verify the emitted transition sequence (per .coderabbit.yaml).
 */
class ObservedCertHolderTest {

    private val holder = ObservedCertHolder()
    private val mismatch = ObservedCertHolder.Mismatch("paperless.lan", "sha256/OLD", "sha256/NEW")

    @Test
    fun `latest starts null`() = runTest {
        holder.latest.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `record then consume drives latest null to mismatch to null (case-insensitive)`() = runTest {
        holder.latest.test {
            assertNull(awaitItem())

            holder.record(mismatch)
            assertEquals(mismatch, awaitItem())
            assertEquals(mismatch, holder.peek("paperless.lan"))

            holder.consume("PAPERLESS.LAN")
            assertNull(awaitItem())
            assertNull(holder.peek("paperless.lan"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `consume of a different host leaves latest unchanged`() = runTest {
        holder.latest.test {
            assertNull(awaitItem())
            holder.record(mismatch)
            assertEquals(mismatch, awaitItem())

            holder.consume("other.host")

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `record overwrites latest with the most recent mismatch`() = runTest {
        holder.latest.test {
            assertNull(awaitItem())
            holder.record(mismatch)
            assertEquals(mismatch, awaitItem())

            val newer = ObservedCertHolder.Mismatch("paperless.lan", "sha256/OLD", "sha256/NEWER")
            holder.record(newer)

            assertEquals(newer, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `consuming the latest promotes another still-pending host`() = runTest {
        val a = ObservedCertHolder.Mismatch("server-a.lan", "sha256/A_OLD", "sha256/A_NEW")
        val b = ObservedCertHolder.Mismatch("server-b.lan", "sha256/B_OLD", "sha256/B_NEW")
        holder.latest.test {
            assertNull(awaitItem())
            holder.record(a)
            assertEquals(a, awaitItem())
            holder.record(b)
            assertEquals(b, awaitItem()) // newest shown first

            // Resolving B must surface A (the other unresolved host), not null.
            holder.consume("server-b.lan")
            assertEquals(a, awaitItem())

            // Resolving the last host clears the surface.
            holder.consume("server-a.lan")
            assertNull(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `consumeIfMatches removes and clears latest only when the pin matches`() = runTest {
        holder.latest.test {
            assertNull(awaitItem())
            holder.record(mismatch) // actualPin = sha256/NEW
            assertEquals(mismatch, awaitItem())

            // Non-matching pin: returns null, leaves the entry and latest intact.
            assertNull(holder.consumeIfMatches("paperless.lan", "sha256/STALE"))
            assertEquals(mismatch, holder.peek("paperless.lan"))
            expectNoEvents()

            // Matching pin: returns the entry, removes it, clears latest.
            assertEquals(mismatch, holder.consumeIfMatches("paperless.lan", "sha256/NEW"))
            assertNull(holder.peek("paperless.lan"))
            assertNull(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }
}
