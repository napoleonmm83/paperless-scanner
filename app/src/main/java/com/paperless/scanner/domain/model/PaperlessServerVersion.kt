package com.paperless.scanner.domain.model

import java.math.BigInteger

/** A confirmed server release. API negotiation versions are a separate value. */
data class PaperlessServerVersion private constructor(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: List<String> = emptyList()
) : Comparable<PaperlessServerVersion> {
    override fun toString(): String = "$major.$minor.$patch" +
        if (prerelease.isEmpty()) "" else "-${prerelease.joinToString(".")}"

    override fun compareTo(other: PaperlessServerVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1
        prerelease.zip(other.prerelease).forEach { (left, right) ->
            val leftNumeric = left.all { it in '0'..'9' }
            val rightNumeric = right.all { it in '0'..'9' }
            val comparison = when {
                leftNumeric && rightNumeric -> BigInteger(left).compareTo(BigInteger(right))
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val release = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$")

        fun parse(value: String?): PaperlessServerVersion? {
            val normalized = value?.trim()?.takeIf { it.length in 1..128 } ?: return null
            val match = release.matchEntire(normalized) ?: return null
            val prerelease = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (prerelease.any { it.startsWith("dev", ignoreCase = true) }) return null
            if (prerelease.any { it.all { char -> char in '0'..'9' } && it.length > 1 && it.startsWith('0') }) return null
            return PaperlessServerVersion(
                match.groupValues[1].toIntOrNull() ?: return null,
                match.groupValues[2].toIntOrNull() ?: return null,
                match.groupValues[3].toIntOrNull() ?: return null,
                prerelease
            )
        }
    }
}

data class ServerCapabilityState(
    val serverVersion: PaperlessServerVersion? = null,
    val displayVersion: String? = null,
    val apiVersion: Int? = null,
    val serverBase: String? = null
)
