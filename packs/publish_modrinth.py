"""Publishes Ultracraft on Modrinth with your own token (MODRINTH_TOKEN in the environment; never pass it on the
command line). Run it yourself:

    python packs/publish_modrinth.py              creates the project (a draft) if it doesn't exist yet, uploads this version
    python packs/publish_modrinth.py --submit     the same, then sends the draft to Modrinth's moderators for review
    python packs/publish_modrinth.py --icon icon.png --gallery docs/a.png docs/b.png

The project page is packs/modrinth/body.md. A version already on Modrinth isn't uploaded again.
The token needs the scopes "Create projects", "Write projects" and "Create versions"."""
import argparse, json, mimetypes, os, sys, urllib.error, urllib.parse, urllib.request, uuid

API = "https://api.modrinth.com/v2"
SLUG = "ultracraft-mod"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UA = "alfr0762/ultracraft publish script"

ap = argparse.ArgumentParser()
ap.add_argument("--submit", action="store_true", help="send the draft project for review")
ap.add_argument("--icon", help="square PNG for the project icon (not ULTRAKILL's own art)")
ap.add_argument("--gallery", nargs="*", default=[], help="screenshots for the project's gallery")
a = ap.parse_args()
token = os.environ.get("MODRINTH_TOKEN")
if not token:
    sys.exit("set MODRINTH_TOKEN first (see the README's publishing notes)")
pack = json.load(open(os.path.join(ROOT, "packs", "pack.json"), encoding="utf-8"))
version = next(l.split("=", 1)[1].strip() for l in open(os.path.join(ROOT, "fabric", "gradle.properties")) if l.startswith("version="))
mc = pack["minecraft"]
jar = os.path.join(ROOT, "fabric", "build", mc, "libs", f"ultracraft-{version}+{mc}.jar")
if not os.path.isfile(jar):
    sys.exit(f"build the mod first: {jar} is missing")


def call(method, path, body=None, files=None):
    """JSON in and out; files: {part name: path} sent as multipart with the JSON as the "data" part."""
    headers = {"Authorization": token, "User-Agent": UA}
    data = None
    if files is not None:
        boundary = uuid.uuid4().hex
        parts = []
        if body is not None:
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="data"\r\nContent-Type: application/json\r\n\r\n'.encode() + json.dumps(body).encode() + b"\r\n")
        for name, file in files.items():
            ctype = mimetypes.guess_type(file)[0] or "application/java-archive"
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; filename="{os.path.basename(file)}"\r\nContent-Type: {ctype}\r\n\r\n'.encode()
                         + open(file, "rb").read() + b"\r\n")
        data = b"".join(parts) + f"--{boundary}--\r\n".encode()
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(API + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            text = r.read().decode()
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        if e.code == 404 and method == "GET":
            return None
        sys.exit(f"{method} {path}: {e.code} {e.read().decode()[:500]}")


# ---- the project
project = call("GET", f"/project/{SLUG}")
if project is None:
    data = {
        "slug": SLUG, "title": "Ultracraft", "project_type": "mod",
        "description": "The real ULTRAKILL inside Minecraft: play as V1 with ULTRAKILL's movement, weapons, enemies, bosses, shop and Cyber Grind. Needs ULTRAKILL on Steam (Windows).",
        "body": open(os.path.join(ROOT, "packs", "modrinth", "body.md"), encoding="utf-8").read(),
        "categories": ["adventure", "game-mechanics", "mobs"], "additional_categories": ["equipment"],
        "client_side": "required", "server_side": "optional", "license_id": "MIT",
        "source_url": "https://github.com/alfr0762/ultracraft", "issues_url": "https://github.com/alfr0762/ultracraft/issues",
        "is_draft": True, "initial_versions": [],
    }
    project = call("POST", "/project", data, {"icon": a.icon} if a.icon else {})
    print("created the draft project", project["id"], f"https://modrinth.com/mod/{SLUG}")
else:
    print("project", project["id"], "status", project["status"])
    # the page follows packs/modrinth/body.md
    call("PATCH", f"/project/{project['id']}", {"body": open(os.path.join(ROOT, "packs", "modrinth", "body.md"), encoding="utf-8").read()})
    print("page updated")
    if a.icon:
        ext = os.path.splitext(a.icon)[1].lstrip(".")
        req = urllib.request.Request(f"{API}/project/{project['id']}/icon?ext={ext}", data=open(a.icon, "rb").read(), method="PATCH",
                                     headers={"Authorization": token, "User-Agent": UA, "Content-Type": f"image/{ext}"})
        urllib.request.urlopen(req, timeout=60)
        print("icon set")
pid = project["id"]

for i, shot in enumerate(a.gallery):
    ext = os.path.splitext(shot)[1].lstrip(".")
    req = urllib.request.Request(f"{API}/project/{pid}/gallery?ext={ext}&featured={'true' if i == 0 else 'false'}&title={urllib.parse.quote(os.path.basename(shot))}",
                                 data=open(shot, "rb").read(), method="POST", headers={"Authorization": token, "User-Agent": UA, "Content-Type": f"image/{ext}"})
    urllib.request.urlopen(req, timeout=120)
    print("gallery:", shot)

# ---- this version
versions = call("GET", f"/project/{pid}/version") or []
if any(v["version_number"] == version for v in versions):
    print("version", version, "is already on Modrinth")
else:
    notes = os.path.join(ROOT, "packs", "modrinth", f"changelog-{version}.md")
    data = {
        "project_id": pid, "name": f"Ultracraft {version}", "version_number": version,
        "changelog": open(notes, encoding="utf-8").read() if os.path.isfile(notes) else f"Ultracraft {version}: see https://github.com/alfr0762/ultracraft/releases",
        "dependencies": [{"project_id": "P7dR8mSH", "dependency_type": "required"}],
        "game_versions": [mc], "version_type": "release", "loaders": ["fabric"], "featured": True, "file_parts": ["jar"],
    }
    v = call("POST", "/version", data, {"jar": jar})
    print("uploaded", version, v["id"])

if a.submit:
    if project.get("status") == "draft":
        call("PATCH", f"/project/{pid}", {"status": "processing"})
        print("sent for review: Modrinth's moderators usually answer within a few days")
    else:
        print("not a draft (status", project.get("status"), "): nothing to submit")
