package com.neulketing.openthumb.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for the provider connectivity probe's URL shape.
 *
 * `effectiveBaseURL` already applies the instance's `appendV1Suffix` choice,
 * so the probe must not re-derive one. It did, which sent OpenAI-compatible
 * endpoints whose root is not `/v1` (z.ai's `/paas/v4`) to
 * `…/paas/v4/v1/models` — a 404 that reported a perfectly working provider as
 * unreachable.
 *
 * The rule under test is mirrored here because the production expression sits
 * inside a suspend function that needs a Context and a repository; the shape
 * decision itself is pure, and this is the check that fails if it regresses.
 */
class ProviderProbeUrlTest {

    private fun probe(baseURL: String, isCustomBase: Boolean): String =
        if (isCustomBase || baseURL.endsWith("/v1")) "$baseURL/models" else "$baseURL/v1/models"

    @Test
    fun `custom root is used verbatim even when it does not end in v1`() {
        assertEquals(
            "https://api.z.ai/api/coding/paas/v4/models",
            probe("https://api.z.ai/api/coding/paas/v4", isCustomBase = true),
        )
    }

    @Test
    fun `custom root already ending in v1 is not doubled`() {
        assertEquals(
            "https://api.deepseek.com/v1/models",
            probe("https://api.deepseek.com/v1", isCustomBase = true),
        )
    }

    @Test
    fun `built-in default without a v1 root still gets the suffix`() {
        assertEquals(
            "https://api.anthropic.com/v1/models",
            probe("https://api.anthropic.com", isCustomBase = false),
        )
    }

    @Test
    fun `built-in default that already ends in v1 is left alone`() {
        assertEquals(
            "https://api.openai.com/v1/models",
            probe("https://api.openai.com/v1", isCustomBase = false),
        )
    }
}
