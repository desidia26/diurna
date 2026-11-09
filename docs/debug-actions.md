# Debug action API (v1)

The Redux-style store logs intent and outcomes only. Its reducer is intentionally a no-op;
ViewModels and Room continue to own data. A future bundled Svelte Week client may dispatch
through this same boundary; this is not yet a WebView bridge or replay engine.

- `container.actions.dispatch(Action.Event(Screen.DAY, "feature.operation", correlationId, result, latencyMs))`
- `Action.Text(screen, TextField.FOOD|EXERCISE|RECIPE|SEARCH, value)` is restricted to explicitly non-secret fields; disabled by default.
- `container.debug.registerAttachment(bytes: ByteArray, kind: AttachmentKind, meta: Map<String,String> = emptyMap(), correlationId: String = UUID...) : String?`
- Same overload with `file: File`; call on IO dispatcher. Copies immediately, before a temporary file is deleted. Null means disabled, invalid, oversized, evicted or unreadable. IDs are opaque UUIDs; never filenames supplied by callers.
- Kinds: `IMAGE` (opaque image bytes) and `BARCODE` (UTF-8, 6–14 digits only). Metadata accepts only `format` (`[A-Z0-9_]{1,32}`). Pass one UUID correlation id to related events/attachments.

Never dispatch keys, passwords, request/response bodies, auth headers, endpoint URLs, porch QR
contents or arbitrary exception messages. Event names must be fixed lowercase tokens, not user
values. Known provider keys and common token/password patterns are redacted from opted-in text.
Arbitrary secrets in prose cannot be recognized reliably: avoid entering secrets in ordinary fields.
Images are inherently unredacted personal data; reporting UI must make this explicit and allow removal.

Journal IO is FIFO on a bounded background queue; overload drops log actions, not app actions.
Storage is app-private `noBackupFilesDir`, up to 20 MiB content and 2,000 action entries, expires
on startup/access/write after seven days. Disabling erases the journal and resets typed-text logging.
Debug preferences are excluded from Android backup. There is no automatic upload.

## Current coverage, not “all actions”

Tab changes; Setup page and porch-dialog navigation; Library page entry; named fire-and-forget
ViewModel operations and completion/error/cancellation with latency; committed Room table-change
notifications (coalesced, not each SQL statement); food/cardio/label AI calls and ingredient guesses;
photo picker/camera/import; barcode lookup and numeric result; alarm open/code-attempt/stop.
Saved food text is the initial opt-in text hook. Other text fields and individual focus/keystroke
changes are intentionally not instrumented yet. Table notifications contain no row ids or values.
Density AI, URL imports, most local dialog switches and service lifecycle events are coverage gaps.
No payload replay, screenshots, HTTP bodies or deterministic state reconstruction is claimed.

## Security review

No schema/package/signing/prompt/CI changes. No new network calls or automatic sharing. Storage
and queue are bounded, filenames generated, metadata allowlisted, exception messages omitted.
Attachments can contain private information and must be explicitly reviewed before export.

## Reviewed failure reports

Setup exposes independent debug and typed-text toggles. A top-level flag button covers all five
tabs, Setup-hosted Library pages and AlarmActivity. While an existing modal dialog covers the
root, close it to reach the flag button; those individual modal windows are not instrumented yet.
The report screen previews the JSONL and each image/barcode, allows excluding the log or individual
attachments, bounds the note, then invokes the system share chooser with a read-only FileProvider
URI. Review windows are secure, require an unlocked device and cannot bypass the porch alarm.
Generated ZIPs live in a dedicated cache directory, at most one per report flow, cleared on disabling
and next process startup. No database, settings backup or key material is included. Reports are
not a replay format; filesystem/queue eviction can leave incomplete history and missing attachments.

Tests cover recorder redaction/retention/size/enable transitions and archive contents/path guarding,
plus the independent toggle behavior in a CI-only emulator Compose test. End-to-end share chooser,
keyguard transitions during an actual ringing alarm, and arbitrary OEM camera behavior remain
manual/emulator integration gaps.
