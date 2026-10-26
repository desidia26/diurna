package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhotoSizingTest {
    @Test fun largePhotosShrinkToTheLongEdgeKeepingShape() {
        assertEquals(1568 to 1176, PhotoSizing.fit(4032, 3024))
        assertEquals(1176 to 1568, PhotoSizing.fit(3024, 4032))
        assertEquals(1568 to 882, PhotoSizing.fit(1920 * 2, 1080 * 2))
    }

    @Test fun smallPhotosAreNeverEnlarged() {
        assertEquals(800 to 600, PhotoSizing.fit(800, 600))
        assertEquals(1568 to 1568, PhotoSizing.fit(1568, 1568))
    }

    @Test fun extremeShapesKeepAtLeastOnePixel() {
        assertEquals(1568 to 1, PhotoSizing.fit(100_000, 10))
    }

    @Test fun emptyImagesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PhotoSizing.fit(0, 100) }
    }

    @Test fun thumbnailSampleSizeStaysAboveTheTarget() {
        assertEquals(1, PhotoSizing.sampleSize(100, 100, 150))
        assertEquals(8, PhotoSizing.sampleSize(1568, 1176, 150)) // 196 px
        assertEquals(1, PhotoSizing.sampleSize(1568, 1176, 1000))
        assertEquals(2, PhotoSizing.sampleSize(1568, 1176, 700))
    }

    @Test fun maxBytesFitsClaudesLimitOnceBase64Encoded() {
        // Base64 grows data by 4/3; Claude refuses images over 5 MB.
        assert(PhotoSizing.MAX_BYTES * 4 / 3 < 5 * 1024 * 1024)
    }
}
