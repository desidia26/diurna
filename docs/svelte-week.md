# Offline Svelte Week preview

Stacked on the action-log foundation (#55). This is one opt-in read-only screen, not a replacement data layer. Compose, ViewModels and Room remain authoritative. The native Previous/Next controls only change the selected week; the web client cannot write anything.

`web/` uses pinned Svelte/Vite/TypeScript dependencies and a committed npm lockfile. Gradle's `preBuild` runs `npm ci --ignore-scripts` and the production build into `app/build/generated/webAssets/web`. Generated bundles are not committed. CI sets up Node 24.22.1 in every Android build job. Local builds require Node >=22.12 and npm on PATH; missing npm fails explicitly in `webInstall` (no silently incomplete APK). Node is never required on the phone. npm needs network on a clean build; the installed APK does not.

`https://appassets.androidplatform.net/assets/web/index.html` is served by WebViewAssetLoader. All other request destinations fail closed (including unmatched appassets paths). Main-frame navigation is blocked, CSP forbids frames/network/inline scripts, and file/content access is disabled. WebMessageListener is injected only at that HTTPS origin; the router additionally rejects subframes and foreign origins. The WebView never receives settings, secrets, arbitrary SQL, or a repository object. An unsupported WebView, renderer failure, asset error, bridge error, or startup timeout uses Compose Week. A native escape button is always visible. No remote content is loaded.

## v1 protocol

Request: `{ "v": 1, "id": "web-1", "method": "week.get" }`. Exactly these fields, <=2048 characters, id restricted to 1–40 alphanumeric/underscore/hyphen characters. Closed methods: `week.get`, `client.ready`, `client.failed`. There is no generic action dispatch or mutation command. Each attempt logs STARTED and OK/FAILED through the existing Redux-style store, with a native-generated correlation id and latency, never request or response contents.

Reply: `{ "v": 1, "id": "web-1", "data": { ... } }` or `data: null` while loading. Acknowledgements have `ok: true`; invalid requests have `error: "invalid_request"`. Week DTO: start/today, onTime/scoredCount, up to seven days with date, morning status, workout/planned names (capped at 200 characters), kcal, steps, stepGoal. No scan timestamps, free-form food logs, keys or settings. Refresh on entry, every 30 seconds and on demand; changing week recreates the page. Missing values stay null, not zero.

## Tests and review

- `cd web && npm ci --ignore-scripts && npm test`: fake bridge component rendering, escaping, failure callback and version rejection.
- `./gradlew testDebugUnitTest`: DTO mapping, command validation, origin/subframe rejection, URL policy and action logging.
- CI emulator: actual bundled Svelte page loads and performs `week.get`; foreign-origin content gets no injected bridge.
- `npm audit --audit-level=high`: no high/moderate findings with the selected versions. Two low build-only findings remain for deepmerge/plugin, with no upstream fix. The app ships compiled assets, not npm packages or a dev server; no untrusted Vite config is consumed.

Known scope limits: no editing or day drill-down in web, no offline browser-only mode, no Redux state rewrite, no screenshot test or TalkBack parity audit yet. CI covers WebView integration; no local emulator or real phone was used.
