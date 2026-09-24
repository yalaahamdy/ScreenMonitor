package com.example.screenmonitor

import com.example.screenmonitor.data.SecurityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SecurityManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var securityManager: SecurityManager

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        securityManager = SecurityManager(context = null, prefs = fakePrefs)
    }

    @Test
    fun initialState_isPinSet_isFalse() {
        assertFalse(securityManager.isPinSet())
    }

    @Test
    fun setPin_isPinSet_isTrue() {
        securityManager.setPin("1234")
        assertTrue(securityManager.isPinSet())
    }

    @Test
    fun verifyPin_correctPin_returnsTrue() {
        securityManager.setPin("4321")
        val result = securityManager.verifyPin("4321")
        assertTrue(result)
        assertEquals(0, securityManager.getFailedAttempts())
    }

    @Test
    fun verifyPin_wrongPin_returnsFalseAndIncrementsAttempts() {
        securityManager.setPin("1234")
        val result = securityManager.verifyPin("0000")
        assertFalse(result)
        assertEquals(1, securityManager.getFailedAttempts())
    }

    @Test
    fun verifyPin_fiveWrongAttempts_causesLockout() {
        securityManager.setPin("1234")
        repeat(5) {
            securityManager.verifyPin("0000")
        }
        assertEquals(5, securityManager.getFailedAttempts())
        assertTrue(securityManager.getRemainingLockoutSeconds() > 0)

        // Even correct PIN is blocked during lockout
        val resultWithCorrectPin = securityManager.verifyPin("1234")
        assertFalse(resultWithCorrectPin)
    }

    @Test
    fun verifyPin_correctPinAfterFailedAttempts_resetsCount() {
        securityManager.setPin("1234")
        securityManager.verifyPin("9999")
        securityManager.verifyPin("8888")
        assertEquals(2, securityManager.getFailedAttempts())

        val success = securityManager.verifyPin("1234")
        assertTrue(success)
        assertEquals(0, securityManager.getFailedAttempts())
    }
}
