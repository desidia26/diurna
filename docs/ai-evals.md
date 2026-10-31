# AI prompt evals

Eval harness for every prompt that ships on `main`. It calls the **same** Kotlin prompt builders, request builders, estimators and parsers the app uses (`EstimatePrompt`, `CardioPrompt`, `LabelPrompt`, `ClaudeFoodEstimator` / `GrokFoodEstimator`, cardio/label readers). Prompt text is not copied into the eval suite.

## Prompts covered (main)

| Prompt | Used by | Input | Eval `prompt` id |
|--------|---------|--------|------------------|
| `EstimatePrompt` (basic) | Claude + Grok food estimators; restaurant menu estimate (same prompt + constructed text) | free-text food entry ± meal/menu photo | `food` |
| `EstimatePrompt` (detailed) | Food estimate / detail backfill when Detailed nutrition is on | text ± photo, `detailed=true` | `food` + `input.detailed` |
| `EstimatePrompt` (detailed + ingredients) | Food estimate / backfill when Detailed + FDC key | text ± photo, `ingredients=true` | `food` + `input.ingredients` |
| `CardioPrompt` | Claude + Grok cardio readers | workout-summary photo | `cardio` |
| `LabelPrompt` | Claude + Grok label readers | Nutrition Facts photo | `label` |

**Not AI prompts** (no eval cases): Open Food Facts barcode lookup, schema.org recipe import, FDC amino resolution (API, not a model call).

**Follow-up:** [PR #21](https://github.com/desidia26/diurna/pull/21) (BYO OpenAI-compatible gateway) is still open and not on `main`. Do not add OpenAI-compatible provider coverage here; after it merges, Ollama smoke can switch to that app provider and BYO can get its own cases.

## Providers

| Id | Env / setup | Default model | Notes |
|----|-------------|---------------|--------|
| `grok` | `XAI_API_KEY` | app default (`grok-4.20-non-reasoning`) | Real accuracy eval; text + food/menu photo + cardio + label |
| `claude` | `ANTHROPIC_API_KEY` | app default (`claude-haiku-5-5`) | Real accuracy eval; text + food/menu photo + cardio + label |
| `ollama` | local Ollama at `OLLAMA_BASE_URL` (default `http://localhost:11434/v1`) | `qwen2.5:1.5b-instruct` | **Smoke only** — prompt/parse health on a substitute CPU model; text-only |

A provider with a missing key (or unreachable Ollama) is **skipped** (clear message), not failed. Override models with `-PgrokModel=`, `-PclaudeModel=`, `-PollamaModel=`, `-PollamaBaseUrl=`.

### Ollama smoke

CI installs a **checksum-verified** Ollama binary on `ubuntu-latest`, caches the model blobs (key pinned by model layer SHA-256), pulls a small instruct model, and runs the `smoke` case set. No repo secret is required. Permissions are `contents: read` only.

This checks that prompts still produce **parseable** structured replies on a generic OpenAI-compatible chat API. It is **not** a measure of Grok/Claude estimate quality. For `ollama`, nutrition accuracy bands, semantic not-food labeling misses, and optional detail/ingredient presence are **informational** (recorded in the report, do not fail the build); required checks are parse/structure health (successful parse, range ordered, non-negative).

### Pass / fail rules (all providers)

| Result | Ollama | Grok / Claude | Effect on run / CI |
|--------|--------|---------------|--------------------|
| **Parse failure** — malformed/unparseable JSON, missing required fields, parser exception, empty response, or HTTP/transport error with no parseable body | **Fails** the case and the run | **Fails** the case and the run | Exit non-zero; listed under **Parse failures (block the build)** in the summary. Ignores pass-rate threshold. |
| **Accuracy / score miss** — kcal/macro bands, not-food labeling, optional detail/ingredients | Warning / informational (PARSE_HEALTH); does **not** fail the case | Counts against the provider pass-rate vs `passThreshold` (unchanged; do not loosen) | Ollama: reported under Informational. Grok/Claude: under Accuracy / score failures; overall uses existing threshold. |
| **Skipped** — missing API key, unreachable Ollama at probe, image/vision/cardio/label cases on Ollama text-only smoke | Skip | Skip | Not a failure. |

`CaseScore.parseFailure` distinguishes parse failures from score misses. `EvalReport.anyParseFailure` forces `overallPassed = false` even when every provider’s pass rate still meets `passThreshold` (e.g. 4/5 passed with one unparseable reply).

**Model choice:** `qwen2.5:1.5b-instruct` (~986 MB). On CPU-only boxes it passes JSON structured-output smoke tests where the 0.5B sibling does not, while staying small enough for Actions runners and the daily CI budget. Ollama stays **text-only** (no vision input in CI; `-PollamaTextOnly=true`). Photo / cardio / label coverage belongs on Grok and Claude when their keys are present.

Eval-side OpenAI-compatible client only (reuses `XaiChat.request` / `EstimatePrompt.parse`). Once [PR #21](https://github.com/desidia26/diurna/pull/21) (BYO OpenAI-compatible gateway) lands on `main`, this smoke path can switch to that app provider.

## Run locally

```bash
source ~/android-env.sh   # if you use the Diurna build box
cd /path/to/diurna

# Unit tests for scoring/case loading (no network)
./gradlew testDebugUnitTest

# Live evals — only providers whose keys / daemon are available will run
./gradlew aiEval -Pprovider=grok -Pset=default
./gradlew aiEval -Pprovider=claude -Pset=default
./gradlew aiEval -Pprovider=ollama -Pset=smoke
./gradlew aiEval -Pprovider=all -Pset=full
```

For Ollama locally: install from https://ollama.com/download/linux (or the same checksum-verified tarball the workflow uses), `ollama serve`, `ollama pull qwen2.5:1.5b-instruct`, then run the gradle task above.

Reports: `ai-evals/out/summary.md` and `ai-evals/out/results.json` (gitignored). In Actions, each provider job uploads its own artifact; a combined summary job merges them into `$GITHUB_STEP_SUMMARY` and `ai-eval-results`.

## Case sets

| Set | File | Intent |
|-----|------|--------|
| `smoke` | `ai-evals/cases/smoke.json` | 5 text-only food cases (basic + detailed + ingredients); Ollama CI default |
| `default` | `ai-evals/cases/default.json` | ~16 cases: typical food, detailed/ingredients, edges, menu photo, label OCR, cardio |
| `full` | `ai-evals/cases/full.json` | default + more food variety |

Expectations are **bands and tolerances**, not exact values (kcal ±%, macro ranges, parse success, not-food / reject, macro↔kcal consistency, optional `requireDetail` / `requireIngredients`). For `ollama`, bands, not-food labeling, and detail/ingredient presence are informational; **parse failures still fail the run** on every provider.

### Adding a case

1. Edit the JSON under `ai-evals/cases/` (or add an image under `ai-evals/images/` — see `images/SOURCE.md`; only generate or use public-domain/CC0).
2. Set `prompt` to `food`, `cardio`, or `label`. For detailed/ingredients food paths set `input.detailed` / `input.ingredients`.
3. Set `expect.outcome` to `estimate`, `not_food`, `not_food_or_zeros`, `reading`, or `reject`.
4. Run `./gradlew aiEval -Pprovider=… -Pset=…` and adjust bands if a healthy model is consistently outside them.

## CI

Workflow: `.github/workflows/ai-evals.yml`

- **Triggers:** `workflow_dispatch` (provider/model/set inputs); `pull_request` and `push` to `main`, path-filtered to prompt/eval files.
- **Every run (PR/push and dispatch with `all`):** parallel jobs for **Ollama** (always), **Grok**, and **Claude**. A missing `XAI_API_KEY` / `ANTHROPIC_API_KEY` is a clear skip inside that job, not a fail. Ollama does not need a secret.
- **Sets:** Ollama always uses `smoke` (text-only). Grok and Claude use `default` on path-filtered runs (includes food-photo, menu-photo, cardio-photo, and label-photo cases) or the dispatch-selected set.
- **Secrets:** each cloud key is injected only into its own provider job’s eval step. The Ollama job gets no cloud keys.
- **Permissions:** `contents: read` only. Never `pull_request_target`.
- **Not** a required check. Concurrency + ~30m per provider job. Artifacts: `ai-eval-ollama`, `ai-eval-grok`, `ai-eval-claude`, plus combined `ai-eval-results`.

### Secrets an admin must add

Only a repo admin (e.g. desidia26) can add Actions secrets:

| Secret | Used by |
|--------|---------|
| `XAI_API_KEY` | `grok` provider job |
| `ANTHROPIC_API_KEY` | `claude` provider job |

No secret is needed for Ollama smoke. Keys will be added later; until then those jobs skip cleanly.

## Cost (ballpark)

| Run | Calls | Rough cost |
|-----|-------|------------|
| `smoke` × ollama | 5 | Free (CPU on the runner; ~1 GB model download, cached) |
| `default` × one real provider | ~16 (incl. photos) | Low cents with Haiku / grok-4.20-non-reasoning |
| `full` × both real providers | ~40+ | Still usually well under $1 |

Vision cases cost more than text. Prefer `smoke`/`default` in CI; use `full` on dispatch when you care.
