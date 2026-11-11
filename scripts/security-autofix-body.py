# Issue body (or follow-up comment) for scripts/security-autofix.
#   python3 -I scripts/security-autofix-body.py <findings.json> <why> <claude-summary.md> <patch|""> <run-id> <findings-key>
# <why> is empty when there's a checked fix in <patch>. Findings are already reduced to ids,
# packages, versions and locations. Claude's words are shown as an escaped quote and its patch
# inside a code fence it can't close, so neither can add mentions, links or markup to the issue.
import json, re, sys

findings = json.load(open(sys.argv[1]))
why, summary = sys.argv[2], open(sys.argv[3]).read().strip()
patch = open(sys.argv[4], errors="replace").read() if sys.argv[4] else ""
run_id, key = sys.argv[5], sys.argv[6]
MAX_PATCH_CHARS = 40000  # an issue body tops out at 65536

def plain(s, limit):
    s = re.sub(r"[ \t]+", " ", str(s))[:limit]
    s = s.replace("@", "@​").replace("://", ":/​/")
    return re.sub(r"([\\`*_{}\[\]()<>#+!|~])", r"\\\1", s)

get = (f"gh run download {int(run_id)} -n autofix -D /tmp/autofix-{int(run_id)}"
       f" && git apply /tmp/autofix-{int(run_id)}/change.patch")
out = ["The nightly's security gate blocked publishing.", ""]
if patch:
    out += ["Claude proposed the change below, and the workflow checked it on its own machine (unit tests, lint, and",
            "the security gate now passing). To use it, apply it on a branch from `main`, then review it and open a",
            "PR like any other; the nightly publishes again once it's merged:", "", f"    {get}", ""]
else:
    out += [f"**No checked fix:** {plain(why, 600)}", "",
            "Options: raise the library by hand, waive the advisory in `security/osv-scanner.toml` with a reason and an",
            "`ignoreUntil` date if it can't reach the app, fix the code CodeQL points at, or dismiss the alert with a",
            "reason if it's a deliberate choice.", ""]
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
if patch:
    out += ["### The change", ""]
    if len(patch) <= MAX_PATCH_CHARS:
        # Longer than any run of backticks inside, so the patch can't end the block early.
        fence = "`" * max(3, 1 + max((len(r) for r in re.findall(r"`+", patch)), default=0))
        out += [fence + "diff", patch.rstrip("\n"), fence, ""]
    else:
        out += [f"Too long to show here ({len(patch)} characters); the command above fetches it.", ""]
out += [f"<sub>From the nightly's autofix job (scripts/security-autofix), run {int(run_id)}.</sub>",
        f"<!-- autofix-findings: {plain(key, 64)} -->"]
print("\n".join(out))
