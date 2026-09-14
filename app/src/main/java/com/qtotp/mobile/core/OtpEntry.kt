package com.qtotp.mobile.core

import java.util.UUID

/**
 * One authenticator token, matching the desktop app's entry shape field for
 * field so a vault written here still opens there.
 */
data class OtpEntry(
    val issuer: String = "",
    val account: String = "",
    val secret: String = "",
    val digits: Int = Totp.DEFAULT_DIGITS,
    val period: Int = Totp.DEFAULT_PERIOD,
    val algorithm: String = Totp.DEFAULT_ALGORITHM,
    val notes: String = "",
    val id: String = newId(),
    val createdAt: Double = System.currentTimeMillis() / 1000.0,
) {

    /** Human label, as the desktop app renders it. */
    val label: String
        get() = if (issuer.isNotEmpty() && account.isNotEmpty()) {
            "$issuer — $account"
        } else {
            issuer.ifEmpty { account }
        }

    fun code(atSeconds: Double): String =
        Totp.totp(secret, atSeconds, period = period, digits = digits, algorithm = algorithm)

    fun remaining(atSeconds: Double): Double = Totp.remainingSeconds(period, atSeconds)

    fun toUri(): String = OtpAuthUri.build(
        secret = secret,
        issuer = issuer,
        account = account,
        digits = digits,
        period = period,
        algorithm = algorithm,
    )

    companion object {
        fun newId(): String = UUID.randomUUID().toString().replace("-", "")

        /**
         * Validate and canonicalize the fields the desktop app validates on
         * load, so a hand-edited or foreign vault fails loudly here too.
         */
        fun create(
            issuer: String = "",
            account: String = "",
            secret: String = "",
            digits: Int = Totp.DEFAULT_DIGITS,
            period: Int = Totp.DEFAULT_PERIOD,
            algorithm: String = Totp.DEFAULT_ALGORITHM,
            notes: String = "",
            id: String = newId(),
            createdAt: Double = System.currentTimeMillis() / 1000.0,
        ): OtpEntry {
            val cleanIssuer = issuer.trim()
            val cleanAccount = account.trim()
            if (digits !in Totp.ALLOWED_DIGITS.toList()) {
                throw IllegalArgumentException("digits must be one of 6, 7, 8")
            }
            if (period <= 0) throw IllegalArgumentException("period must be positive")
            if (cleanIssuer.isEmpty() && cleanAccount.isEmpty()) {
                throw IllegalArgumentException("an entry needs an issuer or an account name")
            }
            return OtpEntry(
                issuer = cleanIssuer,
                account = cleanAccount,
                secret = Totp.normalizeSecret(secret),
                digits = digits,
                period = period,
                algorithm = Totp.normalizeAlgorithm(algorithm),
                notes = notes,
                id = id.ifEmpty { newId() },
                createdAt = createdAt,
            )
        }
    }
}
