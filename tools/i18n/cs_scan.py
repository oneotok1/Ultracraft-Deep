# -*- coding: utf-8 -*-
"""Scan the C# side for hardcoded Chinese strings and group them by file."""
import glob
import re
import sys

CJK = re.compile('[\u4e00-\u9fff]')
LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')

rows = []
for path in sorted(glob.glob('*.cs')):
    text = open(path, encoding='utf-8', errors='replace').read()
    n = 0
    for m in LIT.finditer(text):
        if CJK.search(m.group(1)):
            n += 1
    if n:
        rows.append((n, path))

rows.sort(reverse=True)
total = sum(n for n, _ in rows)
print('含中文的字符串字面量: %d 条，分布在 %d 个文件' % (total, len(rows)))
print()
for n, path in rows:
    print('  %-22s %d' % (path, n))
