package com.example.screenmonitor

import com.example.screenmonitor.data.PreferencesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PreferencesManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var preferencesManager: PreferencesManager

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        preferencesManager = PreferencesManager(context = null, prefs = fakePrefs)
    }

    @Test
    fun defaultValues_areCorrect() {
        assertEquals(PreferencesManager.DEFAULT_INTERVAL_SECONDS, preferencesManager.captureIntervalSeconds)
        assertEquals(PreferencesManager.DEFAULT_RETENTION_HOURS, preferencesManager.retentionHours)
        assertTrue(preferencesManager.isAutoCleanEnabled)
        assertFalse(preferencesManager.isMonitoringActive)
    }

    @Test
    fun updateInterval_persistsCorrectly() {
        preferencesManager.captureIntervalSeconds = 300
        assertEquals(300, preferencesManager.captureIntervalSeconds)
    }

    @Test
    fun updateRetention_persistsCorrectly() {
        preferencesManager.retentionHours = 168
        assertEquals(168, preferencesManager.retentionHours)
    }

    @Test
    fun updateMonitoringState_persistsCorrectly() {
        preferencesManager.isMonitoringActive = true
        assertTrue(preferencesManager.isMonitoringActive)
    }

    @Test
    fun wasMonitoringBeforeReboot_persistsCorrectly() {
        assertFalse(preferencesManager.wasMonitoringBeforeReboot)
        preferencesManager.wasMonitoringBeforeReboot = true
        assertTrue(preferencesManager.wasMonitoringBeforeReboot)
        preferencesManager.wasMonitoringBeforeReboot = false
        assertFalse(preferencesManager.wasMonitoringBeforeReboot)
    }

    @Test
    fun discreetNotification_defaultsToTrue_andPersistsCorrectly() {
        assertTrue(preferencesManager.isDiscreetNotificationEnabled)
        preferencesManager.isDiscreetNotificationEnabled = false
        assertFalse(preferencesManager.isDiscreetNotificationEnabled)
        preferencesManager.isDiscreetNotificationEnabled = true
        assertTrue(preferencesManager.isDiscreetNotificationEnabled)
    }
}
