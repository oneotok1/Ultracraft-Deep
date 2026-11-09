# -*- coding: utf-8 -*-
"""Build Ultracraft.zip: the jar and the bridge dll in one download, flat, no folders.

The release notes tell players to drop the jar in mods and the dll in
BepInEx/plugins/UltraBridge/, so the archive holds exactly those two files with
their built names - unpacking it into either place puts the right file there.
"""
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
def version():
    with open(os.path.join(ROOT, 'fabric', 'gradle.properties'), encoding='utf-8') as f:
        for line in f:
            if line.startswith('version='):
                return line.split('=', 1)[1].strip()
    sys.exit('no version in fabric/gradle.properties')


JAR = os.path.join(ROOT, 'fabric', 'build', '1.21.11', 'libs',
                   'ultracraft-' + version() + '+1.21.11.jar')
DLL = os.path.join(ROOT, 'ultrabridge', 'bin', 'Release', 'net472', 'UltraBridge.dll')
OUT = os.path.join(ROOT, 'dist', 'Ultracraft.zip')

for p in (JAR, DLL):
    if not os.path.isfile(p):
        sys.exit('missing: ' + p)

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    z.write(JAR, os.path.basename(JAR))
    z.write(DLL, os.path.basename(DLL))

size = os.path.getsize(OUT)
with zipfile.ZipFile(OUT) as z:
    names = z.namelist()
    bad = z.testzip()

lines = ['%s %d bytes' % (os.path.basename(OUT), size), 'entries: ' + ', '.join(names)]
for n in names:
    lines.append('  %s %d bytes' % (n, z.getinfo(n).file_size if False else 0))
lines.append('testzip: ' + ('OK' if bad is None else 'CORRUPT at ' + bad))
open(os.path.join(ROOT, 'tmp-zip-out.txt'), 'w', encoding='utf-8').write('\n'.join(lines))
print('\n'.join(lines))
