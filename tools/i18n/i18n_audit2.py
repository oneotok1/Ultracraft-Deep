import glob
import json
import os
import re

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')

# 收集所有 Java 字符串字面量（不只是 Component.literal 包裹的）
lit = re.compile(r'"((?:[^"\\]|\\.)*)"')
files = glob.glob(os.path.join(ROOT, 'fabric/src/main/java/**/*.java'), recursive=True)
allstr = set()
for f in files:
    s = open(f, encoding='utf-8').read()
    for m in lit.finditer(s):
        allstr.add(m.group(1))

print('源码字符串字面量总数(去重):', len(allstr))

lang = json.load(open(os.path.join(ROOT, 'fabric/src/main/resources/assets/ultracraft/lang/zh_cn.json'), encoding='utf-8'))
hit = [k for k in lang if k in allstr]
miss = [k for k in lang if k not in allstr]
print('zh_cn 键能在源码任意字面量中找到的:', len(hit), '/', len(lang))
print('找不到的:', len(miss))
for k in miss[:20]:
    print('   -', repr(k[:70]))
