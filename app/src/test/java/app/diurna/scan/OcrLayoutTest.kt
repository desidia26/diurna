package app.diurna.scan

import app.diurna.domain.*
import org.junit.Assert.*
import org.junit.Test

class OcrLayoutTest {
    private fun line(text:String,x:Int,y:Int=0,w:Int=text.length*20)=OcrLine(text,x,y,x+w,y+30)
    @Test fun combinesAlignedUpcFragmentsAcrossRecognizerBlocks() {
        val lines=listOf(line("0",0),line("36000",40),line("29145",160),line("2",280))
        assertEquals(listOf(ProductCode("036000291452")),ocrProductCodesFromLayout(lines))
    }
    @Test fun rejectsUnrelatedRowsLabelsAndInvalidChecksums() {
        assertTrue(ocrProductCodesFromLayout(listOf(line("0",0),line("36000",40,60),line("29145",160,120),line("2",280,180))).isEmpty())
        assertTrue(ocrProductCodesFromLayout(listOf(line("Sodium 03600",0),line("0291452 mg",200))).isEmpty())
        assertTrue(ocrProductCodes(listOf("4006381333932","O36000291452")).isEmpty())
    }
    @Test fun acceptsWholeEanUpcSpacesHyphensButNeverInventsOrJoinsLines() {
        assertEquals(listOf(ProductCode("4006381333931")),ocrProductCodes(listOf("4 006381 333931")))
        assertEquals(listOf(ProductCode("036000291452")),ocrProductCodes(listOf("0-36000-29145-2")))
        assertTrue(ocrProductCodes(listOf("036000","291452")).isEmpty())
        assertTrue(ocrProductCodes(listOf("11110360002914521111")).isEmpty())
    }
    @Test fun boundsInputAndDeduplicatesLeadingZeroEquivalentCodes() {
        assertEquals(1,ocrProductCodesFromLayout(listOf(line("036000291452",0),line("0036000291452",0,60))).size)
        assertTrue(ocrProductCodesFromLayout(emptyList()).isEmpty())
    }
}
