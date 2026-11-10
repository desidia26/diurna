# Reads `aapt2 dump xmltree --file AndroidManifest.xml` on stdin and prints the APK's attack
# surface for scripts/check-apk, one sorted line per item:
#   permission <name>
#   exported <activity|receiver|service|provider> <class> [guarded-by <permission>]
#   flag <attribute>=<value>     for application attributes that must never be on in a release
#   cleartext <domain>           hosts the network security config lets use plain http
# argv[1] is the source network_security_config.xml: R8 shortens resource paths in the APK, so
# the release's copy can't be found by name there.
import re, sys
import xml.etree.ElementTree as ET

ELEMENT = re.compile(r"^(\s*)E: ([\w-]+)")
ATTR = re.compile(r"^(\s*)A: http://schemas\.android\.com/apk/res/android:(\w+)\([^)]*\)=(\S+)")
COMPONENTS = {"activity", "activity-alias", "receiver", "service", "provider"}
WATCHED_FLAGS = {"debuggable", "testOnly", "usesCleartextTraffic"}

stack, nodes = [], []  # stack of (indent, node)
for line in sys.stdin:
    if m := ELEMENT.match(line):
        indent = len(m.group(1))
        while stack and stack[-1][0] >= indent:
            stack.pop()
        node = {"tag": m.group(2), "attrs": {}, "children": [], "parent": stack[-1][1] if stack else None}
        if node["parent"]:
            node["parent"]["children"].append(node)
        nodes.append(node)
        stack.append((indent, node))
    elif (m := ATTR.match(line)) and stack:
        value = m.group(3)
        raw = re.search(r'\(Raw: "([^"]*)"\)', line)
        stack[-1][1]["attrs"][m.group(2)] = raw.group(1) if raw else value.strip('"')

out = set()
for n in nodes:
    a = n["attrs"]
    if n["tag"] in ("uses-permission", "uses-permission-sdk-23"):
        out.add(f"permission {a.get('name')}")
    elif n["tag"] == "application":
        for flag in WATCHED_FLAGS & a.keys():
            if a[flag] not in ("false", "0x0"):
                out.add(f"flag {flag}={a[flag]}")
        if "networkSecurityConfig" not in a:
            out.add("flag networkSecurityConfig=missing")
    elif n["tag"] in COMPONENTS:
        exported = a.get("exported")
        # Without an explicit value, a component with an intent filter is reachable from other apps.
        reachable = exported == "true" or (exported is None and any(c["tag"] == "intent-filter" for c in n["children"]))
        if reachable:
            guard = a.get("permission") or (a.get("readPermission") if n["tag"] == "provider" else None)
            kind = "activity" if n["tag"] == "activity-alias" else n["tag"]
            out.add(f"exported {kind} {a.get('name')}" + (f" guarded-by {guard}" if guard else ""))

config = ET.parse(sys.argv[1]).getroot()
base = config.find("base-config")
if base is None or base.get("cleartextTrafficPermitted", "false") != "false":
    out.add("cleartext *")
for dc in config.iter("domain-config"):
    if dc.get("cleartextTrafficPermitted") == "true":
        out.update(f"cleartext {d.text.strip()}" for d in dc.iter("domain"))

print("\n".join(sorted(out)))
