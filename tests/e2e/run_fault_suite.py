from android_test import *
import urllib.request,wave,hashlib

OUT=ROOT/'evidence/reliability';OUT.mkdir(exist_ok=True)
TRACE=OUT/'fixture-trace.jsonl'
HTTP=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def control(actions,repeat=True):
    HTTP.open(urllib.request.Request('http://127.0.0.1:8765/control',data=json.dumps({'actions':actions,'repeat_last':repeat}).encode(),headers={'Content-Type':'application/json'}),timeout=5).read()
def rows():return [json.loads(line) for line in TRACE.read_text().splitlines()] if TRACE.exists() else []
def events():
    try:return json.loads(adb('shell','cat','/sdcard/Android/data/org.localvoice.probe/files/events.json'))
    except (ValueError,subprocess.CalledProcessError):return []
def wait_for(check,timeout=180):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        value=check()
        if value:return value
        time.sleep(.25)
    raise AssertionError('Timed out waiting for controlled condition')
def pcm(name):
    with wave.open(str(ROOT/'evidence'/f'{name}.wav'),'rb') as audio:
        assert (audio.getframerate(),audio.getnchannels(),audio.getsampwidth())==(24000,1,2)
        return audio.readframes(audio.getnframes())
def finish_trace(name,start):
    result=rows()[start:];(OUT/f'{name}-server.json').write_text(json.dumps(result,indent=2));return result
def success(events):
    assert sum(e['event']=='done' for e in events)==1,events
    assert not any(e['event'] in ('error','exception','latePcm') for e in events),events
def output_hashes(trace):
    by_id={r['attempt_id']:r for r in trace if r['event']=='attempt'}
    return [by_id[r['attempt_id']]['speak_sha256'] for r in trace if r['event']=='served_complete']
def collected(name):
    collect(name); result=json.loads((ROOT/'evidence'/f'{name}-events.json').read_text());return result
def stop_latency(result):
    assert not any(e['event'] in ('done','error','latePcm') for e in result),result
    assert any(e['event']=='stop' for e in result),result
    returned=next(e for e in result if e['event'] in ('stopRequested','stopReturned'))
    duration=int(returned['detail'].split('durationMs=')[1]);assert duration<2000,duration
    return duration

longtext=('The first public sentence describes a blue river beneath the morning sky. '
          'The second public sentence follows a silver lantern along a mountain path. '
          'The third public sentence watches distant birds above a quiet village. '
          'The fourth public sentence recalls a kind traveler beside a stone bridge. '
          'The fifth public sentence marks the final page of this controlled test.')
summary={}
control([{'type':'success'}]);start=len(rows())
baseline=run_case('fixture-baseline','edge:en-US-AriaNeural',longtext);success(baseline)
baseline_pcm=pcm('fixture-baseline');baseline_trace=finish_trace('fixture-baseline',start)
expected=output_hashes(baseline_trace);assert len(expected)>=2
summary['baseline']={'chunks':len(expected),'pcmBytes':len(baseline_pcm),'pcmSha256':hashlib.sha256(baseline_pcm).hexdigest()}

control([{'type':'close','bytes':800},{'type':'truncate','drop_bytes':1},{'type':'stall','seconds':6},{'type':'success','fragment_bytes':1031}]);start=len(rows())
faulted=run_case('fixture-fault-recovery','edge:en-US-AriaNeural',longtext);success(faulted)
trace=finish_trace('fixture-fault-recovery',start)
assert pcm('fixture-fault-recovery')==baseline_pcm
assert output_hashes(trace)==expected
attempts=[r for r in trace if r['event']=='attempt'];assert len(attempts)==len(expected)+3,attempts
assert len({r['speak_sha256'] for r in attempts[:4]})==1
summary['partialTruncateStall']={'attempts':len(attempts),'orderedChunks':len(expected),'pcmIdentical':True}
print('PASS partial close, truncated MP3 end and stalled response: exact baseline PCM once per ordered chunk',flush=True)

control([{'type':'http_fail'}]);start=len(rows());probe('fixture-repeated-recovery','edge:en-US-AriaNeural','A public sentence remains pending through repeated failures.')
wait_for(lambda:sum(r['event']=='http_fail' for r in rows()[start:])>=7)
pending=events();assert not any(e['event'] in ('firstPcm','done','error') for e in pending)
(OUT/'fixture-repeated-pending.json').write_text(json.dumps(pending,indent=2))
(OUT/'fixture-repeated-memory.txt').write_text(adb('shell','dumpsys','meminfo','org.localvoice.tts'))
control([{'type':'success'}]);wait_for(lambda:any(e['event']=='done' and e['detail'].startswith('fixture-repeated-recovery') for e in events()))
result=collected('fixture-repeated-recovery');success(result);trace=finish_trace('fixture-repeated-recovery',start)
summary['repeatedRecovery']={'transient503':sum(r['event']=='http_fail' for r in trace),'completed':True}
print('PASS seven transient failures retained the same pending request, then recovered',flush=True)

for name,action,delay in [('fixture-stop-backoff',{'type':'http_fail'},1800),('fixture-stop-inflight',{'type':'stall','seconds':60},500)]:
    control([action]);start=len(rows());result=run_case(name,'edge:en-US-AriaNeural','This public request must be cancelled without later audio.',stopMs=delay)
    latency=stop_latency(result);after=rows()[start:];count=len(after);time.sleep(2);trace=finish_trace(name,start)
    assert len(trace)==count,trace
    if action['type']=='stall':assert any(r['event']=='stalled' for r in trace)
    summary[name]={'stopCallMs':latency,'latePcm':False}
    print('PASS',name,'stop call',latency,'ms',flush=True)

control([{'type':'stall','seconds':60},{'type':'success'}]);start=len(rows())
replacement=run_case('fixture-replacement','edge:en-US-AriaNeural','The old public utterance waits.',replacement='The replacement public utterance completes.',replacementVoice='edge:ru-RU-SvetlanaNeural',replaceMs=500)
assert any(e['event']=='stop' and e['detail'].startswith('fixture-replacement') for e in replacement)
assert sum(e['event']=='done' and e['detail'].startswith('replacement:') for e in replacement)==1
assert not any(e['event'] in ('error','latePcm') for e in replacement)
trace=finish_trace('fixture-replacement',start);assert len(output_hashes(trace))==1
summary['replacement']={'oldPcmBytes':0,'replacementCompleted':True,'latePcm':False}
(OUT/'fault-suite-summary.json').write_text(json.dumps(summary,indent=2))
print('PASS controlled Edge fault suite',json.dumps(summary),flush=True)
