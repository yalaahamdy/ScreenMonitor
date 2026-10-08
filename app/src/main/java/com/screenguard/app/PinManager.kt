package com.screenguard.app

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/** Salted SHA-256 PIN storage. The clear PIN is never persisted. */
object PinManager {

    private const val FILE = "screenguard_security"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_SALT = "pin_salt"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hasPin(ctx: Context): Boolean = sp(ctx).contains(KEY_HASH)

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun setPin(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        sp(ctx).edit()
            .putString(KEY_SALT, salt)
            .putString(KEY_HASH, sha256(salt + pin))
            .apply()
    }

    fun verify(ctx: Context, pin: String): Boolean {
        val salt = sp(ctx).getString(KEY_SALT, null) ?: return false
        val hash = sp(ctx).getString(KEY_HASH, null) ?: return false
        return MessageDigest.isEqual(hash.toByteArray(), sha256(salt + pin).toByteArray())
    }

    fun changePin(ctx: Context, currentPin: String, newPin: String): Boolean =
        if (verify(ctx, currentPin)) {
            setPin(ctx, newPin)
            true
        } else false
}
