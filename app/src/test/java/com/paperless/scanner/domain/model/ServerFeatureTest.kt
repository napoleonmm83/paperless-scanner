package com.paperless.scanner.domain.model

import org.junit.Assert.*
import org.junit.Test

class ServerFeatureTest {
    @Test fun versionInputRejectsEmptyOversizedAndOverflowingValues() {
        val prefix = "2.20.0+"
        assertNotNull(PaperlessServerVersion.parse(prefix + "a".repeat(128 - prefix.length)))
        assertNull(PaperlessServerVersion.parse(prefix + "a".repeat(129 - prefix.length)))
        listOf(null, "", " ", "2147483648.0.0").forEach {
            assertNull(PaperlessServerVersion.parse(it))
        }
    }

    @Test fun `version rendering is readable and development prereleases are unknown`() {
        assertEquals("2.20.0", PaperlessServerVersion.parse("v2.20.0+build.1").toString())
        assertEquals("3.0.0-rc.1", PaperlessServerVersion.parse("v3.0.0-rc.1").toString())
        listOf("3.0.0-dev", "3.0.0-dev.1", "3.0.0-dev123", "3.0.0-DEV.1", "3.0.0-rc.dev1").forEach {
            assertNull(it, PaperlessServerVersion.parse(it))
        }
    }

    @Test fun `versions compare numbers and semver prereleases`() {
        fun v(value: String) = requireNotNull(PaperlessServerVersion.parse(value))
        assertTrue(v("2.10.0") > v("2.9.9"))
        assertTrue(v("v2.20.0-rc.10") > v("2.20.0-rc.2"))
        assertTrue(v("2.20.0") > v("2.20.0-rc.10"))
        assertEquals(0, v("2.20.0+build.1").compareTo(v("2.20.0+build.2")))
        val ordered = listOf("2.20.0-alpha", "2.20.0-alpha.1", "2.20.0-alpha.beta", "2.20.0-beta", "2.20.0-beta.2", "2.20.0-beta.11", "2.20.0-rc.1", "2.20.0")
        ordered.zipWithNext().forEach { (left, right) -> assertTrue("$left < $right", v(left) < v(right)) }
        listOf("dev", "unknown", "2.20", "02.20.0", "2.20.0-01", "2.20.0-", "2.20.0 garbage").forEach {
            assertNull(it, PaperlessServerVersion.parse(it))
        }
    }

    @Test fun `catalog never presents future work as a server upgrade`() {
        assertEquals(9, ServerFeatureCatalog.features.size)
        val futureFeatures = ServerFeatureCatalog.features.filter { it.id != "share_links" }
        assertEquals(8, futureFeatures.size)
        futureFeatures.forEach {
            assertFalse(it.implemented)
            assertNull(it.minimumVersion)
            assertEquals(FeatureStatus.NOT_IMPLEMENTED, it.evaluate(PaperlessServerVersion.parse("1.0.0")))
        }
        assertEquals(listOf("share_links"), ServerFeatureCatalog.upgradeRequired(PaperlessServerVersion.parse("1.0.0")).map { it.id })
    }

    @Test fun `share links require stable Paperless 2 and are available on newer servers`() {
        val shareLinks = ServerFeatureCatalog.features.single { it.id == "share_links" }
        assertTrue(shareLinks.implemented)
        assertEquals("2.0.0", shareLinks.minimumVersion.toString())
        assertEquals(FeatureStatus.UNKNOWN, shareLinks.evaluate(null))
        listOf("1.17.4", "2.0.0-rc.1").forEach {
            assertEquals(FeatureStatus.UPDATE_REQUIRED, shareLinks.evaluate(PaperlessServerVersion.parse(it)))
        }
        listOf("2.0.0", "2.20.0", "3.0.0").forEach {
            assertEquals(FeatureStatus.AVAILABLE, shareLinks.evaluate(PaperlessServerVersion.parse(it)))
            assertTrue(ServerFeatureCatalog.upgradeRequired(PaperlessServerVersion.parse(it)).isEmpty())
        }
    }

    @Test fun `only implemented version gated features can require update`() {
        val minimum = requireNotNull(PaperlessServerVersion.parse("2.20.0"))
        val feature = ServerFeature("example", true, minimum)
        assertEquals(FeatureStatus.UNKNOWN, feature.evaluate(null))
        assertEquals(FeatureStatus.UPDATE_REQUIRED, feature.evaluate(PaperlessServerVersion.parse("2.20.0-rc.1")))
        assertEquals(FeatureStatus.AVAILABLE, feature.evaluate(minimum))
        assertEquals(FeatureStatus.UNKNOWN, feature.copy(minimumVersion = null).evaluate(minimum))
    }
}
