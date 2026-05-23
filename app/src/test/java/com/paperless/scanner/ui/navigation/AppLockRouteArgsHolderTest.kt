package com.paperless.scanner.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLockRouteArgsHolderTest {

    private val holder = AppLockRouteArgsHolder()

    @Test
    fun `get returns null for an unset key`() {
        assertNull(holder.get("pageUris"))
    }

    @Test
    fun `put then get returns the stored value`() {
        holder.put("pageUris", "file://a|file://b")
        assertEquals("file://a|file://b", holder.get("pageUris"))
    }

    @Test
    fun `put null clears the entry`() {
        holder.put("pageUris", "file://a")
        holder.put("pageUris", null)
        assertNull(holder.get("pageUris"))
    }

    @Test
    fun `put empty string clears the entry`() {
        holder.put("pageUris", "file://a")
        holder.put("pageUris", "")
        assertNull(holder.get("pageUris"))
    }

    @Test
    fun `keys are independent`() {
        holder.put("pageUris", "p")
        holder.put("documentUris", "d")
        assertEquals("p", holder.get("pageUris"))
        assertEquals("d", holder.get("documentUris"))
    }

    @Test
    fun `put overwrites a previous value`() {
        holder.put("pageUris", "old")
        holder.put("pageUris", "new")
        assertEquals("new", holder.get("pageUris"))
    }
}
