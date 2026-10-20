// Checks Vitals.cs's transpiler anchors against an IL dump of ULTRAKILL's NewMovement:
//   ilspycmd -il -t NewMovement C:/SteamLibrary/steamapps/common/ULTRAKILL/ULTRAKILL_Data/Managed/Assembly-CSharp.dll > NewMovement.il
//   node docs/il_probe.cjs NewMovement.il
// Every predicate here mirrors one in the SteveMaxPatches transpilers, so a mismatch means that
// cap would silently stay at its old value in game (the bridge only logs how many it found).
const fs = require('fs');
const file = process.argv[2] || 'NewMovement.il';
const L = fs.readFileSync(file, 'utf8').split(/\r?\n/);

function body(name) {
  let end = -1;
  const needle = 'end of method NewMovement::' + name;
  for (let i = 0; i < L.length; i++) {
    const k = L[i].indexOf(needle);
    if (k < 0) continue;
    if (/[A-Za-z0-9_]/.test(L[i].charAt(k + needle.length))) continue; // StartSlide, Start, ...
    end = i;
  }
  if (end < 0) return null;
  let start = end;
  while (start > 0 && L[start].indexOf('.method') < 0) start--;
  const ins = [];
  for (let i = start; i <= end; i++) {
    const m = L[i].match(/^\s*IL_([0-9a-f]{4}): (.*)$/);
    if (m) ins.push({ off: 'IL_' + m[1], op: m[2].trim() });
  }
  return ins;
}

const isInt = (s, v) => s === 'ldc.i4.s ' + v || s === 'ldc.i4 ' + v;
const isFloat = (s, v) => s === 'ldc.r4 ' + v;
const isHp = s => /^ldfld int32 NewMovement::hp$/.test(s);
const isStoreHp = s => /^stfld int32 NewMovement::hp$/.test(s);

const out = [];
function run(name, fn) {
  const b = body(name);
  if (!b) { out.push(name + ': MISSING'); return; }
  const hits = [];
  for (let i = 0; i < b.length; i++) if (fn(b, i)) hits.push(b[i].off + ' ' + b[i].op);
  out.push(name + ': ' + hits.length + ' hit(s)' + (hits.length ? '  @ ' + hits.join(' | ') : ''));
}

run('GetHurt', (b, i) => i > 0 && isInt(b[i].op, 100) && isHp(b[i - 1].op));
run('ForceAntiHP', (b, i) => {
  const i100 = isInt(b[i].op, 100), f100 = isFloat(b[i].op, 100);
  if (i100 && i > 0 && isHp(b[i - 1].op)) return true;                       // hp > 100
  if (f100 && i > 1 && isHp(b[i - 2].op)) return true;                       // hp > 100f - amount
  if (i100 && i + 2 < b.length && isHp(b[i + 2].op)) return true;            // amount = 100 - hp
  if (f100 && i + 1 < b.length && b[i + 1].op === 'ldarg.1') return true;    // hp = RoundToInt(100f - amount)
  return false;
});
run('GetHealth', (b, i) => isFloat(b[i].op, 100) || isFloat(b[i].op, 200));
run('Respawn', (b, i) => i + 1 < b.length && (isInt(b[i].op, 200) || isInt(b[i].op, 100)) && isStoreHp(b[i + 1].op));
run('SuperCharge', (b, i) => i + 1 < b.length && isInt(b[i].op, 200) && isStoreHp(b[i + 1].op));
run('Start', (b, i) => i + 1 < b.length && isInt(b[i].op, 200) && isStoreHp(b[i + 1].op));

console.log(out.join('\n'));
