"""Edge fault suite against tests/edge-fault-fixture (3 s tone, words on); see tests/edge-fault-fixture/README.md.
Runs every case, prints PASS/FAIL per case and writes evidence/reliability/fault-suite-summary.json."""
from android_test import *
import urllib.request,wave,hashlib,traceback

OUT=ROOT/'evidence/reliability';OUT.mkdir(exist_ok=True)
TRACE=OUT/'fixture-trace.jsonl'
FILES='/sdcard/Android/data/org.localvoice.probe/files/'
VOICE='edge:en-US-AriaNeural'
HTTP=urllib.request.build_opener(urllib.request.ProxyHandler({}))
PACED={'type':'success','fragment_bytes':288,'fragment_delay':0.1}
def control(actions,repeat=True):
    HTTP.open(urllib.request.Request('http://127.0.0.1:8765/control',data=json.dumps({'actions':actions,'repeat_last':repeat,'words':True}).encode(),headers={'Content-Type':'application/json'}),timeout=5).read()
def rows():return [json.loads(line) for line in TRACE.read_text().splitlines()] if TRACE.exists() else []
def events():
    try:return json.loads(adb('shell','cat',FILES+'events.json'))
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
def success(events,last=None):
    done=[e for e in events if e['event']=='done' and (last is None or e['detail'].startswith(last))]
    assert len(done)==1,events
    assert not any(e['event'] in ('error','exception','latePcm') for e in events),events
def tried(trace):return [r for r in trace if r['event']=='attempt']
def sha(text):return hashlib.sha256(text.encode()).hexdigest()
def resumed_at(text,text_hash):
    """Index of the word whose text suffix the attempt sent (0 = whole text), else None."""
    for i,word in enumerate(re.finditer(r"[\w']+",text)):
        if sha(text[word.start():])==text_hash:return i
    return None
def engine_log(name):return (ROOT/'evidence'/f'{name}-engine.log').read_text(encoding='utf-8')
def stop_latency(result):
    assert not any(e['event'] in ('done','error','latePcm') for e in result),result
    assert any(e['event']=='stop' for e in result),result
    returned=next(e for e in result if e['event'] in ('stopRequested','stopReturned'))
    duration=int(returned['detail'].split('durationMs=')[1]);assert duration<2000,duration
    return duration
def chain(name,paragraphs,last,timeout=120,**extras):
    """Chromium/Edge Read Aloud pattern: speak(QUEUE_FLUSH) per paragraph, the next only after onDone."""
    local=OUT/f'{name}-chain.json';local.write_text(json.dumps(paragraphs))
    adb('push',str(local),FILES+'chain.json');adb('shell','rm','-f',FILES+'chain-*.pcm')
    adb('logcat','-c');probe(name,VOICE,None,chainFile='chain.json',**extras)
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        result=events()
        if any(e['event']=='done' and e['detail'].startswith(last) or e['event']=='error' for e in result):break
        time.sleep(1)
    else:raise AssertionError('Timed out '+name)
    time.sleep(3);collect(name)
    return json.loads((ROOT/'evidence'/f'{name}-events.json').read_text())

summary={}
ONLY=set(sys.argv[1:])  # optional case names; baseline feeds decline_after_partial_audio
def case(fn):
    if ONLY and fn.__name__ not in ONLY:return
    try:
        summary[fn.__name__]={'pass':True,'detail':fn()};print('PASS',fn.__name__,json.dumps(summary[fn.__name__]['detail']),flush=True)
    except Exception as error:
        traceback.print_exc();summary[fn.__name__]={'pass':False,'detail':repr(error)[:2000]};print('FAIL',fn.__name__,flush=True)
    (OUT/'fault-suite-summary.json').write_text(json.dumps(summary,indent=2))

P=['The first public paragraph describes a blue river beneath the morning sky and a boat drifting slowly past the old mill.',
   'The second public paragraph follows a silver lantern along a mountain path where pine trees whisper in the evening wind.',
   'The third public paragraph watches distant birds above a quiet village while children gather apples near the stone wall.',
   'The fourth public paragraph recalls a kind traveler beside a stone bridge who shares bread with a tired shepherd.',
   'The fifth public paragraph marks the final page of this controlled test and closes the book on a calm night.']
state={}

@case
def baseline():
    # Three clean runs: identical PCM, and the learned render speed drops below the handoff threshold (rtf < 0.5).
    hashes=[]
    for run in range(3):
        control([{'type':'success'}]);start=len(rows())
        result=run_case('fixture-baseline','edge:en-US-AriaNeural',P[0]);success(result)
        trace=finish_trace('fixture-baseline',start);assert len(tried(trace))==1,trace
        hashes.append(hashlib.sha256(pcm('fixture-baseline')).hexdigest())
    assert len(set(hashes))==1,hashes
    state['pcm']=pcm('fixture-baseline')
    return {'pcmBytes':len(state['pcm']),'pcmSha256':hashes[0]}

# Two paragraphs per request: the last segment is buffered, so its tail is held whatever the learned render speed
# (a live last segment is held only below rtf 0.5, which the emulator's MediaCodec MP3 decode does not reach).
PAIRS=[P[0]+'\n\n'+P[1],P[2]+'\n\n'+P[3]]

@case
def tail_handoff_next_transient():
    # Request 2 claims request 1's tail; its first paragraph meets the spare failing at handshake and two failed
    # attempts, then recovers. No error.
    control([{'type':'success'},{'type':'success'},{'type':'http_fail'},{'type':'http_fail'},{'type':'http_fail'},{'type':'success'}]);start=len(rows())
    result=chain('fixture-tail-transient',PAIRS,'chain-1');success(result,'chain-1')
    assert sum(e['event']=='done' for e in result)==2,result
    trace=finish_trace('fixture-tail-transient',start);log=engine_log('fixture-tail-transient')
    assert 'claimed=true' in log,log[-3000:]
    fails=sum(r['event']=='http_fail' for r in trace);assert fails==3,fails
    return {'httpFail':fails,'tailClaimed':True}

@case
def tail_handoff_next_fatal():
    # Request 2 fails fatally (HTTP 400): request 1's claimed tail is still handed first, then one error, no retry storm.
    control([{'type':'success'},{'type':'success'},{'type':'http_fail','status':400}]);start=len(rows())
    result=chain('fixture-tail-fatal',PAIRS,'chain-1');time.sleep(3)
    trace=finish_trace('fixture-tail-fatal',start);log=engine_log('fixture-tail-fatal')
    assert any(e['event']=='done' and e['detail'].startswith('chain-0') for e in result),result
    assert any(e['event']=='error' and e['detail'].startswith('chain-1') for e in result),result
    assert any(e['event']=='firstPcm' and e['detail'].startswith('chain-1') for e in result),'tail not handed'
    assert 'claimed=true' in log;assert not any(e['event']=='latePcm' for e in result)
    fails=sum(r['event']=='http_fail' for r in trace);assert fails==2,fails
    return {'httpFail':fails,'tailHanded':True}

@case
def tail_handoff_next_declined():
    # Request 2's first paragraph is declined on the warm spare: tail plays, that paragraph is silent, the next one plays.
    control([{'type':'success'},{'type':'success'},{'type':'no_audio'},{'type':'success'}]);start=len(rows())
    result=chain('fixture-tail-declined',PAIRS,'chain-1');success(result,'chain-1')
    trace=finish_trace('fixture-tail-declined',start)
    assert [r['action'] for r in tried(trace)]==['success','success','no_audio','success'],tried(trace)
    assert 'claimed=true' in engine_log('fixture-tail-declined')
    return {'attempts':len(tried(trace)),'tailClaimed':True}

@case
def drop_mid_stream():
    # Connection aborts after ~40% of the audio (words sent with it); the retry resumes at the word being spoken.
    control([{'type':'close','bytes':7200,'fragment_bytes':576,'seconds':0.5},{'type':'success'}]);start=len(rows())
    result=run_case('fixture-drop-mid','edge:en-US-AriaNeural',P[0]);success(result)
    trace=finish_trace('fixture-drop-mid',start);attempts=tried(trace)
    closed=next(r for r in trace if r['event']=='mid_response_close')
    assert len(attempts)==2,attempts
    k=resumed_at(P[0],attempts[1]['text_sha256'])
    assert k is not None and max(1,closed['words_sent']-3)<=k<=closed['words_sent'],(k,closed)
    return {'wordsSent':closed['words_sent'],'resumedAtWord':k,'retryConnection':attempts[1]['connection_id'],'pcmBytes':len(pcm('fixture-drop-mid'))}

@case
def decline_after_partial_audio():
    # Close 1007 after audio started: the rest of the fragment is skipped, no retry, no error.
    control([{'type':'close','bytes':7200,'fragment_bytes':576,'seconds':0.5,'code':1007},{'type':'success'}]);start=len(rows())
    result=run_case('fixture-decline-partial','edge:en-US-AriaNeural',P[1]);success(result);time.sleep(2)
    trace=finish_trace('fixture-decline-partial',start)
    assert len(tried(trace))==1,tried(trace)
    played=len(pcm('fixture-decline-partial'));assert 0<played<len(state['pcm']),played
    return {'attempts':1,'pcmBytes':played}

@case
def decline_before_audio():
    control([{'type':'no_audio'}]);start=len(rows())
    result=run_case('fixture-decline','edge:en-US-AriaNeural',P[2]);success(result);time.sleep(2)
    trace=finish_trace('fixture-decline',start);assert len(tried(trace))==1,tried(trace)
    return {'attempts':1}

@case
def spare_closed_before_use():
    # Two paragraphs: the spare opened during paragraph 1 is closed by the server (1001) before paragraph 2 uses it.
    control([{'type':'success'},{'type':'idle_close'},{'type':'success'}]);start=len(rows())
    result=run_case('fixture-spare-closed','edge:en-US-AriaNeural',P[0]+'\n\n'+P[1]);success(result)
    trace=finish_trace('fixture-spare-closed',start);attempts=tried(trace)
    assert any(r['event']=='idle_closed' for r in trace),trace
    assert sorted(r['text_sha256'] for r in attempts)==sorted([sha(P[0]),sha(P[1])]),attempts
    return {'attempts':len(attempts),'connections':sum(r['event']=='upgrade' for r in trace)}

@case
def spare_stale():
    # The spare accepts paragraph 2's SSML and never answers: one attempt timeout, then a fresh connection.
    control([{'type':'success'},{'type':'stall','seconds':30},{'type':'success'}]);start=len(rows())
    result=run_case('fixture-spare-stale','edge:en-US-AriaNeural',P[0]+'\n\n'+P[1]);success(result)
    trace=finish_trace('fixture-spare-stale',start);attempts=tried(trace)
    second=[r for r in attempts if r['text_sha256']==sha(P[1])]
    assert [r['action'] for r in second]==['stall','success'],second
    return {'paragraph2Attempts':len(second)}

@case
def outage_guard():
    # Five paragraphs all declined: 3 skipped once each, the 4th retried with backoff (no storm) until recovery.
    control([{'type':'no_audio'}]);start=len(rows())
    probe('fixture-outage','edge:en-US-AriaNeural','\n\n'.join(P))
    fourth=sha(P[3])
    wait_for(lambda:sum(r['event']=='attempt' and r['text_sha256']==fourth for r in rows()[start:])>=4,120)
    control([{'type':'success'}])
    wait_for(lambda:any(e['event'] in ('done','error') and e['detail'].startswith('fixture-outage') for e in events()),120)
    collect('fixture-outage');result=json.loads((ROOT/'evidence'/'fixture-outage-events.json').read_text());success(result)
    trace=finish_trace('fixture-outage',start);attempts=tried(trace)
    for text in P[:3]:assert sum(r['text_sha256']==sha(text) for r in attempts)==1,(text,attempts)
    times=[r['time_ns'] for r in attempts if r['text_sha256']==fourth]
    gaps=[(b-a)//1000000 for a,b in zip(times,times[1:])];assert min(gaps)>=180,gaps
    return {'fourthAttempts':len(times),'gapsMs':gaps}

@case
def fault_sequence():
    # Former suite: abort after 800 bytes, truncated final frame (accepted: incomplete frame dropped), stall; recovers.
    control([{'type':'close','bytes':800},{'type':'truncate','drop_bytes':1},{'type':'stall','seconds':6},{'type':'success','fragment_bytes':1031}]);start=len(rows())
    result=run_case('fixture-fault-recovery','edge:en-US-AriaNeural',P[0]);success(result)
    trace=finish_trace('fixture-fault-recovery',start)
    return {'attempts':len(tried(trace)),'actions':[r['action'] for r in tried(trace)]}

@case
def slow_stream():
    # 3 s of audio paced over ~6.4 s, longer than the 2 s attempt timeout: steady progress is not a timeout.
    control([PACED,{'type':'success'}]);start=len(rows())
    result=run_case('fixture-slow-stream','edge:en-US-AriaNeural',P[4]);success(result)
    trace=finish_trace('fixture-slow-stream',start);assert len(tried(trace))==1,tried(trace)
    return {'attempts':1,'pcmBytes':len(pcm('fixture-slow-stream'))}

@case
def stop_during_streaming():
    control([PACED,{'type':'success'}]);start=len(rows())
    result=run_case('fixture-stop-streaming','edge:en-US-AriaNeural',P[0],stopOnFirstPcm=True)
    latency=stop_latency(result);count=len(tried(rows()[start:]));time.sleep(3)
    trace=finish_trace('fixture-stop-streaming',start)
    assert len(tried(trace))==count==1,tried(trace)
    assert any(r['event'] in ('peer_cancelled','disconnected','client_closed') and r.get('attempt_id')==tried(trace)[0]['attempt_id'] for r in trace),trace
    return {'stopCallMs':latency,'latePcm':False}

@case
def seek_during_streaming():
    # Edge-style tap/skip: stop() then speak() of the next paragraph while paragraph 1 streams.
    control([PACED,{'type':'success'}]);start=len(rows())
    result=chain('fixture-seek-streaming',P[:2],'restart-1',restartMs=400)
    assert any(e['event']=='stop' and e['detail'].startswith('chain-0') for e in result),result
    success(result,'restart-1')
    trace=finish_trace('fixture-seek-streaming',start)
    return {'attempts':len(tried(trace)),'restartCompleted':True}

@case
def repeated_recovery():
    control([{'type':'http_fail'}]);start=len(rows());probe('fixture-repeated-recovery','edge:en-US-AriaNeural','A public sentence remains pending through repeated failures.')
    wait_for(lambda:sum(r['event']=='http_fail' for r in rows()[start:])>=7)
    pending=events();assert not any(e['event'] in ('firstPcm','done','error') for e in pending)
    control([{'type':'success'}]);wait_for(lambda:any(e['event']=='done' and e['detail'].startswith('fixture-repeated-recovery') for e in events()))
    collect('fixture-repeated-recovery');result=json.loads((ROOT/'evidence'/'fixture-repeated-recovery-events.json').read_text());success(result)
    trace=finish_trace('fixture-repeated-recovery',start)
    return {'transient503':sum(r['event']=='http_fail' for r in trace)}

@case
def stop_backoff_and_inflight():
    out={}
    for name,action,delay in [('fixture-stop-backoff',{'type':'http_fail'},1800),('fixture-stop-inflight',{'type':'stall','seconds':60},500)]:
        control([action]);start=len(rows());result=run_case(name,'edge:en-US-AriaNeural','This public request must be cancelled without later audio.',stopMs=delay)
        latency=stop_latency(result);count=len(rows()[start:]);time.sleep(2);trace=finish_trace(name,start)
        assert len(trace)==count,trace
        if action['type']=='stall':assert any(r['event']=='stalled' for r in trace)
        out[name]=latency
    return out

@case
def auth_retry_once():
    control([{'type':'http_fail','status':403},{'type':'success'}]);start=len(rows())
    result=run_case('fixture-auth-recover','edge:en-US-AriaNeural',P[3]);success(result)
    trace=finish_trace('fixture-auth-recover',start);assert sum(r['event']=='http_fail' for r in trace)==1
    control([{'type':'http_fail','status':403}]);start=len(rows())
    result=run_case('fixture-auth-fatal','edge:en-US-AriaNeural',P[3]);time.sleep(3)
    assert any(e['event']=='error' for e in result) and not any(e['event']=='done' for e in result),result
    trace=finish_trace('fixture-auth-fatal',start);fails=sum(r['event']=='http_fail' for r in trace);assert fails==2,fails
    return {'fatalAfterAttempts':fails}

@case
def replacement():
    control([{'type':'stall','seconds':60},{'type':'success'}]);start=len(rows())
    result=run_case('fixture-replacement','edge:en-US-AriaNeural','The old public utterance waits.',replacement='The replacement public utterance completes.',replacementVoice='edge:ru-RU-SvetlanaNeural',replaceMs=500)
    assert any(e['event']=='stop' and e['detail'].startswith('fixture-replacement') for e in result)
    assert sum(e['event']=='done' and e['detail'].startswith('replacement:') for e in result)==1
    assert not any(e['event'] in ('error','latePcm') for e in result)
    trace=finish_trace('fixture-replacement',start);assert sum(r['event']=='served_complete' for r in trace)==1
    return {'replacementCompleted':True}

print(json.dumps({k:v['pass'] for k,v in summary.items()},indent=2))
sys.exit(0 if all(v['pass'] for v in summary.values()) else 1)
