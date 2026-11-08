package app.diurna.scan

import app.diurna.domain.*
import org.junit.Assert.*
import org.junit.Test

class ScannerReviewTest {
    @Test fun neighbouringNumbersDoNotSwallowWholeEan() {
        for (text in listOf("500 4006381333931", "4 006381 333931 12")) {
            val codes = ocrProductCodes(listOf(text))
            assertTrue(text, codes.contains(ProductCode("4006381333931")))
            assertTrue(codes.all { it.isValid() })
        }
    }
    @Test fun neverSliceInsideGroupsOrRepairDigits() {
        assertTrue(ocrProductCodes(listOf("5004006381333931", "4 006381 333932", "4 OO6381 333931")).isEmpty())
    }
}
