package com.paperless.scanner.data.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageDecodingTest {

    @Test
    fun `images up to 16MP decode at full resolution`() {
        assertEquals(1, calculateInSampleSize(4000, 4000)) // exactly 16MP
        assertEquals(1, calculateInSampleSize(4000, 3000))
    }

    @Test
    fun `a 50MP photo is halved`() {
        assertEquals(2, calculateInSampleSize(8160, 6120)) // 49.9MP -> 12.5MP
    }

    @Test
    fun `a 200MP photo is quartered`() {
        assertEquals(4, calculateInSampleSize(16320, 12240)) // 199.8MP -> 12.5MP
    }

    @Test
    fun `sizes beyond Int range do not overflow`() {
        assertEquals(16, calculateInSampleSize(50_000, 50_000)) // 2.5GP -> 9.8MP
    }
}
