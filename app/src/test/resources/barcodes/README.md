# Scanner fixtures

Synthetic product barcode 4006381333931 (no personal photos). `Generator.java.txt`
uses ZXing EAN13Writer, 420×180, margin 30; the remaining images use Pillow:

- blur: GaussianBlur(0.65)
- noise: Python Random(91), 3.5% pixels replaced by a random grey
- rotate: 90 degrees, expand=True
- low-resolution: resize to 140×90, BOX
- perspective: coefficients (1,.07,-6,.03,1,-5,.0001,.0002), BICUBIC, white fill
- glare-fail: white vertical stripe x=140..260 removes bars on every row
- clipped-fail: crop x=100..320 removes both ends
- blank-fail: all white
- corrupt-fail: literal bytes "not a png"

Expected results and exact successful retry are asserted by BarcodeFixtureTest.
These fixtures test transforms with ZXing, not ML Kit recognition accuracy; device
coverage tests the bundled ML Kit decoder separately. Larry's actual failed photo
is not yet available. Never copy personal captures into fixtures without consent.
