package app.diurna.scan
import app.diurna.domain.*
import org.junit.Assert.*
import org.junit.Test
class ScanLookupGateTest {
    private val code = ProductCode("4006381333931")
    @Test fun rejectsDoubleClicksButAllowsExplicitRetryAfterFailure() {
        val gate=ScanLookupGate(); assertTrue(gate.begin(code)); assertFalse(gate.begin(code))
        gate.finish(false); assertTrue(gate.begin(code)); gate.finish(true)
        gate.finish(false); assertFalse(gate.begin(code))
    }
    @Test fun cancelledOrInvalidLookupCannotStart() {
        val gate=ScanLookupGate(); assertFalse(gate.begin(ProductCode("123")))
        gate.cancel(); gate.finish(false); assertFalse(gate.begin(code))
    }
}
