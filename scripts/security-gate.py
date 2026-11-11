# Judges an OSV-Scanner JSON report for scripts/security-gate.
#   python3 -I scripts/security-gate.py <osv.json> <osv-scanner.toml> <gate.json out>
# Prints a markdown summary (also appended to $GITHUB_STEP_SUMMARY in CI), writes the findings as
# JSON for the autofix job, and exits 1 when anything blocks. Advisory text is never echoed: only
# ids, packages, versions and scores, so a crafted advisory can't steer whatever reads the output.
import json, os, re, sys, tomllib
from datetime import date, timedelta

BLOCK_SCORE = 7.0
BLOCK_LABELS = {"HIGH", "CRITICAL"}
EXPIRY_WARNING = timedelta(days=14)

report_path, config_path, out_path = sys.argv[1:4]
report = json.load(open(report_path)) if os.path.getsize(report_path) else {}
waivers = tomllib.load(open(config_path, "rb")).get("IgnoredVulns", [])

SAFE = re.compile(r"[A-Za-z0-9._:+\-]{1,120}")
def safe(s): return s if isinstance(s, str) and SAFE.fullmatch(s) else "?"

findings = []
for result in report.get("results", []):
    for pkg in result.get("packages", []):
        p = pkg.get("package", {})
        vulns = {v.get("id"): v for v in pkg.get("vulnerabilities", [])}
        for group in pkg.get("groups", []):
            ids = [i for i in group.get("ids", []) if i in vulns] or group.get("ids", [])
            try:
                score = float(group.get("max_severity") or "nan")
            except ValueError:
                score = float("nan")
            labels = {str((vulns.get(i, {}).get("database_specific") or {}).get("severity", "")).upper() for i in ids}
            blocks = score >= BLOCK_SCORE if score == score else bool(labels & BLOCK_LABELS)
            fixed = sorted({ev["fixed"] for i in ids for a in vulns.get(i, {}).get("affected", [])
                            for r in a.get("ranges", []) for ev in r.get("events", []) if "fixed" in ev})
            findings.append({
                "ids": [safe(i) for i in ids],
                "package": safe(p.get("name")),
                "version": safe(p.get("version")),
                "score": score if score == score else None,
                "label": next((l for l in ("CRITICAL", "HIGH", "MODERATE", "MEDIUM", "LOW") if l in labels), None),
                "fixed": [safe(f) for f in fixed],
                "blocks": blocks,
            })

findings.sort(key=lambda f: (not f["blocks"], -(f["score"] or 0)))
blocking = [f for f in findings if f["blocks"]]
today = date.today()
expiring = [w for w in waivers if isinstance(w.get("ignoreUntil"), date) and w["ignoreUntil"] - today <= EXPIRY_WARNING]

lines = ["### Known vulnerabilities in what ships", ""]
if not findings:
    lines.append("None found in the release runtime classpath.")
else:
    lines += ["| | Advisory | Package | Version | Severity | Fixed in |", "|---|---|---|---|---|---|"]
    for f in findings:
        sev = f"{f['score']:.1f}" if f["score"] is not None else (f["label"] or "unknown")
        lines.append(f"| {'⛔' if f['blocks'] else '·'} | {', '.join(f['ids'])} | `{f['package']}` | {f['version']} | {sev} | {', '.join(f['fixed']) or '—'} |")
lines.append("")
lines.append(f"**{len(blocking)} blocking** (CVSS ≥ {BLOCK_SCORE} or HIGH/CRITICAL), {len(findings) - len(blocking)} reported only, {len(waivers)} waived.")
for w in expiring:
    lines.append(f"- Waiver for {safe(w.get('id'))} expires {w['ignoreUntil']}: fix it or renew it with a reason.")
summary = "\n".join(lines) + "\n"

print(summary)
if os.environ.get("GITHUB_STEP_SUMMARY"):
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as s:
        s.write(summary)
json.dump({"blocking": blocking, "reported": [f for f in findings if not f["blocks"]],
           "expiring_waivers": [safe(w.get("id")) for w in expiring]}, open(out_path, "w"), indent=2)
sys.exit(1 if blocking else 0)
