"""Optional isolated client smoke test. Invoke with Xvfb and ImageMagick import paths.
No world/entity rendering is claimed: the screenshot proves only client startup.
"""
from pathlib import Path
import os,sys,time,subprocess,signal,json,hashlib
here=Path(__file__).resolve().parent; repo=here.parent.parent; e=here/'evidence'
target=repo/'neoforge/src/main/resources/assets/sexmod/geo/galath/galath.geo.json'
fresh=repo/'neoforge/src/main/resources/assets/sexmod/geo/pipeline-proof.geo.json'
original=target.read_bytes()
roundtrip=(e/'galath-roundtrip.geo.json').read_bytes()
new_geo=(e/'curved_shell.geo.json').read_bytes()
assert not fresh.exists()
env=dict(os.environ,DISPLAY=':193',LIBGL_ALWAYS_SOFTWARE='1',ALSOFT_DRIVERS='null')
xvfb=None; client=None
report={'scope':'client startup with roundtrip Galath and fresh geometry in resources; no entity screenshot'}
try:
    xvfb=subprocess.Popen([sys.argv[1],':193','-screen','0','1280x800x24','-nolisten','tcp'],stdout=(e/'xvfb.log').open('w'),stderr=subprocess.STDOUT)
    time.sleep(2)
    assert xvfb.poll() is None,'Xvfb failed'
    target.write_bytes(roundtrip)
    fresh.write_bytes(new_geo)
    with (e/'client.log').open('w') as log:
        client=subprocess.Popen(['./gradlew','runClient'],cwd=repo/'neoforge',env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        deadline=time.monotonic()+45
        while time.monotonic()<deadline and client.poll() is None:
            time.sleep(2)
        report['process_exit_before_capture']=client.poll()
        report['galath_sha256']=hashlib.sha256(roundtrip).hexdigest()
        report['fresh_sha256']=hashlib.sha256(new_geo).hexdigest()
        resource_dir=repo/'neoforge/build/resources/main/assets/sexmod/geo'
        report['classpath_resource_bytes_match']=(resource_dir/'galath/galath.geo.json').read_bytes()==roundtrip and (resource_dir/'pipeline-proof.geo.json').read_bytes()==new_geo
        report['mod_resource_reload_logged']='mod/sexmod' in (e/'client.log').read_text()
        shot=subprocess.run([sys.argv[2],'-window','root',str(e/'client-startup.png')],env=env,capture_output=True,text=True)
        report['screenshot_exit']=shot.returncode
        report['screenshot_error']=shot.stderr
finally:
    if client and client.poll() is None:
        os.killpg(client.pid,signal.SIGTERM)
        try: client.wait(timeout=10)
        except subprocess.TimeoutExpired: os.killpg(client.pid,signal.SIGKILL)
    if xvfb: xvfb.terminate(); xvfb.wait(timeout=10)
    target.write_bytes(original)
    if fresh.exists(): fresh.unlink()
    report['original_resources_restored']=target.read_bytes()==original and not fresh.exists()
    (e/'client-smoke.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
