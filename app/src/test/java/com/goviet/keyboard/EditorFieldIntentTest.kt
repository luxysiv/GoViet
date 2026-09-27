package com.goviet.keyboard

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the keyboard does about the field in front of it.
 *
 * The one-time code cases are the ones that were wrong: a bare "code" match put
 * the numeric pad in front of a promo code field, so these read as the
 * decisions rather than as a list of strings.
 */
class EditorFieldIntentTest {

    private val numberPassword =
        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    private val plainText = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
    private val phone = InputType.TYPE_CLASS_PHONE

    @Test
    fun `a field that declares digits gets the numeric pad`() {
        assertTrue(EditorFieldIntent.isNumericInput(InputType.TYPE_CLASS_NUMBER))
        assertTrue(EditorFieldIntent.isNumericInput(phone))
        assertTrue(EditorFieldIntent.isNumericInput(InputType.TYPE_CLASS_DATETIME))
        assertFalse(EditorFieldIntent.isNumericInput(plainText))
        assertFalse(EditorFieldIntent.isNumericInput(0))
    }

    @Test
    fun `digits as a password is how a code field usually arrives`() {
        assertTrue(EditorFieldIntent.isNumericPassword(numberPassword))
        assertFalse(EditorFieldIntent.isNumericPassword(InputType.TYPE_CLASS_NUMBER))
        assertFalse(EditorFieldIntent.isNumericPassword(plainText))
    }

    @Test
    fun `a field that says otp is a code field`() {
        assertTrue(EditorFieldIntent.isOneTimeCode("Mã OTP", null))
        assertTrue(EditorFieldIntent.isOneTimeCode("One-time password", null))
        assertTrue(EditorFieldIntent.isOneTimeCode(null, "et_otp"))
        assertTrue(EditorFieldIntent.isOneTimeCode(null, "edit_verification_code"))
        assertTrue(EditorFieldIntent.isOneTimeCode(null, "et_pin"))
        assertTrue(EditorFieldIntent.isOneTimeCode("Mã bảo mật", null))
        assertTrue(EditorFieldIntent.isOneTimeCode("Mã xác thực", null))
        assertTrue(EditorFieldIntent.isOneTimeCode("Verification code", null))
        assertTrue(EditorFieldIntent.isOneTimeCode("Security code", null))
        assertTrue(EditorFieldIntent.isOneTimeCode("Confirm code", null))
    }

    @Test
    fun `a code with no reason to be a code is not one`() {
        // This is the bug: a bare "code" substring put the numeric pad in front
        // of every one of these.
        assertFalse(EditorFieldIntent.isOneTimeCode("Code", null))
        assertFalse(EditorFieldIntent.isOneTimeCode("Enter code", null))
        assertFalse(EditorFieldIntent.isOneTimeCode("Product code", null))
        assertFalse(EditorFieldIntent.isOneTimeCode("Postal code", null))
        assertFalse(EditorFieldIntent.isOneTimeCode("Zip code", null))
        assertFalse(EditorFieldIntent.isOneTimeCode("Country code", null))
        assertFalse(EditorFieldIntent.isOneTimeCode("Promo code", null))
        assertFalse(EditorFieldIntent.isOneTimeCode("Coupon code", null))
        assertFalse(EditorFieldIntent.isOneTimeCode("Error code", null))
        assertFalse(EditorFieldIntent.isOneTimeCode("Mã bưu chính", null))
        assertFalse(EditorFieldIntent.isOneTimeCode(null, "txtZipCode"))
        assertFalse(EditorFieldIntent.isOneTimeCode(null, "ed_country_code"))
        assertFalse(EditorFieldIntent.isOneTimeCode(null, "et_promo_code"))
    }

    @Test
    fun `a classification beats a verification word in the same hint`() {
        // "verify your zip code" is still a zip code.
        assertFalse(EditorFieldIntent.isOneTimeCode("Verify your zip code", null))
        assertFalse(EditorFieldIntent.isOneTimeCode("Promo code, verified", null))
    }

    @Test
    fun `a code on its way to the user is a code even with no other words`() {
        assertTrue(EditorFieldIntent.isOneTimeCode("Enter the code we sent you", null))
        assertTrue(EditorFieldIntent.isOneTimeCode("Mã gửi qua SMS", null))
    }

    @Test
    fun `nothing to read is not a code field`() {
        assertFalse(EditorFieldIntent.isOneTimeCode(null, null))
        assertFalse(EditorFieldIntent.isOneTimeCode("", ""))
        assertFalse(EditorFieldIntent.isOneTimeCode("   ", "  _  "))
    }

    @Test
    fun `case and spacing do not decide it`() {
        assertTrue(EditorFieldIntent.isOneTimeCode("VERIFICATION CODE", null))
        assertTrue(EditorFieldIntent.isOneTimeCode("verification_code", "et_pin"))
        assertTrue(EditorFieldIntent.isOneTimeCode("  Mã   OTP  ", null))
        assertFalse(EditorFieldIntent.isOneTimeCode("PROMO CODE", null))
    }
}
