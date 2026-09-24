package com.example.screenmonitor

import com.example.screenmonitor.data.SecurityEventType
import com.example.screenmonitor.data.SecurityLogManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SecurityLogManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var securityLogManager: SecurityLogManager

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        securityLogManager = SecurityLogManager(context = null, prefs = fakePrefs)
    }

    @Test
    fun initialState_hasNoEventsAndNoUnread() {
        assertTrue(securityLogManager.getEvents().isEmpty())
        assertFalse(securityLogManager.hasUnreadAlert())
    }

    @Test
    fun logEvent_storesEventAndSetsUnread() {
        securityLogManager.logEvent(
            SecurityEventType.PERMISSION_REVOKED,
            "سحب الإذن",
            "تم سحب إذن التقاط الشاشة أثناء المراقبة"
        )

        val events = securityLogManager.getEvents()
        assertEquals(1, events.size)
        assertEquals("سحب الإذن", events[0].title)
        assertEquals(SecurityEventType.PERMISSION_REVOKED, events[0].type)
        assertTrue(securityLogManager.hasUnreadAlert())
    }

    @Test
    fun markAlertsAsRead_clearsUnreadFlag() {
        securityLogManager.logEvent(
            SecurityEventType.PERMISSION_REVOKED,
            "تنبيه",
            "تفاصيل التنبيه"
        )
        assertTrue(securityLogManager.hasUnreadAlert())

        securityLogManager.markAlertsAsRead()
        assertFalse(securityLogManager.hasUnreadAlert())
        assertEquals(1, securityLogManager.getEvents().size)
    }

    @Test
    fun clearLogs_removesAllEventsAndResetsUnread() {
        securityLogManager.logEvent(
            SecurityEventType.PERMISSION_REVOKED,
            "تنبيه 1",
            "تفاصيل 1"
        )
        securityLogManager.logEvent(
            SecurityEventType.UNEXPECTED_SERVICE_STOP,
            "تنبيه 2",
            "تفاصيل 2"
        )
        assertEquals(2, securityLogManager.getEvents().size)

        securityLogManager.clearLogs()
        assertTrue(securityLogManager.getEvents().isEmpty())
        assertFalse(securityLogManager.hasUnreadAlert())
    }
}
