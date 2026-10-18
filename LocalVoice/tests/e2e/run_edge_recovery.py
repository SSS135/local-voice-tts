from android_test import *

name='edge-recovery-queue'
adb('shell','cmd','connectivity','airplane-mode','enable');adb('shell','svc','wifi','disable');time.sleep(2)
(ROOT/'evidence/recovery-offline-connectivity.txt').write_text(adb('shell','dumpsys','connectivity'))
texts=['First queued sentence waits for the network.','Second queued sentence follows the first.','Third queued sentence finishes the sequence.']
adb('logcat','-c');probe(name,'edge:en-US-AriaNeural',queue=json.dumps(texts));start=time.monotonic()
for checkpoint in (20,40,65):
    while time.monotonic()-start<checkpoint:time.sleep(1)
    events=json.loads(adb('shell','cat','/sdcard/Android/data/org.localvoice.probe/files/events.json'))
    assert [e['detail'].split(':')[0] for e in events if e['event']=='request']==[f'{name}-{i}' for i in range(3)],events
    assert not any(e['event'] in ('done','error','firstPcm') for e in events),events
    (ROOT/'evidence'/f'{name}-pending-{checkpoint}.json').write_text(json.dumps(events,indent=2))
    (ROOT/'evidence'/f'{name}-memory-{checkpoint}.txt').write_text(adb('shell','dumpsys','meminfo','org.localvoice.tts'))
    print('Still pending without PCM/error/done at',round(time.monotonic()-start,1),'seconds',flush=True)
adb('shell','cmd','connectivity','airplane-mode','disable');adb('shell','svc','wifi','enable')
restored=time.monotonic();print('Network restoration requested',flush=True)
deadline=restored+120
while time.monotonic()<deadline:
    events=json.loads(adb('shell','cat','/sdcard/Android/data/org.localvoice.probe/files/events.json'))
    if any(e['event']=='error' for e in events):raise RuntimeError(events)
    if sum(e['event']=='done' for e in events)==3:break
    time.sleep(1)
else:raise RuntimeError('Recovery did not finish')
(ROOT/'evidence'/f'{name}-events.json').write_text(json.dumps(events,indent=2))
(ROOT/'evidence'/f'{name}-engine.log').write_text(adb('logcat','-d','-s','LocalVoice','LocalVoiceTiming','LocalVoiceStatus','LVProbe','-v','threadtime'))
for index in range(3):adb('pull',f'/sdcard/Android/data/org.localvoice.probe/files/{name}-{index}.wav',str(ROOT/'evidence'/f'{name}-{index}.wav'))
assert [e['detail'].split(':')[0] for e in events if e['event']=='done']==[f'{name}-{i}' for i in range(3)]
assert not any(e['event']=='latePcm' for e in events)
print('PASS: three queued utterances recovered in order after',round(restored-start,1),'seconds offline; recovery',round(time.monotonic()-restored,1),'seconds',flush=True)
