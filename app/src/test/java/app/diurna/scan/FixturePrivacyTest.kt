package app.diurna.scan
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
class FixturePrivacyTest {
    @Test fun authorizedPhotoContainsPixelsOnlyNoAncillaryMetadata() {
        javaClass.getResourceAsStream("/barcodes/blurry-upca.png")!!.use { input ->
            val data=DataInputStream(input)
            assertEquals(-8552249625308161526L,data.readLong()) // PNG signature
            val types=mutableSetOf<String>()
            while (true) {
                val length=data.readInt(); assertTrue(length in 0..2_000_000)
                val tag=ByteArray(4);data.readFully(tag);val type=String(tag,Charsets.US_ASCII)
                assertTrue(type in setOf("IHDR","IDAT","IEND"));types+=type
                val bytes=ByteArray(length);data.readFully(bytes);data.readInt()
                if(type=="IEND")break
            }
            assertEquals(setOf("IHDR","IDAT","IEND"),types);assertEquals(-1,data.read())
        }
    }
}
