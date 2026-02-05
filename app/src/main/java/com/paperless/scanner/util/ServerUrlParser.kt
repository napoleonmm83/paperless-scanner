package com.paperless.scanner.util

import androidx.annotation.StringRes
import com.paperless.scanner.R
import java.util.Locale

/**
 * Server URL Parser - Comprehensive URL parsing and validation for Paperless server URLs.
 *
 * Features:
 * - IPv4 and IPv6 address support
 * - Port validation (1-65535)
 * - Whitespace removal (newlines, tabs, spaces)
 * - Protocol stripping and normalization
 * - Domain lowercase normalization
 *
 * Supported formats:
 * - paperless.example.com
 * - paperless.example.com:8000
 * - https://paperless.example.com
 * - http://paperless.example.com:8000/api/
 * - 192.168.1.100:8000
 * - [::1]:8000
 * - [2001:db8::1]
 * - https://[::1]:8000/
 */
object ServerUrlParser {

    /**
     * Result of URL parsing operation.
     */
    sealed class ParseResult {
        /**
         * Successfully parsed URL.
         * @param host The hostname/IP (lowercase for domains)
         * @param port The port number, or null if not specified
         * @param isIpv6 Whether this is an IPv6 address
         */
        data class Success(
            val host: String,
            val port: Int?,
            val isIpv6: Boolean = false
        ) : ParseResult() {
            /**
             * Returns host with port for URL construction.
             * IPv6 addresses are wrapped in brackets.
             */
            fun toHostString(): String {
                val hostPart = if (isIpv6) "[$host]" else host
                return if (port != null) "$hostPart:$port" else hostPart
            }
        }

        /**
         * Parsing failed with specific error.
         * @param messageResId String resource ID for the error message.
         *                     Caller must resolve using context.getString().
         */
        data class Error(@StringRes val messageResId: Int) : ParseResult()
    }

    // Valid port range
    private const val MIN_PORT = 1
    private const val MAX_PORT = 65535

    // IPv6 pattern: [address] or [address]:port
    private val IPV6_BRACKET_PATTERN = Regex("""\[([0-9a-fA-F:]+)\](?::(\d+))?""")

    // IPv4 pattern with optional port
    private val IPV4_WITH_PORT_PATTERN = Regex("""(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})(?::(\d+))?""")

    // Domain pattern with optional port (hostname:port)
    private val DOMAIN_WITH_PORT_PATTERN = Regex("""([a-zA-Z0-9][-a-zA-Z0-9.]*[a-zA-Z0-9])(?::(\d+))?""")

    /**
     * Parses and validates a server URL input.
     *
     * @param input Raw user input (may contain protocol, path, whitespace)
     * @return ParseResult with validated host and port, or error
     */
    fun parse(input: String): ParseResult {
        // Step 1: Remove ALL whitespace (newlines, tabs, spaces)
        val cleaned = input.replace(Regex("\\s+"), "")

        if (cleaned.isBlank()) {
            return ParseResult.Error(R.string.error_server_address_missing)
        }

        // Step 2: Remove protocol prefix
        val withoutProtocol = cleaned
            .removePrefix("https://")
            .removePrefix("http://")

        // Step 3: Remove path (everything after first / that's not part of IPv6)
        val hostPortPart = extractHostPort(withoutProtocol)

        if (hostPortPart.isBlank()) {
            return ParseResult.Error(R.string.error_invalid_server_address)
        }

        // Step 4: Parse host and port based on format
        return when {
            // IPv6 with brackets: [::1] or [::1]:8000
            hostPortPart.startsWith("[") -> parseIpv6(hostPortPart)

            // IPv4: 192.168.1.1 or 192.168.1.1:8000
            IPV4_WITH_PORT_PATTERN.matches(hostPortPart) -> parseIpv4(hostPortPart)

            // Domain name: example.com or example.com:8000
            else -> parseDomain(hostPortPart)
        }
    }

    /**
     * Extracts the host:port part from a URL, handling IPv6 brackets correctly.
     */
    private fun extractHostPort(input: String): String {
        // For IPv6, we need to find the closing bracket first
        if (input.startsWith("[")) {
            val closeBracket = input.indexOf(']')
            if (closeBracket == -1) {
                // Invalid IPv6 - missing closing bracket
                return input
            }
            // Get everything up to and including port (if any)
            val afterBracket = input.substring(closeBracket + 1)
            val portPart = if (afterBracket.startsWith(":")) {
                val slashPos = afterBracket.indexOf('/')
                if (slashPos != -1) afterBracket.substring(0, slashPos) else afterBracket
            } else {
                ""
            }
            return input.substring(0, closeBracket + 1) + portPart
        }

        // For non-IPv6, take everything before first slash
        val slashPos = input.indexOf('/')
        return if (slashPos != -1) input.substring(0, slashPos) else input
    }

    /**
     * Parses IPv6 address in bracket notation.
     */
    private fun parseIpv6(input: String): ParseResult {
        val match = IPV6_BRACKET_PATTERN.matchEntire(input)
            ?: return ParseResult.Error(R.string.error_invalid_ipv6_format)

        val address = match.groupValues[1]
        val portStr = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }

        // Validate IPv6 address
        if (!isValidIpv6(address)) {
            return ParseResult.Error(R.string.error_invalid_ipv6_address)
        }

        // Validate port if present
        val port = portStr?.let { validatePort(it) }
        if (portStr != null && port == null) {
            return ParseResult.Error(R.string.error_invalid_port)
        }

        return ParseResult.Success(
            host = address,
            port = port,
            isIpv6 = true
        )
    }

    /**
     * Parses IPv4 address with optional port.
     */
    private fun parseIpv4(input: String): ParseResult {
        val match = IPV4_WITH_PORT_PATTERN.matchEntire(input)
            ?: return ParseResult.Error(R.string.error_invalid_ipv4_format)

        val address = match.groupValues[1]
        val portStr = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }

        // Validate IPv4 octets
        if (!isValidIpv4(address)) {
            return ParseResult.Error(R.string.error_invalid_ipv4_address)
        }

        // Validate port if present
        val port = portStr?.let { validatePort(it) }
        if (portStr != null && port == null) {
            return ParseResult.Error(R.string.error_invalid_port)
        }

        return ParseResult.Success(
            host = address,
            port = port,
            isIpv6 = false
        )
    }

    /**
     * Parses domain name with optional port.
     */
    private fun parseDomain(input: String): ParseResult {
        // Check for empty port (domain:)
        if (input.endsWith(":")) {
            return ParseResult.Error(R.string.error_port_missing)
        }

        // Check for invalid characters
        if (input.contains(" ") || input.contains("\t") || input.contains("\n")) {
            return ParseResult.Error(R.string.error_invalid_characters)
        }

        // Split domain and port
        val colonIndex = input.lastIndexOf(':')
        val (domain, portStr) = if (colonIndex != -1) {
            input.substring(0, colonIndex) to input.substring(colonIndex + 1)
        } else {
            input to null
        }

        // Validate domain
        if (domain.isBlank() || domain.length < 1) {
            return ParseResult.Error(R.string.error_address_too_short)
        }

        // Check for invalid port (non-numeric)
        if (portStr != null && !portStr.all { it.isDigit() }) {
            return ParseResult.Error(R.string.error_port_not_number)
        }

        // Validate port if present
        val port = portStr?.let { validatePort(it) }
        if (portStr != null && port == null) {
            return ParseResult.Error(R.string.error_invalid_port)
        }

        // Normalize domain to lowercase
        val normalizedDomain = domain.lowercase(Locale.ROOT)

        return ParseResult.Success(
            host = normalizedDomain,
            port = port,
            isIpv6 = false
        )
    }

    /**
     * Validates and returns port number, or null if invalid.
     */
    private fun validatePort(portStr: String): Int? {
        return try {
            val port = portStr.toInt()
            if (port in MIN_PORT..MAX_PORT) port else null
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * Validates IPv6 address format.
     */
    private fun isValidIpv6(address: String): Boolean {
        return try {
            // Use Java's built-in validation
            java.net.InetAddress.getByName(address) is java.net.Inet6Address
        } catch (e: Exception) {
            // Simple validation fallback
            address.contains(':') && address.matches(Regex("[0-9a-fA-F:]+"))
        }
    }

    /**
     * Validates IPv4 address octets are in valid range (0-255).
     */
    private fun isValidIpv4(address: String): Boolean {
        val octets = address.split('.')
        if (octets.size != 4) return false
        return octets.all { octet ->
            try {
                val value = octet.toInt()
                value in 0..255
            } catch (e: NumberFormatException) {
                false
            }
        }
    }
}
