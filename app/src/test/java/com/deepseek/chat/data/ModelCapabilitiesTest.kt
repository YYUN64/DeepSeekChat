package com.deepseek.chat.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilitiesTest {

    @Test
    fun `v4 pro does not support latest reminder or web tools`() {
        assertFalse(Models.supportsLatestReminder(Models.PRO))
        assertFalse(Models.supportsWebTools(Models.PRO))
        assertFalse(Models.supportsVision(Models.PRO))
    }

    @Test
    fun `flash supports latest reminder and web tools`() {
        assertTrue(Models.supportsLatestReminder(Models.FLASH))
        assertTrue(Models.supportsWebTools(Models.FLASH))
        assertTrue(Models.supportsVision(Models.FLASH))
    }
}
