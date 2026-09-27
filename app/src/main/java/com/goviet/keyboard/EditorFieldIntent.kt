package com.goviet.keyboard

import android.text.InputType
import java.text.Normalizer
import java.util.Locale

/**
 * What the focused editor field wants from the keyboard.
 *
 * Two callers need this answer and they used to each carry their own copy of
 * the guess: the service deciding between the letter panel and the Tpad, and
 * the root view deciding whether the Tpad offers a paste-code key. They could
 * disagree — the panel said letters while the pad said one-time code — so the
 * guess lives here once, with tests, and both ask.
 *
 * The guessing is deliberately narrow. Matching the bare word "code" in a hint
 * put the numeric pad in front of a promo code, a zip code and a country code,
 * three fields where numbers are the wrong keyboard entirely. An OTP names
 * itself as one: the acronym, "pin", "passcode", or "code" with a word that
 * says the code is for verifying the person rather than labelling the thing.
 */
object EditorFieldIntent {

    /**
     * True when [inputType] asks for digits: a number, phone or date field.
     *
     * This is the field declaring its own type rather than a guess from the
     * hint, so it is trusted as it stands.
     */
    fun isNumericInput(inputType: Int): Boolean {
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> true
            else -> false
        }
    }

    /**
     * True for a number field typed as a password, which is how most one-time
     * codes arrive: `TYPE_CLASS_NUMBER` + `TYPE_NUMBER_VARIATION_PASSWORD`.
     */
    fun isNumericPassword(inputType: Int): Boolean {
        return (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER &&
            (inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    /**
     * True when [hint] or [fieldName] names a one-time code.
     *
     * Matched word by word, never as substrings. "Mã OTP", "verification code",
     * `edit_otp_code` and `et_pin` are one-time codes; "promo code", "postal
     * code", "country code" and a hint that merely says "code" are not, and
     * putting the numeric pad in front of those takes away the keyboard the
     * field asked for.
     */
    fun isOneTimeCode(hint: CharSequence?, fieldName: CharSequence?): Boolean {
        return mentionsOneTimeCode(hint) || mentionsOneTimeCode(fieldName)
    }

    private fun mentionsOneTimeCode(text: CharSequence?): Boolean {
        val words = wordsOf(text)
        if (words.isEmpty()) return false

        // Stands on its own: the acronym, and the two words these fields are
        // named after without anything else.
        if (words.any { it in OWN_NAMES }) return true

        // "postal code" and its relatives come before the qualifiers, because a
        // hint can carry both ("verify your zip code") and the classification
        // wins.
        if (words.any { it in NOT_A_ONE_TIME_CODE_WORDS }) return false
        val phrases = phraseSet(words)
        if (phrases.any { it in NOT_A_ONE_TIME_CODE_PHRASES }) return false
        if (phrases.any { it in ONE_TIME_CODE_PHRASES }) return true

        // "Enter the code we sent you": no qualifier, but the code is on its
        // way to the user, which is the situation itself. The word for code is
        // "code" in English and "mã" in Vietnamese, which is "ma" once the
        // accents are folded away.
        val namesTheCode = words.contains("code") || words.contains("ma")
        if (!namesTheCode) return false
        return words.any { it in ONE_TIME_CODE_CONTEXT }
    }

    /**
     * Runs of two and three adjacent words, for the phrases above. Three
     * because "one time password" is the most common English hint there is and
     * matching it as two words would match "time password" or "one time".
     */
    private fun phraseSet(words: List<String>): Set<String> {
        if (words.size < 2) return emptySet()
        val phrases = HashSet<String>(words.size * 2)
        for (index in 0 until words.size - 1) {
            phrases.add(words[index] + " " + words[index + 1])
            if (index < words.size - 2) {
                phrases.add(words[index] + " " + words[index + 1] + " " + words[index + 2])
            }
        }
        return phrases
    }

    /**
     * Splits [text] into comparable words: accents folded away, lowercased,
     * split on anything that is not a letter or a digit, and split at camelCase
     * humps so a Java field name reads the same as a sentence. Folding the
     * accents is what lets one rule cover "Mã xác thực" and "Ma xac thuc".
     */
    private fun wordsOf(text: CharSequence?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val folded = Normalizer.normalize(text.toString(), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
        val spaced = StringBuilder(folded.length + 8)
        var previousWasLower = false
        for (character in folded) {
            if (Character.isLetterOrDigit(character)) {
                // "verificationCode" -> "verification code"
                if (Character.isUpperCase(character) && previousWasLower) spaced.append(' ')
                spaced.append(Character.toLowerCase(character))
                previousWasLower = Character.isLowerCase(character)
            } else {
                spaced.append(' ')
                previousWasLower = false
            }
        }
        return spaced.toString()
            .split(' ')
            .filter { it.isNotEmpty() }
            .map { it.lowercase(Locale.ROOT) }
    }

    private val DIACRITICS = "\\p{M}+".toRegex()

    /** Words that name a one-time code on their own. */
    private val OWN_NAMES = setOf("otp", "pin", "passcode", "pincode", "2fa", "totp", "hotp")

    /**
     * "code" preceded by a word that makes it a code for verifying the person.
     */
    private val ONE_TIME_CODE_PHRASES = setOf(
        "verification code", "verify code", "verifying code", "security code",
        "secure code", "confirm code", "confirmation code", "one time code",
        "2fa code", "access code", "login code", "signin code",
        "activation code", "activate code", "otp code", "pin code",
        "one time password", "onetime password", "single use code",
        "single use password", "one time pin", "security pin", "one time otp",
        "ma xac thuc", "xac thuc", "ma xac nhan", "xac nhan", "ma bao mat",
        "bao mat", "ma bao ve", "ma kich hoat", "kich hoat", "ma truy cap",
        "tru cap", "ma one time"
    )

    /**
     * Words that mean the code is a label on something, so the field is not a
     * one-time code field even if it also mentions verifying.
     */
    private val NOT_A_ONE_TIME_CODE_WORDS = setOf(
        "barcode", "qrcode", "zipcode", "postcode", "mabuuchinh", "skucode"
    )

    /** The same, as two-word phrases. */
    private val NOT_A_ONE_TIME_CODE_PHRASES = setOf(
        "postal code", "post code", "zip code", "bar code", "qr code",
        "country code", "area code", "product code", "item code", "sku code",
        "promo code", "promotion code", "coupon code", "gift code",
        "referral code", "invite code", "discount code", "voucher code",
        "imei code", "serial code", "error code", "status code", "http code",
        "ma buu", "ma buu chinh", "ma vach"
    )

    /**
     * Evidence that a code is on its way to the user, for hints that say
     * nothing else useful: "enter the code we sent you", "Mã gửi qua SMS".
     */
    private val ONE_TIME_CODE_CONTEXT = setOf(
        "sent", "sms", "texted", "message", "inbox", "receive", "received",
        "gui", "nhan", "dien thoai", "email"
    )
}
