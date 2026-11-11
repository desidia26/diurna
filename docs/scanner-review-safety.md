# Scanner review corrections

The pure LiveScanSession and ScanLookupGate safety design and regression tests are ported from scanner-reliability #85 (13e003e) so #57 is safe independently, without action/debug-report dependencies. Live-only candidates always require explicit confirmation. Per-binding sessions reject stale/cancelled callbacks and serialize capture/completion; the lookup gate suppresses duplicates and success delivery after cancellation.

OCR tries whole contiguous digit-group windows of 8, 12 or 13 digits within one line, checks checksums, and always requests confirmation. It never slices a longer number or invents digits. All work remains bounded. Injected OutOfMemoryError tests cover the image-processing boundary without large allocations; photo cleanup still runs in finally.

No OCR engine/dependency change. Parser tests are not OCR recognition proof; model-not-ready must remain a separate availability result. Alarm QR is untouched. No new real-photo fixtures or private logs are added.
