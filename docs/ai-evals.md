# AI prompt evals

Eval harness for every prompt that ships on `main`. It calls the **same** Kotlin prompt builders, request builders, estimators and parsers the app uses (`EstimatePrompt`, `CardioPrompt`, `ClaudeFoodEstimator` / `GrokFoodEstimator`, cardio readers). Prompt text is not copied into the eval suite.

## Prompts covered (main)

| Prompt | Used by | Input |
|--------|---------|--------|
| `EstimatePrompt` | Claude + Grok food estimators | free-text food entry ± meal photo |
| `CardioPrompt` | Claude + Grok cardio readers | workout-summary photo |

Stacked recipe / sub-macro work is out of scope until those PRs land.

## Providers

| Id | Env | Default model | Notes |
|----|-----|---------------|--------|
| `grok` | `XAI_API_KEY` | app default (`grok-4.20-non-reasoning`) | Real accuracy eval |
| `claude` | `ANTHROPIC_API_KEY` | app default (`claude-haiku-5-5`) | Real accuracy eval |
| `github-models` | `GITHUB_TOKEN` (or `GH_TOKEN`) | `xai/grok-3-mini` | **Smoke only** — substitute model, not an accuracy eval of the app’s providers |

A provider with a missing key is **skipped** (clear message), not failed. HTTP 429 on GitHub Models is also a skip. Override models with `-PgrokModel=`, `-PclaudeModel=`, `-PgithubModel=`.

### GitHub Models smoke

Path-filtered PRs run the tiny `smoke` case set against GitHub Models using the workflow’s `GITHUB_TOKEN` and `permissions: models: read`. No repo secret is required.

This checks that prompts still produce parseable structured replies on *some* OpenAI-compatible chat API. It is **not** a measure of Grok/Claude estimate quality.

**Status (2026-10):** [GitHub Models was retired on 2026-07-30](https://github.blog/changelog/2026-07-30-github-models-is-now-retired/). The inference URL currently returns a plain-text stub. The provider detects that and skips cleanly so CI stays green; if the endpoint ever serves chat completions again (or a compatible successor), the same wiring works. Fork PRs may also lack `models: read` — that is skipped, not failed.

## Run locally

```bash
source ~/android-env.sh   # if you use the Diurna build box
cd /path/to/diurna

# Unit tests for scoring/case loading (no network)
./gradlew testDebugUnitTest

# Live evals — only providers whose keys are set will run
./gradlew aiEval -Pprovider=grok -Pset=default
./gradlew aiEval -Pprovider=claude -Pset=default
./gradlew aiEval -Pprovider=github-models -Pset=smoke
./gradlew aiEval -Pprovider=all -Pset=full
```

Reports: `ai-evals/out/summary.md` and `ai-evals/out/results.json` (gitignored). In Actions, the markdown is also appended to `$GITHUB_STEP_SUMMARY`.

## Case sets

| Set | File | Intent |
|-----|------|--------|
| `smoke` | `ai-evals/cases/smoke.json` | 3 text-only food cases; CI default without secrets |
| `default` | `ai-evals/cases/default.json` | ~11 cases: typical food, edges, synthetic photos, cardio |
| `full` | `ai-evals/cases/full.json` | default + more food variety |

Expectations are **bands and tolerances**, not exact values (kcal ±%, macro ranges, parse success, not-food / reject, macro↔kcal consistency).

### Adding a case

1. Edit the JSON under `ai-evals/cases/` (or add an image under `ai-evals/images/` — see `images/SOURCE.md`; only generate or use public-domain/CC0).
2. Set `expect.outcome` to `estimate`, `not_food`, `not_food_or_zeros`, `reading`, or `reject`.
3. Run `./gradlew aiEval -Pprovider=… -Pset=…` and adjust bands if a healthy model is consistently outside them.

## CI

Workflow: `.github/workflows/ai-evals.yml`

- **Triggers:** `workflow_dispatch` (provider/model/set inputs); `pull_request` and `push` to `main`, path-filtered to prompt/eval files.
- **Default PR path:** `github-models` + `smoke`.
- **With secrets:** if `XAI_API_KEY` / `ANTHROPIC_API_KEY` exist, path-filtered runs use those providers and `default`.
- **Permissions:** `contents: read`, `models: read`. Never `pull_request_target`.
- **Not** a required check. Concurrency + 25m timeout. Artifact: `ai-eval-results`.

### Secrets an admin must add

Only a repo admin (e.g. desidia26) can add Actions secrets:

| Secret | Used by |
|--------|---------|
| `XAI_API_KEY` | `grok` provider |
| `ANTHROPIC_API_KEY` | `claude` provider |

`GITHUB_TOKEN` is provided by Actions; no secret to add for GitHub Models smoke.

## Cost (ballpark)

| Run | Calls | Rough cost |
|-----|-------|------------|
| `smoke` × github-models | 3 | Free tier (was ~2/min, ~30/day for grok-3-mini) |
| `default` × one real provider | ~11 | Low cents with Haiku / grok-4.20-non-reasoning |
| `full` × both real providers | ~30+ | Still usually well under $1 |

Vision cases cost more than text. Prefer `smoke`/`default` in CI; use `full` on dispatch when you care.

## Security notes

- Secrets are injected only into the eval step `env`; the harness prints key **presence**, never values.
- Reports/artifacts store case ids, pass/fail, and model names — not API keys.
- `models: read` on `GITHUB_TOKEN` is the only added token scope; it only matters if GitHub Models (or a successor on that endpoint) accepts the job token.
