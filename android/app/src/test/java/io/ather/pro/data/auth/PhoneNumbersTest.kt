package io.ather.pro.data.auth

import org.junit.Assert.*
import org.junit.Test

class PhoneNumbersTest {
    @Test fun internationalPasteSelectsCountryAndRemovesCallingCode() {
        assertEquals(PhoneNumbers.Number("IN", "9123456789"), PhoneNumbers.normalize("+91 91234 56789", "GB"))
        assertEquals(PhoneNumbers.Number("GB", "7400123456"), PhoneNumbers.normalize("+44 7400 123456", "IN"))
    }

    @Test fun nationalTrunkPrefixIsNormalized() {
        assertEquals(PhoneNumbers.Number("GB", "7400123456"), PhoneNumbers.normalize("07400 123456", "GB"))
    }

    @Test fun shorterInternationalNumbersAreAccepted() {
        assertTrue(PhoneNumbers.canSubmit("771234567", "LK"))
        assertTrue(PhoneNumbers.canSubmit("81234567", "SG"))
        assertFalse(PhoneNumbers.canSubmit("912345678", "IN"))
    }

    @Test fun sharedCallingCodeKeepsSelectedCountry() {
        assertEquals("CA", PhoneNumbers.normalize("+1 416 555 0123", "CA")?.region)
    }

    @Test fun significantLeadingZeroIsPreserved() {
        assertEquals("0212345678", PhoneNumbers.normalize("+39 02 12345678", "IT")?.national)
    }

    @Test fun malformedOrUnsupportedNumbersAreRejected() {
        for (input in listOf("", "123", "+999123456789", "12345678901234567890", "+44 7400 123456 ext 12")) {
            assertFalse(input, PhoneNumbers.canSubmit(input, "IN"))
        }
        assertFalse(PhoneNumbers.canSubmit("0000000000", "IN"))
        assertTrue(PhoneNumbers.countries.any { it.region == "IN" && it.dialCode == 91 })
        assertTrue(PhoneNumbers.countries.any { it.region == "NP" && it.dialCode == 977 })
    }
}
