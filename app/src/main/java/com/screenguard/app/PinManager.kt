package com.screenguard.app

import android.content.Context
import android.content.SharedPreferences
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Unified security manager for the merged application.
 *
 * Combines the original ScreenGuard PIN gate (salted SHA-256, session lock,
 * auto-lock grace on background) with the full advanced-protection feature
 * set ported from the usage-controller app: security question recovery,
 * brute-force lockout, configurable relock timeout, feature toggles
 * (app lock / anti-tamper / anti-uninstall / safe-mode protection),
 * heartbeat with clock-rollback detection and safe-mode violation records.
 *
 * Upgrade compatibility: legacy ScreenGuard PINs were hashed as
 * sha256(salt + pin) while the controller app used sha256("salt:pin").
 * Verification accepts both schemes; newly saved PINs use the legacy
 * ScreenGuard scheme so existing users keep working after the update.
 */
object PinManager {

    private const val FILE = "screenguard_security"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_SALT = "pin_salt"
    private const val KEY_IS_SETUP = "is_pin_setup"
    private const val KEY_SECURITY_QUESTION = "security_question"
    private const val KEY_SECURITY_ANSWER_HASH = "security_answer_hash"
    private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
    private const val KEY_LOCKOUT_UNTIL = "lockout_until"
    private const val KEY_LOCK_TIMEOUT_SECONDS = "lock_timeout_seconds"
    private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
    private const val KEY_ANTI_TAMPER_ENABLED = "anti_tamper_enabled"
    private const val KEY_ANTI_UNINSTALL_ENABLED = "anti_uninstall_enabled"
    private const val KEY_SAFE_MODE_PROTECTION_ENABLED = "safe_mode_protection_enabled"
    private const val KEY_SAFE_MODE_VIOLATION_DETECTED = "safe_mode_violation_detected"
    private const val KEY_VIOLATION_MESSAGE = "safe_mode_violation_message"
    private const val KEY_LAST_HEARTBEAT_TIMESTAMP = "last_heartbeat_timestamp"
    private const val KEY_LAST_BOOT_TIME = "last_boot_time"

    /** Legacy fixed grace kept for API compatibility; the effective timeout is configurable. */
    const val AUTO_LOCK_GRACE_MS = 15_000L
    const val DEFAULT_LOCK_TIMEOUT_SECONDS = 15

    private const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 5
    private const val LOCKOUT_DURATION_MS = 30_000L

    @Volatile
    private var sessionUnlocked: Boolean = false

    @Volatile
    private var backgroundTimestamp: Long = 0L

    // ---------------------------------------------------------------------
    // Session management (ScreenGuard semantics with configurable timeout)
    // ---------------------------------------------------------------------

    fun isSessionUnlocked(): Boolean {
        if (!sessionUnlocked) return false
        if (backgroundTimestamp > 0L) {
            val elapsed = System.currentTimeMillis() - backgroundTimestamp
            if (elapsed > lockTimeoutMillis()) {
                sessionUnlocked = false
                backgroundTimestamp = 0L
                return false
            }
        }
        return true
    }

    fun unlockSession() {
        sessionUnlocked = true
        backgroundTimestamp = 0L
    }

    fun lockSession() {
        sessionUnlocked = false
        backgroundTimestamp = 0L
    }

    fun onAppEnteredBackground() {
        backgroundTimestamp = System.currentTimeMillis()
    }

    fun onAppEnteredForeground() {
        if (backgroundTimestamp > 0L) {
            val elapsed = System.currentTimeMillis() - backgroundTimestamp
            if (elapsed > lockTimeoutMillis()) {
                sessionUnlocked = false
            }
            backgroundTimestamp = 0L
        }
    }

    private fun lockTimeoutMillis(): Long {
        val seconds = Prefs.getLockTimeoutSeconds()
        return if (seconds <= 0) 0L else seconds * 1000L
    }

    // ---------------------------------------------------------------------
    // PIN lifecycle
    // ---------------------------------------------------------------------

    private fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hasPin(ctx: Context): Boolean = sp(ctx).contains(KEY_HASH)

    /** Whether the full setup (PIN + security question) was completed. */
    fun isPinConfigured(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_IS_SETUP, false) && sp(ctx).getString(KEY_HASH, null) != null

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * Save the PIN together with the recovery question for the first time.
     * Returns false when validation fails.
     */
    fun setupSecurity(ctx: Context, pin: String, question: String, answer: String): Boolean {
        if (pin.length < 4 || question.isBlank() || answer.isBlank()) return false
        val salt = newSalt()
        val answerHash = sha256(salt + normalizeAnswer(answer))
        sp(ctx).edit()
            .putString(KEY_SALT, salt)
            .putString(KEY_HASH, sha256(salt + pin))
            .putString(KEY_SECURITY_QUESTION, question.trim())
            .putString(KEY_SECURITY_ANSWER_HASH, answerHash)
            .putBoolean(KEY_IS_SETUP, true)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
        unlockSession()
        return true
    }

    /** Simple raw PIN comparison (no lockout side effects). Used by in-app dialogs. */
    fun verify(ctx: Context, pin: String): Boolean {
        val salt = sp(ctx).getString(KEY_SALT, null) ?: return false
        val hash = sp(ctx).getString(KEY_HASH, null) ?: return false
        val candidateLegacy = sha256(salt + pin)
        if (MessageDigest.isEqual(hash.toByteArray(), candidateLegacy.toByteArray())) return true
        // Accept the alternate scheme (hashed as salt:pin) for imported setups
        val candidateAlternate = sha256("$salt:$pin")
        return MessageDigest.isEqual(hash.toByteArray(), candidateAlternate.toByteArray())
    }

    /**
     * PIN verification with brute-force protection. Used by the lock screen.
     * Records failed attempts and enforces a temporary lockout.
     */
    fun verifyWithLockout(ctx: Context, pin: String): Boolean {
        if (getRemainingLockoutSeconds(ctx) > 0) return false
        val match = verify(ctx, pin)
        if (match) {
            resetFailedAttempts(ctx)
            unlockSession()
        } else {
            recordFailedAttempt(ctx)
        }
        return match
    }

    fun setPin(ctx: Context, pin: String) {
        val salt = sp(ctx).getString(KEY_SALT, null) ?: newSalt()
        sp(ctx).edit()
            .putString(KEY_SALT, salt)
            .putString(KEY_HASH, sha256(salt + pin))
            .putBoolean(KEY_IS_SETUP, true)
            .apply()
        unlockSession()
    }

    fun changePin(ctx: Context, currentPin: String, newPin: String): Boolean =
        if (verify(ctx, currentPin) && newPin.length >= 4) {
            setPin(ctx, newPin)
            true
        } else false

    // ---------------------------------------------------------------------
    // Security question (recovery)
    // ---------------------------------------------------------------------

    fun getSecurityQuestion(ctx: Context): String =
        sp(ctx).getString(KEY_SECURITY_QUESTION, "") ?: ""

    fun hasSecurityQuestion(ctx: Context): Boolean =
        sp(ctx).getString(KEY_SECURITY_ANSWER_HASH, null) != null

    fun verifySecurityAnswer(ctx: Context, enteredAnswer: String): Boolean {
        val salt = sp(ctx).getString(KEY_SALT, null) ?: return false
        val saved = sp(ctx).getString(KEY_SECURITY_ANSWER_HASH, null) ?: return false
        val candidate = sha256(salt + normalizeAnswer(enteredAnswer))
        return MessageDigest.isEqual(saved.toByteArray(), candidate.toByteArray())
    }

    /** Reset the PIN after a correct answer to the security question. */
    fun resetPinWithAnswer(ctx: Context, enteredAnswer: String, newPin: String): Boolean {
        if (!verifySecurityAnswer(ctx, enteredAnswer)) return false
        if (newPin.length < 4) return false
        val salt = sp(ctx).getString(KEY_SALT, null) ?: newSalt()
        sp(ctx).edit()
            .putString(KEY_SALT, salt)
            .putString(KEY_HASH, sha256(salt + newPin))
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .putBoolean(KEY_IS_SETUP, true)
            .apply()
        unlockSession()
        return true
    }

    /** Update the recovery question after PIN confirmation. */
    fun updateSecurityQuestion(ctx: Context, pin: String, newQuestion: String, newAnswer: String): Boolean {
        if (!verify(ctx, pin)) return false
        if (newQuestion.isBlank() || newAnswer.isBlank()) return false
        val salt = sp(ctx).getString(KEY_SALT, null) ?: return false
        sp(ctx).edit()
            .putString(KEY_SECURITY_QUESTION, newQuestion.trim())
            .putString(KEY_SECURITY_ANSWER_HASH, sha256(salt + normalizeAnswer(newAnswer)))
            .apply()
        return true
    }

    // ---------------------------------------------------------------------
    // Lockout (brute-force protection)
    // ---------------------------------------------------------------------

    fun getRemainingLockoutSeconds(ctx: Context): Long {
        val until = sp(ctx).getLong(KEY_LOCKOUT_UNTIL, 0L)
        val diff = until - System.currentTimeMillis()
        return if (diff > 0) (diff / 1000) + 1 else 0L
    }

    private fun recordFailedAttempt(ctx: Context) {
        val current = sp(ctx).getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val editor = sp(ctx).edit().putInt(KEY_FAILED_ATTEMPTS, current)
        if (current >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
            editor.putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + LOCKOUT_DURATION_MS)
        }
        editor.apply()
    }

    private fun resetFailedAttempts(ctx: Context) {
        sp(ctx).edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
    }

    // ---------------------------------------------------------------------
    // Feature toggles
    // ---------------------------------------------------------------------

    fun isAppLockEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_APP_LOCK_ENABLED, true)

    fun setAppLockEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_APP_LOCK_ENABLED, enabled).apply()
        if (!enabled) {
            sessionUnlocked = true
        }
    }

    fun isAntiTamperEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ANTI_TAMPER_ENABLED, true)

    fun setAntiTamperEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_ANTI_TAMPER_ENABLED, enabled).apply()
    }

    fun isAntiUninstallEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ANTI_UNINSTALL_ENABLED, true)

    fun setAntiUninstallEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_ANTI_UNINSTALL_ENABLED, enabled).apply()
    }

    fun isSafeModeProtectionEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_SAFE_MODE_PROTECTION_ENABLED, true)

    fun setSafeModeProtectionEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_SAFE_MODE_PROTECTION_ENABLED, enabled).apply()
    }

    fun getLockTimeoutSeconds(ctx: Context): Int =
        sp(ctx).getInt(KEY_LOCK_TIMEOUT_SECONDS, DEFAULT_LOCK_TIMEOUT_SECONDS)

    fun setLockTimeoutSeconds(ctx: Context, seconds: Int) {
        sp(ctx).edit().putInt(KEY_LOCK_TIMEOUT_SECONDS, seconds.coerceIn(0, 3600)).apply()
    }

    /** Whether the whole app is currently locked (gate should be shown). */
    fun isAppLocked(ctx: Context): Boolean {
        if (!hasPin(ctx)) return false
        if (!isAppLockEnabled(ctx)) return false
        return !isSessionUnlocked()
    }

    // ---------------------------------------------------------------------
    // Heartbeat / boot audit / safe-mode violations
    // ---------------------------------------------------------------------

    fun recordHeartbeat(ctx: Context) {
        val now = System.currentTimeMillis()
        val last = getLastHeartbeatTimestamp(ctx)
        if (last > 0L && now < (last - 60_000L) && isSafeModeProtectionEnabled(ctx)) {
            recordSafeModeViolation(ctx, Str.get(R.string.violation_clock_rollback))
        }
        sp(ctx).edit().putLong(KEY_LAST_HEARTBEAT_TIMESTAMP, now).apply()
    }

    fun getLastHeartbeatTimestamp(ctx: Context): Long = sp(ctx).getLong(KEY_LAST_HEARTBEAT_TIMESTAMP, 0L)

    fun recordBootTime(ctx: Context, bootTime: Long) {
        sp(ctx).edit().putLong(KEY_LAST_BOOT_TIME, bootTime).apply()
    }

    fun getLastBootTime(ctx: Context): Long = sp(ctx).getLong(KEY_LAST_BOOT_TIME, 0L)

    fun recordSafeModeViolation(ctx: Context, message: String) {
        sp(ctx).edit()
            .putBoolean(KEY_SAFE_MODE_VIOLATION_DETECTED, true)
            .putString(KEY_VIOLATION_MESSAGE, message)
            .apply()
        sessionUnlocked = false
    }

    fun isSafeModeViolationDetected(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_SAFE_MODE_VIOLATION_DETECTED, false)

    fun getSafeModeViolationMessage(ctx: Context): String? =
        sp(ctx).getString(KEY_VIOLATION_MESSAGE, null)

    fun clearSafeModeViolation(ctx: Context) {
        sp(ctx).edit()
            .putBoolean(KEY_SAFE_MODE_VIOLATION_DETECTED, false)
            .remove(KEY_VIOLATION_MESSAGE)
            .apply()
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun newSalt(): String =
        ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }

    private fun normalizeAnswer(answer: String): String =
        answer.trim().lowercase(Locale.getDefault())
}
