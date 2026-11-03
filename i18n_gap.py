# -*- coding: utf-8 -*-
"""List translation keys referenced by Java source but absent from zh_cn.json."""
import json
import os
import re

ROOT = 'fabric/src/main/java/dev/ultracraft/'
LANG = 'fabric/src/main/resources/assets/ultracraft/lang/'

pat = re.compile(r'Component\.translatable\((\"(?:[^\"\\]|\\.)*\")')
zh = json.load(open(LANG + 'zh_cn.json', encoding='utf-8-sig'))

refs = {}
for fn in sorted(os.listdir(ROOT)):
    if not fn.endswith('.java'):
        continue
    for i, line in enumerate(open(ROOT + fn, encoding='utf-8'), 1):
        for m in pat.finditer(line):
            key = m.group(1)[1:-1]
            refs.setdefault(key, []).append('%s.java:%d' % (fn[:-5], i))

missing = sorted(k for k in refs if k not in zh)
print('distinct keys referenced from Java: %d' % len(refs))
print('present in zh_cn.json            : %d' % (len(refs) - len(missing)))
print('MISSING                          : %d' % len(missing))
print()
for k in missing:
    print('  %-70s  %s' % (k[:70], refs[k][0]))
