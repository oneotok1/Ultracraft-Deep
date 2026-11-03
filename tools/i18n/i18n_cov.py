# -*- coding: utf-8 -*-
"""Coverage check: which hard-coded Component.literal("...") strings does zh_cn.json cover?"""
import json
import os
import re

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'fabric/src/main/java/dev/ultracraft/')
LANG = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'fabric/src/main/resources/assets/ultracraft/lang/')

plain = re.compile(r'Component\.literal\("((?:[^"\\]|\\.)*)"\)')

zh = json.load(open(LANG + 'zh_cn.json', encoding='utf-8-sig'))

lits = {}
for fn in sorted(os.listdir(ROOT)):
    if not fn.endswith('.java'):
        continue
    for i, line in enumerate(open(ROOT + fn, encoding='utf-8'), 1):
        for m in plain.finditer(line):
            lits.setdefault(m.group(1), []).append('%s:%d' % (fn, i))

covered = [k for k in lits if k in zh]
missing = sorted(k for k in lits if k not in zh)

print('pure literals (deduped): %d' % len(lits))
print('covered by zh_cn.json : %d' % len(covered))
print('missing               : %d' % len(missing))
print()
print('--- missing, first 40 ---')
for k in missing[:40]:
    print(' * [%s] %s' % (lits[k][0], k[:110].replace('\n', ' / ')))

english_keys = [k for k in zh if ' ' in k or (re.search(r'[a-z]{3}', k) and not re.match(r'^[a-z0-9_.]+$', k))]
print()
print('zh_cn.json keys that look like English UI text: %d / %d' % (len(english_keys), len(zh)))
