"""
Checks that align.py gives the same map as abAlign() in the web view (audiobook.js), on random books and
transcripts with dropped, added and missing words. Needs Node.js. Run: python3 companion/tests/parity.py
"""
import json, os, re, subprocess, sys, tempfile
here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(here))
from align import align  # noqa: E402

js_src = open(os.path.join(here, '..', '..', 'app', 'src', 'main', 'assets', 'web', 'audiobook.js'), encoding='utf-8').read()
a = js_src.index('// ------------------------------------------------------------------ alignment')
b = js_src.index('// ------------------------------------------------------------------ in the reader')
tmp = tempfile.mkdtemp()
open(os.path.join(tmp, 'ab.js'), 'w', encoding='utf-8').write(js_src[a:b] + '\nmodule.exports={abAlign};\n')
open(os.path.join(tmp, 'run.js'), 'w').write(
    "const {abAlign}=require('./ab.js');const c=JSON.parse(require('fs').readFileSync(0,'utf8'));"
    "process.stdout.write(JSON.stringify(c.map(x=>{const m=abAlign(x.text,x.segments);delete m.created;return m;})));")
bad = 0
for seed in range(1, 9):
    out = subprocess.run([sys.executable, os.path.join(here, 'gen_cases.py'), str(seed)], cwd=tmp, check=True)
    cases = json.load(open(os.path.join(tmp, 'cases.json'), encoding='utf-8'))
    if seed % 2 == 0:  # a stretch of the audio missing
        for x in cases:
            n = len(x['segments']); x['segments'] = x['segments'][:n // 3] + x['segments'][2 * n // 3:]
    js = json.loads(subprocess.run(['node', 'run.js'], cwd=tmp, input=json.dumps(cases, ensure_ascii=False),
                                   capture_output=True, text=True, check=True).stdout)
    for c, j in zip(cases, js):
        m = align(c['text'], c['segments']); del m['created']
        if m != j:
            bad += 1
            print('DIFFERENT for seed', seed)
print('parity:', 'ok' if not bad else f'{bad} different'); sys.exit(1 if bad else 0)
