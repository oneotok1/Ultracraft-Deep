"""Uploads this version of Ultracraft to its CurseForge project with your own upload token (CURSEFORGE_TOKEN in the
environment; make one at https://authors.curseforge.com/account/api-tokens). CurseForge projects are created on its
website; this only uploads files. Run it yourself:

    python packs/publish_curseforge.py --check            the token works, and CurseForge's ids for 1.21.11 / Fabric / Java 21 / Client
    python packs/publish_curseforge.py --project 123456   uploads the jar (Fabric API as a required dependency)

The changelog is packs/modrinth/changelog-<version>.md when there is one."""
import argparse, json, os, sys, urllib.error, urllib.request, uuid

API = "https://minecraft.curseforge.com/api"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UA = "alfr0762/ultracraft publish script"

ap = argparse.ArgumentParser()
ap.add_argument("--project", type=int, help="the CurseForge project id (the project's About box)")
ap.add_argument("--check", action="store_true", help="only check the token and look up the version ids")
a = ap.parse_args()
token = os.environ.get("CURSEFORGE_TOKEN")
if not token:
    sys.exit("set CURSEFORGE_TOKEN first")
pack = json.load(open(os.path.join(ROOT, "packs", "pack.json"), encoding="utf-8"))
version = next(l.split("=", 1)[1].strip() for l in open(os.path.join(ROOT, "fabric", "gradle.properties")) if l.startswith("version="))
mc = pack["minecraft"]


def get(path):
    req = urllib.request.Request(API + path, headers={"X-Api-Token": token, "User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit(f"GET {path}: {e.code} {e.read().decode()[:300]}")


# CurseForge's ids for what the file is for
types = {t["id"]: t["slug"] for t in get("/game/version-types")}
want = {}
for v in get("/game/versions"):
    slug = types.get(v["gameVersionTypeID"], "")
    if v["name"] == mc and slug.startswith("minecraft-"):
        want["minecraft"] = v["id"]
    elif v["name"] == "Fabric" and slug == "modloader":
        want["fabric"] = v["id"]
    elif v["name"] == "Java 21" and slug == "java":
        want["java"] = v["id"]
    elif v["name"] == "Client" and slug == "environment":
        want["client"] = v["id"]
print("version ids:", want)
if "minecraft" not in want or "fabric" not in want:
    sys.exit("CurseForge has no " + mc + " or Fabric version id")
if a.check:
    sys.exit(0)
if not a.project:
    sys.exit("--project <id> is needed to upload")

jar = os.path.join(ROOT, "fabric", "build", mc, "libs", f"ultracraft-{version}+{mc}.jar")
if not os.path.isfile(jar):
    sys.exit(f"build the mod first: {jar} is missing")
notes = os.path.join(ROOT, "packs", "modrinth", f"changelog-{version}.md")
metadata = {
    "changelog": open(notes, encoding="utf-8").read() if os.path.isfile(notes) else f"Ultracraft {version}: see https://github.com/alfr0762/ultracraft/releases",
    "changelogType": "markdown", "displayName": f"Ultracraft {version}", "releaseType": "release",
    "gameVersions": list(want.values()),
    "relations": {"projects": [{"slug": "fabric-api", "type": "requiredDependency"}]},
}
boundary = uuid.uuid4().hex
body = (f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n\r\n'.encode() + json.dumps(metadata).encode() + b"\r\n"
        + f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{os.path.basename(jar)}"\r\nContent-Type: application/java-archive\r\n\r\n'.encode()
        + open(jar, "rb").read() + f"\r\n--{boundary}--\r\n".encode())
req = urllib.request.Request(f"{API}/projects/{a.project}/upload-file", data=body, method="POST",
                             headers={"X-Api-Token": token, "User-Agent": UA, "Content-Type": f"multipart/form-data; boundary={boundary}"})
try:
    with urllib.request.urlopen(req, timeout=180) as r:
        print("uploaded", os.path.basename(jar), "as file", json.load(r).get("id"))
except urllib.error.HTTPError as e:
    sys.exit(f"upload: {e.code} {e.read().decode()[:500]}")
