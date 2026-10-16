#!/usr/bin/env python3
"""Split step 1b checks: per-mod Java file counts against the manifest, and every reference that crosses a
forbidden edge of the plan's dependency graph (plan section 2).

usage: python3 docs/split/check_split.py [--seams-md]   (run from the repo root)

Counts: each file's mod comes from its package root (dev.brights0ng.<root>); the manifest's mod column is the
expectation. Files the manifest does not list (step 1a's entry classes, 1b's mixin plugins and per-mod mixin config
tests) are counted separately.
Edges: a reference is an import, a static import or a fully qualified name in code (comments are ignored). Edges into
the same mod and allowed edges are skipped; references to EnginesAndEmpiresMod are reported apart.
"""
import csv, os, re, sys
from collections import Counter, defaultdict

ROOTS = {
    'paradynelib': 'paradyne_lib', 'sablefixes': 'sable_fixes', 'sparseores': 'sparse_ores',
    'seismicsurveyance': 'seismic_surveyance', 'meteodyne': 'meteodyne', 'frontier': 'frontier',
    'simplespoilage': 'simple_spoilage', 'enginesandempires': 'engines_and_empires',
}
ALLOWED = {
    'paradyne_lib': set(), 'sable_fixes': set(), 'sparse_ores': {'paradyne_lib'},
    'seismic_surveyance': {'paradyne_lib', 'sparse_ores'}, 'meteodyne': {'paradyne_lib'},
    'frontier': {'paradyne_lib'}, 'simple_spoilage': {'paradyne_lib'},
    'engines_and_empires': set(ROOTS.values()) - {'engines_and_empires'},
}
GATED_PACKAGE = 'dev.brights0ng.frontier.seismic'  # the only Frontier package that may import Seismic/Sparse Ores
REF = re.compile(r'\bdev\.brights0ng\.([a-z]+)((?:\.[a-z][a-z0-9_]*)*)\.([A-Z]\w*)')
OLD = 'dev/brights0ng/enginesandempires/'


def strip_comments(src):
    src = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group()), src, flags=re.S)
    return re.sub(r'//[^\n]*', '', src)


def java_files():
    for root in ('src/main/java', 'src/test/java'):
        for d, _, fs in os.walk(root):
            for f in sorted(fs):
                if f.endswith('.java'):
                    yield root.split('/')[1], os.path.join(d, f)


def mod_of(path):
    return ROOTS[path.split('/java/', 1)[1].split('/')[2]]


def manifest():
    with open('docs/split/mod-split-manifest.csv') as f:
        return list(csv.DictReader(l for l in f if not l.startswith('#')))


def counts():
    expected = Counter((r['kind'], r['mod']) for r in manifest())
    listed = {r['kind'] + ':' + r['mod'] + ':' + os.path.basename(r['path']) for r in manifest()}
    actual, extra = Counter(), defaultdict(list)
    for kind, path in java_files():
        mod = mod_of(path)
        if kind + ':' + mod + ':' + os.path.basename(path) in listed:
            actual[(kind, mod)] += 1
        else:
            extra[(kind, mod)].append(path.split('/java/', 1)[1])
    print(f'{"kind":5} {"mod":20} {"manifest":>8} {"tree":>6}  ok   not in manifest')
    ok = True
    for key in sorted(set(expected) | set(actual) | set(extra)):
        e, a = expected[key], actual[key]
        ok &= e == a
        print(f'{key[0]:5} {key[1]:20} {e:8} {a:6}  {"yes" if e == a else "NO ":3}  {len(extra[key])}')
    print(f'{"total":26} {sum(expected.values()):8} {sum(actual.values()):6}       {sum(map(len, extra.values()))}')
    for key in sorted(extra):
        for p in extra[key]:
            print(f'  not in manifest ({key[0]}, {key[1]}): {p}')
    # Every manifest row sits in its mod's tree.
    missing = []
    present = {(kind, mod_of(p), os.path.basename(p)) for kind, p in java_files()}
    for r in manifest():
        if (r['kind'], r['mod'], os.path.basename(r['path'])) not in present:
            missing.append(r)
    for r in missing:
        print('  MISSING from its mod:', r)
    return ok and not missing


def edges():
    rows, modid = [], []
    for kind, path in java_files():
        mod = mod_of(path)
        with open(path, encoding='utf-8') as fh:
            code = strip_comments(fh.read())
        pkg = re.search(r'^package ([\w.]+);', code, re.M).group(1)
        seen = set()
        for m in REF.finditer(code):
            root, sub, cls = m.groups()
            if root not in ROOTS:
                continue
            target = ROOTS[root]
            fq = f'dev.brights0ng.{root}{sub}.{cls}'
            if fq in seen:
                continue
            seen.add(fq)
            if cls == 'EnginesAndEmpiresMod' and mod != 'engines_and_empires':
                modid.append((kind, mod, path))
                continue
            if target == mod or target in ALLOWED[mod]:
                continue
            gated = (mod == 'frontier' and target in ('seismic_surveyance', 'sparse_ores'))
            if gated and pkg.startswith(GATED_PACKAGE):
                continue
            rows.append(('gated' if gated else 'forbidden', kind, mod, target,
                         path.split('/java/', 1)[1], fq.replace('.', '/') + '.java'))
    return rows, modid


def short(p):
    return p.replace('dev/brights0ng/', '')


def seams_md(rows):
    seam = {
        'FoodTemperature': 'S1', 'WindCommand': 'S2', 'WindShips': 'S2', 'FrontierDarkness': 'S3',
        'LevelChunkFrontierMixin': 'S4', 'CombustiveThumperBlockEntity': 'S5', 'SeismicShots': 'S5',
        'Incursion': 'S6', 'Incursions': 'S6', 'Deep': 'S7', 'FrontierDeepGameTests': 'S7',
        'PrdItemRenderer': 'S8', 'SmartLoggerRenderer': 'S8', 'DepositCommand': 'S9', 'ReadingDeposits': 'S9',
        'RichOreDataGen': 'S9', 'NoiseTerrainProbe': 'S10', 'SeasonSource': 'S11', 'GuardTypes': 'S12',
        'RefiningItems': 'S15',
    }
    order = lambda r: (int(seam.get(os.path.basename(r[4])[:-5], 'S99')[1:]), r[4], r[5])
    out = []
    for kind_, label in (('forbidden', 'Forbidden edges'), ('gated', 'Frontier -> Seismic/Sparse Ores, to be gated in `frontier.seismic`')):
        sel = sorted((r for r in rows if r[0] == kind_), key=order)
        out.append(f'\n## {label} ({len(sel)})\n')
        out.append('| Done | Seam | From mod | To mod | From file | References |')
        out.append('|---|---|---|---|---|---|')
        for _, kind, mod, target, src, dst in sel:
            name = os.path.basename(src)[:-5]
            src_txt = ('test: ' if kind == 'test' else '') + short(src)
            out.append(f'| [ ] | {seam.get(name, "?")} | {mod} | {target} | `{src_txt}` | `{short(dst)}` |')
    return '\n'.join(out)


if __name__ == '__main__':
    os.chdir(os.path.dirname(os.path.abspath(__file__)) + '/../..')
    rows, modid = edges()
    if '--seams-md' in sys.argv:
        print(seams_md(rows))
        sys.exit(0)
    ok = counts()
    print()
    f = [r for r in rows if r[0] == 'forbidden']
    g = [r for r in rows if r[0] == 'gated']
    print(f'forbidden references: {len(f)}; gated Frontier -> Seismic/Sparse Ores references: {len(g)}; '
          f'EnginesAndEmpiresMod references outside core: {len(modid)}')
    for r in rows:
        print(f'  {r[0]:9} {r[2]} -> {r[3]}: {short(r[4])} -> {short(r[5])}')
    for kind, mod, path in modid:
        print(f'  EnginesAndEmpiresMod in {mod}: {short(path)}')
    sys.exit(0 if ok else 1)
