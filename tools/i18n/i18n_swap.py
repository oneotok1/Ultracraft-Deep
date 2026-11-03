# -*- coding: utf-8 -*-
"""Convert pure-literal Component.literal("X") to Component.translatable("X").

Rationale: the translation table falls back to the key verbatim when a key is
missing, so an English string used as its own key keeps the current English
rendering while letting zh_cn.json supply a Chinese value.

Skipped on purpose:
  - literals containing '%' (format placeholders need real args)
  - whitespace / punctuation-only literals
"""
import os
import re
import sys

ROOT = 'fabric/src/main/java/dev/ultracraft/'
PATTERN = re.compile(r'Component\.literal\((\"(?:[^\"\\\\]|\\\\.)*\")\)')
TRIVIAL = re.compile(r'^[\s\-|:/.,+]*$')


def unquote(s):
    return s[1:-1]


def main(apply):
    cnt = {'total': 0, 'changed': 0, 'pct': 0, 'trivial': 0}
    per_file = {}
    for fn in sorted(os.listdir(ROOT)):
        if not fn.endswith('.java'):
            continue
        path = ROOT + fn
        src = open(path, encoding='utf-8').read()
        n = [0]

        def repl(m):
            lit = m.group(1)
            value = unquote(lit)
            cnt['total'] += 1
            if '%' in value:
                cnt['pct'] += 1
                return m.group(0)
            if TRIVIAL.match(value):
                cnt['trivial'] += 1
                return m.group(0)
            n[0] += 1
            cnt['changed'] += 1
            return 'Component.translatable(%s)' % lit

        out = PATTERN.sub(repl, src)
        if n[0]:
            per_file[fn] = n[0]
            if apply:
                open(path, 'w', encoding='utf-8', newline='').write(out)

    for fn in sorted(per_file, key=lambda k: -per_file[k]):
        print('  %-28s %d' % (fn, per_file[fn]))
    print()
    print('total literal sites      : %d' % cnt['total'])
    print('converted                : %d' % cnt['changed'])
    print('skipped (has %% placeholders): %d' % cnt['pct'])
    print('skipped (punctuation only) : %d' % cnt['trivial'])
    if not apply:
        print()
        print('DRY RUN - pass --apply to write')


if __name__ == '__main__':
    main('--apply' in sys.argv)
