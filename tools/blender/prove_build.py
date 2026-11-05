"""Run inside nix develop. Temporarily substitute Galath, build, verify the jar, restore."""
from pathlib import Path
import hashlib, subprocess, zipfile, json
here=Path(__file__).resolve().parent
repo=here.parent.parent
asset='assets/sexmod/geo/galath/galath.geo.json'
target=repo/'neoforge/src/main/resources'/asset
original=target.read_bytes()
exported=(here/'evidence/galath-roundtrip.geo.json').read_bytes()
try:
    target.write_bytes(exported)
    subprocess.run(['./gradlew','build'],cwd=repo/'neoforge',check=True)
    jars=[p for p in (repo/'neoforge/build/libs').glob('*.jar') if not p.name.endswith(('-sources.jar','-javadoc.jar'))]
    assert len(jars)==1,jars
    with zipfile.ZipFile(jars[0]) as jar:
        packaged=jar.read(asset)
    assert packaged==exported
    report={'build':'PASS','jar':jars[0].name,'resource':asset,
            'exported_sha256':hashlib.sha256(exported).hexdigest(),
            'packaged_sha256':hashlib.sha256(packaged).hexdigest()}
finally:
    target.write_bytes(original)
assert target.read_bytes()==original
report['original_restored']=True
(here/'evidence/build-swap.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
