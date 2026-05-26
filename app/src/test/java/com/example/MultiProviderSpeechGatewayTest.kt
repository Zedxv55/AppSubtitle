package com.example

import com.example.api.MultiProviderSpeechGateway
import org.junit.Assert.*
import org.junit.Test

class MultiProviderSpeechGatewayTest {

    @Test
    fun testCacheClearingFlow() {
        // Verify cache operations can be successfully reset
        try {
            MultiProviderSpeechGateway.clearCache()
        } catch (e: Exception) {
            fail("Cache evictAll failed: ${e.message}")
        }
    }
}
