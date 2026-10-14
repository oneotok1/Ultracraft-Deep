"""Builds the Ultracraft modpacks from packs/pack.json and the built mod jar, into dist/:

- Ultracraft.mrpack (Modrinth App: Add instance -> Import): the mods with a Modrinth version, downloaded by the app
  from Modrinth; the Ultracraft jar rides inside the pack (overrides/mods).
- Ultracraft-CurseForge.zip (CurseForge App: Create Custom Profile -> Import): every mod by its CurseForge project and
  file, the Ultracraft jar inside the pack.

The ULTRAKILL side needs nothing from the packs: the mod sets it up when Minecraft starts (UkInstaller).
Usage: python packs/make_packs.py  (after building the Fabric mod). Asks Modrinth's API for each pinned version's
download address and hashes; downloads no mods."""
import glob, json, os, sys, urllib.request, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
pack = json.load(open(os.path.join(ROOT, "packs", "pack.json"), encoding="utf-8"))
version = next(l.split("=", 1)[1].strip() for l in open(os.path.join(ROOT, "fabric", "gradle.properties")) if l.startswith("version="))
jar = os.path.join(ROOT, "fabric", "build", pack["minecraft"], "libs", f"ultracraft-{version}+{pack['minecraft']}.jar")
if not os.path.isfile(jar):
    sys.exit(f"build the mod first: {jar} is missing")
dist = os.path.join(ROOT, "dist")
os.makedirs(dist, exist_ok=True)
jar_in_pack = "mods/" + os.path.basename(jar)


def modrinth(version_id):
    req = urllib.request.Request(f"https://api.modrinth.com/v2/version/{version_id}", headers={"User-Agent": "ultracraft-packs"})
    with urllib.request.urlopen(req, timeout=30) as r:
        v = json.load(r)
    f = next((f for f in v["files"] if f["primary"]), v["files"][0])
    return {"path": "mods/" + f["filename"], "hashes": {"sha1": f["hashes"]["sha1"], "sha512": f["hashes"]["sha512"]},
            "env": {"client": "required", "server": "optional"}, "downloads": [f["url"]], "fileSize": f["size"]}


# ---- Modrinth
index = {"formatVersion": 1, "game": "minecraft", "versionId": version, "name": pack["name"], "summary": pack["summary"],
         "files": [modrinth(m["modrinthVersion"]) for m in pack["mods"] if "modrinthVersion" in m],
         "dependencies": {"minecraft": pack["minecraft"], "fabric-loader": pack["fabricLoader"]}}
mr = os.path.join(dist, "Ultracraft.mrpack")
with zipfile.ZipFile(mr, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("modrinth.index.json", json.dumps(index, indent=2))
    z.write(jar, "overrides/" + jar_in_pack)
print("wrote", mr, [f["path"] for f in index["files"]] + [jar_in_pack])

# ---- CurseForge
manifest = {"minecraft": {"version": pack["minecraft"], "modLoaders": [{"id": "fabric-" + pack["fabricLoader"], "primary": True}]},
            "manifestType": "minecraftModpack", "manifestVersion": 1, "name": pack["name"], "version": version,
            "author": pack["author"], "files": [{"projectID": m["curseforge"][0], "fileID": m["curseforge"][1], "required": True}
                                                for m in pack["mods"] if "curseforge" in m], "overrides": "overrides"}
modlist = "<ul>\n" + "".join(f"<li>{m['name']}</li>\n" for m in pack["mods"]) + f"<li>Ultracraft {version}</li>\n</ul>\n"
cf = os.path.join(dist, "Ultracraft-CurseForge.zip")
with zipfile.ZipFile(cf, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("manifest.json", json.dumps(manifest, indent=2))
    z.writestr("modlist.html", modlist)
    z.write(jar, "overrides/" + jar_in_pack)
print("wrote", cf, [m["name"] for m in pack["mods"] if "curseforge" in m] + [jar_in_pack])
