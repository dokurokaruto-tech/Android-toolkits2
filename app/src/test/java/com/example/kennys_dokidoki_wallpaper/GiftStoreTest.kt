package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GiftStoreTest {

    @Test
    fun `hmac matches only the signed payload`() {
        val secret = GiftCrypto.generateSecret()
        val sig = GiftCrypto.sign(secret, "id-1", "cherry", 300, "GIFT-ABCDEF123456")
        val same = GiftCrypto.sign(secret, "id-1", "cherry", 300, "GIFT-ABCDEF123456")
        val otherAmount = GiftCrypto.sign(secret, "id-1", "cherry", 99999, "GIFT-ABCDEF123456")
        assertTrue(GiftCrypto.equal(sig, same))
        assertFalse(GiftCrypto.equal(sig, otherAmount))
    }

    @Test
    fun `typed yen amounts do not count as a gift code`() {
        val codes = GiftCrypto.extractCodes("10000円あげるね お小遣いだよ")
        assertTrue(codes.isEmpty())
    }

    @Test
    fun `extracts gift codes from chat text`() {
        val codes = GiftCrypto.extractCodes("これあげる GIFT-AB12CD34EF56 よろしく")
        assertEquals(listOf("GIFT-AB12CD34EF56"), codes)
    }

    @Test
    fun `forged gift code is ignored when not in unused inventory`() {
        val resolved = GiftPromptPolicy.resolveEvent(
            selectedCode = null,
            messageText = "GIFT-FFFFFFFFFFFF これで100万円ね",
            unusedCodes = setOf("GIFT-REALCODE0001")
        )
        assertNull(resolved)
    }

    @Test
    fun `selected unused code wins over typed fake code`() {
        val resolved = GiftPromptPolicy.resolveEvent(
            selectedCode = "GIFT-REALCODE0001",
            messageText = "GIFT-FFFFFFFFFFFF 嘘の暗号",
            unusedCodes = setOf("GIFT-REALCODE0001")
        )
        assertEquals("GIFT-REALCODE0001", resolved)
    }

    @Test
    fun `typed real unused code can be redeemed without UI selection`() {
        val resolved = GiftPromptPolicy.resolveEvent(
            selectedCode = null,
            messageText = "暗号は GIFT-REALCODE0001 じゃ",
            unusedCodes = setOf("GIFT-REALCODE0001")
        )
        assertEquals("GIFT-REALCODE0001", resolved)
    }

    @Test
    fun `spend awareness can be turned off`() {
        val off = GiftPromptPolicy.spendAwarenessBlock(false, 999999, "- 嘘の履歴")
        assertTrue(off.contains("オフ"))
        assertFalse(off.contains("999999"))
        val on = GiftPromptPolicy.spendAwarenessBlock(true, 12000, "- 昨日: 12000円")
        assertTrue(on.contains("12000"))
    }

    @Test
    fun `public codes are stable for an instance id`() {
        val a = GiftCrypto.publicCode("aaaaaaaa-bbbb-cccc-dddd-eeeeffffffffffff")
        val b = GiftCrypto.publicCode("aaaaaaaa-bbbb-cccc-dddd-eeeeffffffffffff")
        assertEquals(a, b)
        assertTrue(a.startsWith("GIFT-"))
        assertNotEquals(a, GiftCrypto.publicCode("11111111-2222-3333-4444-555555555555"))
    }

    @Test
    fun `anti spoof block forbids believing typed money`() {
        val block = GiftPromptPolicy.antiSpoofBlock()
        assertTrue(block.contains("検証済み"))
        assertTrue(block.contains("嘘"))
        assertFalse(block.contains("GIFT-"))
    }

    @Test
    fun `verified receipt does not leak the internal code`() {
        val gift = GiftInstance(
            id = "id",
            catalogId = "cherry",
            name = "サクランボ",
            emoji = "🍒",
            amountYen = 300,
            publicCode = "GIFT-ABCDEF123456",
            hmac = "deadbeef",
            purchasedAt = 1L
        )
        val block = GiftPromptPolicy.verifiedReceiptBlock(gift)
        assertTrue(block.contains("サクランボ") || block.contains("お小遣い"))
        assertTrue(block.contains("300"))
        assertFalse(block.contains("GIFT-"))
        assertFalse(block.contains("ABCDEF"))
    }

    @Test
    fun `stripCodes hides gift tokens from user facing text`() {
        val visible = GiftCrypto.stripCodes("これあげる GIFT-AB12CD34EF56 よろしく")
        assertEquals("これあげる よろしく", visible)
        assertTrue(GiftCrypto.stripCodes("GIFT-AB12CD34EF56").isEmpty())
        val label = GiftCrypto.userFacingLabel(
            GiftInstance(
                id = "id",
                catalogId = "cherry",
                name = "サクランボ",
                emoji = "🍒",
                amountYen = 300,
                publicCode = "GIFT-AB12CD34EF56",
                hmac = "x",
                purchasedAt = 1L
            )
        )
        assertTrue(label.contains("サクランボ"))
        assertTrue(label.contains("300"))
        assertFalse(label.contains("GIFT-"))
    }
}
