package com.stalkertv.app

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted-hash storage + brute-force lockout for the parental PIN.
 *
 * - [hash]/[verify] use PBKDF2-HMAC-SHA1 (API 21+) with a random per-secret salt, so the PIN is never
 *   stored in plaintext. [verify] still accepts a LEGACY plaintext value (pre-hash installs) so nobody is
 *   locked out during migration — callers re-hash on the first correct legacy entry.
 * - [lockedMs]/[recordFail]/[recordSuccess] add an escalating lockout after repeated wrong entries so the
 *   PIN can't be brute-forced through the UI.
 */
object Secret {
    private const val ITER = 10_000
    private const val PREF = "sec"

    fun hash(raw: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "v1:" + b64(salt) + ":" + b64(pbkdf2(raw, salt))
    }

    fun isHashed(stored: String) = stored.startsWith("v1:")

    fun verify(raw: String, stored: String): Boolean {
        if (stored.isEmpty()) return false
        if (!isHashed(stored)) return raw == stored // legacy plaintext (migrated on next set)
        val p = stored.split(":")
        return if (p.size != 3) false
        else try { constEq(pbkdf2(raw, ub64(p[1])), ub64(p[2])) } catch (_: Exception) { false }
    }

    private fun pbkdf2(raw: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(raw.toCharArray(), salt, ITER, 256)).encoded

    private fun constEq(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var r = 0; for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt()); return r == 0
    }
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun ub64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    // ---- brute-force lockout (per key) ----
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Milliseconds the [key] is currently locked out for (0 = not locked). */
    fun lockedMs(ctx: Context, key: String): Long =
        (p(ctx).getLong("$key.until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    fun recordFail(ctx: Context, key: String) {
        val fails = p(ctx).getInt("$key.fails", 0) + 1
        val e = p(ctx).edit().putInt("$key.fails", fails)
        if (fails >= 5) e.putLong("$key.until", System.currentTimeMillis() + minOf((fails - 4) * 30_000L, 300_000L))
        e.apply()
    }

    fun recordSuccess(ctx: Context, key: String) {
        p(ctx).edit().remove("$key.fails").remove("$key.until").apply()
    }
}
