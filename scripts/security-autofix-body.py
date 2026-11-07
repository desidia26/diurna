# PR or issue body for scripts/security-autofix.
#   python3 -I scripts/security-autofix-body.py <findings.json> <why> <claude-summary.md> pr|issue
# Findings are already reduced to ids, packages, versions and locations. Claude's summary is shown
# as an escaped quote, so it can't add mentions, links or markup to the PR.
import json, re, sys

findings = json.load(open(sys.argv[1]))
why, summary, kind = sys.argv[2], open(sys.argv[3]).read().strip(), sys.argv[4]

def plain(s, limit):
    s = re.sub(r"[ \t]+", " ", str(s))[:limit]
    s = s.replace("@", "@\u200b").replace("://", ":/\u200b/")
    return re.sub(r"([\\`*_{}\[\]()<>#+!|~])", r"\\\1", s)

out = []
if kind == "pr":
    out += ["The nightly's security gate blocked publishing. Claude proposed this change; the workflow then checked it",
            "(unit tests, lint, and the security gate now passing) before opening this PR. **Review it like any other PR**:",
            "the nightly publishes again once it's merged.", ""]
else:
    out += ["The nightly's security gate blocked publishing, and no fix PR was opened.", "", f"**Why:** {plain(why, 600)}", "",
            "Options: raise the library by hand, waive the advisory in `security/osv-scanner.toml` with a reason and an",
            "`ignoreUntil` date if it can't reach the app, or fix the code CodeQL points at.", ""]
libs, alerts = findings.get("vulnerable_libraries", []), findings.get("codeql_alerts", [])
if libs:
    out += ["### Vulnerable libraries in the APK", "", "| Advisory | Package | Version | Severity | Fixed in |", "|---|---|---|---|---|"]
    for f in libs:
        sev = f"{f['score']:.1f}" if f.get("score") is not None else (f.get("label") or "?")
        out.append(f"| {', '.join(map(str, f.get('ids', [])))} | `{f.get('package')}` | {f.get('version')} | {sev} | {', '.join(f.get('fixed', [])) or '—'} |")
    out.append("")
if alerts:
    out += ["### CodeQL alerts", "", "| Rule | Severity | Where |", "|---|---|---|"]
    for a in alerts:
        out.append(f"| {plain(a.get('rule'), 100)} | {plain(a.get('severity'), 10)} | {plain(a.get('path'), 200)}:{a.get('line')} |")
    out.append("")
if summary:
    out += ["### What Claude said", ""] + ["> " + plain(l, 400) for l in summary.splitlines()[:40]] + [""]
out.append("<sub>Opened by the nightly's autofix job (scripts/security-autofix).</sub>")
print("\n".join(out))
