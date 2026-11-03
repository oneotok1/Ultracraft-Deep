import re, glob, json

files = glob.glob('fabric/src/main/java/**/*.java', recursive=True)
pat = re.compile(r'Component\.literal\(\s*"((?:[^"\\]|\\.)*)"\s*\)')
pat_c = re.compile(r'Component\.literal\(\s*"(?:[^"\\]|\\.)*"\s*\+')

static = 0
concat = 0
allstr = set()
samples = []
for f in files:
    s = open(f, encoding='utf-8').read()
    for m in pat.finditer(s):
        static += 1
        allstr.add(m.group(1))
        if len(samples) < 5:
            samples.append(m.group(1)[:40])
    concat += len(pat_c.findall(s))

print('Component.literal 纯静态字符串:', static)
print('Component.literal 带拼接(+):', concat)
print('样例:', samples)
print()

lang = json.load(open('fabric/src/main/resources/assets/ultracraft/lang/zh_cn.json', encoding='utf-8'))
hit = [k for k in lang if k in allstr]
print('zh_cn 键能在代码静态 literal 里精确匹配的:', len(hit), '/', len(lang))
print('匹配样例:', hit[:8])
print()

# 反向：zh_cn 键在代码里完全找不到的（即"死键"，除非是 OptionInstance caption 走的翻译路径）
miss = [k for k in lang if k not in allstr]
print('代码里找不到的 zh_cn 键:', len(miss))
for k in miss[:12]:
    print('   -', k[:70])
