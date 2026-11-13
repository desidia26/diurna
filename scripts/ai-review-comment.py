# Renders scripts/ai-review's JSON as markdown for a PR comment or a run summary.
#   python3 -I scripts/ai-review-comment.py review.json
# Claude's text goes into table cells and quotes, with markup neutralised, so a finding can't
# turn into links, mentions, images or hidden HTML in the comment.
import json, re, sys

review = json.load(open(sys.argv[1]))

def plain(s, limit):
    s = re.sub(r"\s+", " ", str(s))[:limit]
    s = s.replace("@", "@\u200b").replace("://", ":/\u200b/")  # no mentions, no autolinked URLs
    return re.sub(r"([\\`*_{}\[\]()<>#+!|~])", r"\\\1", s)

ICON = {"critical": "🔴", "high": "🟠", "medium": "🟡", "low": "⚪"}
verdict = review.get("verdict")
lines = ["<!-- diurna-ai-review -->", "### AI security review", ""]
if verdict == "clean" and not review.get("findings"):
    lines.append("Nothing found. " + (plain(review["note"], 200) if review.get("note") else ""))
else:
    lines.append("**Needs a look before it ships.**" if verdict == "hold" else "Some things to consider:")
    if review.get("note"):
        lines += ["", plain(review["note"], 400)]
    if review.get("findings"):
        lines += ["", "| | Where | Issue | Why it matters |", "|---|---|---|---|"]
        for f in review["findings"]:
            where = plain(f.get("file", "?"), 120) + (f":{int(f['line'])}" if isinstance(f.get("line"), int) else "")
            lines.append(f"| {ICON.get(f.get('severity'), '?')} {plain(f.get('severity', ''), 10)} | {where} | {plain(f.get('issue', ''), 300)} | {plain(f.get('why', ''), 800)} |")
lines += ["", "<sub>Advisory: written by Claude from the diff, checked by no one yet. The CVE gate, CodeQL and lint are the actual checks.</sub>"]
print("\n".join(lines))
