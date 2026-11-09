# -*- coding: utf-8 -*-
"""Print every hardcoded Chinese string literal in the C# bridge, with line numbers,
skipping the file whose table the overview already documents."""
import glob
import re

CJK = re.compile('[\u4e00-\u9fff]')
LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')

out = []
for path in sorted(glob.glob('*.cs')):
    if path == 'UpgradesZh.cs':
        continue
    lines = open(path, encoding='utf-8', errors='replace').read().split('\n')
    hits = []
    for i, line in enumerate(lines, 1):
        for m in LIT.finditer(line):
            if CJK.search(m.group(1)):
                hits.append((i, m.group(1)))
    if not hits:
        continue
    out.append('=' * 70)
    out.append('%s  (%d 条)' % (path, len(hits)))
    out.append('=' * 70)
    for ln, s in hits:
        s = s.replace('\\n', ' / ')
        out.append('  %4d  %s' % (ln, s))
    out.append('')

open('../../tmp-cs-dump.txt', 'w', encoding='utf-8').write('\n'.join(out))
print('wrote tmp-cs-dump.txt, %d lines' % len(out))
