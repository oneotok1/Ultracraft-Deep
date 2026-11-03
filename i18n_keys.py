"""List Component.translatable keys in source and diff against en_us / zh_cn."""
import glob
import json
import re

PAT = re.compile(r'Component\.translatable\(\s*"((?:[^"\\]|\\.)*)"')

keys = set()
for path in glob.glob('fabric/src/main/java/**/*.java', recursive=True):
    src = open(path, encoding='utf-8').read()
    for m in PAT.finditer(src):
        raw = m.group(1)
        if '\\' in raw:
            raw = raw.encode('utf-8').decode('unicode_escape').encode('latin1').decode('utf-8')
        keys.add(raw)

zh = json.load(open('fabric/src/main/resources/assets/ultracraft/lang/zh_cn.json', encoding='utf-8-sig'))
en = json.load(open('fabric/src/main/resources/assets/ultracraft/lang/en_us.json', encoding='utf-8-sig'))

# keys written as plain literals that the audit previously recorded as already-translated
print('source keys:', len(keys), 'zh keys:', len(zh), 'en keys:', len(en))
missing_zh = sorted(k for k in keys if k not in zh)
missing_en = sorted(k for k in keys if k not in en)
print('missing in zh:', len(missing_zh))
print('missing in en:', len(missing_en))
json.dump(missing_zh, open('/tmp/missing_zh.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
for k in missing_zh:
    print('  ', repr(k))
