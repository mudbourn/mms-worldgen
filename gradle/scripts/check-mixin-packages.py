"""Fail if a class sits in a mixin-owned package without being a declared mixin.

Mixin claims every class under a config's `package` and refuses to let anything
there be loaded as a normal class (IllegalClassLoadError). A plain helper class
put in a mixin package therefore compiles, packages, and only explodes the first
time it is touched at runtime.
"""
import json, sys, zipfile

jar = sys.argv[1]
z = zipfile.ZipFile(jar)
fm = json.loads(z.read('fabric.mod.json'))

configs = {}  # package -> (config name, declared simple names, plugin fqn)
for cfg in fm.get('mixins', []):
    name = cfg['config'] if isinstance(cfg, dict) else cfg
    m = json.loads(z.read(name))
    declared = set(m.get('mixins', [])) | set(m.get('client', [])) | set(m.get('server', []))
    configs[m['package']] = (name, declared, m.get('plugin'))

bad = []
for entry in z.namelist():
    if not entry.endswith('.class') or '$' in entry:
        continue
    fq = entry[:-len('.class')].replace('/', '.')
    owner = max((p for p in configs if fq.startswith(p + '.')), key=len, default=None)
    if owner is None:
        continue
    cfg_name, declared, plugin = configs[owner]
    rel = fq[len(owner) + 1:]
    if rel in declared or fq == plugin:
        continue
    bad.append((fq, cfg_name))

for fq, cfg_name in bad:
    print(f"!! {fq} is in mixin package owned by {cfg_name} but is not declared "
          f"there — move it out or add it to the config", file=sys.stderr)
print(f"mixin package check: {'FAILED' if bad else 'OK'} ({len(bad)} offender(s))")
sys.exit(1 if bad else 0)
