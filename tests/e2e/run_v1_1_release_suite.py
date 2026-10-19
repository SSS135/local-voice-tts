"""Real public Android TTS client checks. Install all four local packs and opt in to Edge first."""
from android_test import *
import hashlib, wave, array, math

def completed(events):
    assert any(e['event']=='done' for e in events), events
    assert not any(e['event'] in ('error','exception','latePcm') for e in events), events
    voices=json.loads(next(e['detail'] for e in events if e['event']=='voices'))
    assert len(voices)==372 and sum(v['network'] for v in voices)==322, len(voices)

def audio(name):
    with wave.open(str(ROOT/'evidence'/f'{name}.wav'),'rb') as stream:
        assert stream.getnchannels()==1 and stream.getsampwidth()==2
        samples=array.array('h',stream.readframes(stream.getnframes()))
        row={'frames':len(samples),'sampleRate':stream.getframerate(),'seconds':len(samples)/stream.getframerate(),
             'rms':math.sqrt(sum(float(v)*v for v in samples)/len(samples))/32768}
        assert row['rms']>0.001
        return row

settings={key:adb('shell','settings','get','global',key) for key in ('airplane_mode_on','wifi_on','mobile_data')}
(ROOT/'evidence/1.1-network-original.json').write_text(json.dumps(settings,indent=2))
summary={'apkSha256':hashlib.sha256((ROOT/'app/build/outputs/apk/release/app-release.apk').read_bytes()).hexdigest(),'audio':{}}
try:
    adb('shell','cmd','connectivity','airplane-mode','enable')
    adb('shell','svc','wifi','disable');adb('shell','svc','data','disable')
    for _ in range(45):
        state=adb('shell','dumpsys','connectivity')
        if 'Active default network: none' in state:break
        time.sleep(1)
    else:raise AssertionError('Network did not become unavailable')
    (ROOT/'evidence/1.1-offline-connectivity.txt').write_text(state)
    adb('shell','am','force-stop','org.localvoice.tts')
    for name,voice,text in [
        ('1.1-final-kokoro-en','kokoro-en:af_heart','A quiet evening falls beside the river.'),
        ('1.1-final-super-en','supertonic-3:en-US-0','A quiet evening falls beside the river.'),
        ('1.1-final-super-ru','supertonic-3:ru-RU-0','Тихий вечер наступает у реки.'),
        ('1.1-final-pocket-en','pocket-en-v2:alba','A quiet morning begins beside the river.'),
        ('1.1-final-shtorm-ru','shtorm-pocket-ru-fast:vctk-p228','Приве́т! Число 21. Всё хорошо.'),
    ]:
        events=run_case(name,voice,text,timeout=180);completed(events);summary['audio'][name]=audio(name)
        (ROOT/'evidence'/f'{name}-memory.txt').write_text(adb('shell','dumpsys','meminfo','org.localvoice.tts'))
        print('PASS offline',name,summary['audio'][name],flush=True)
    for suffix,rate in (('slow',.75),('fast',1.25)):
        name='1.1-final-pocket-rate-'+suffix
        events=run_case(name,'pocket-en-v2:alba','A quiet morning begins beside the river.',timeout=180,rate=rate)
        completed(events);summary['audio'][name]=audio(name)
    slow=summary['audio']['1.1-final-pocket-rate-slow']['seconds'];fast=summary['audio']['1.1-final-pocket-rate-fast']['seconds']
    assert slow>fast*1.2,(slow,fast) # Stochastic native generation; exact Sonic scaling is covered by signal tests.
    for suffix,voice,text in (
        ('pocket','pocket-en-v2:alba','The lantern shines beside the quiet river. '*24),
        ('shtorm','shtorm-pocket-ru-fast:vctk-p228','Тихий вечер наступает у реки. '*24)):
        events=run_case('1.1-final-stop-'+suffix,voice,text,timeout=180,speak=True,stopOnFirstPcm=True)
        assert any(e['event']=='firstPcm' for e in events)
        stop=next(e for e in events if e['event']=='stop')
        assert int(stop['detail'].split('bytes=')[1])>0
        assert not any(e['event'] in ('done','error','latePcm') for e in events),events
        print('PASS active PCM stop',suffix,stop,flush=True)
    events=run_case('1.1-final-switch','shtorm-pocket-ru-fast:vctk-p228','Тихий вечер наступает у реки. '*18,timeout=180,
                    replacement='A new English voice continues after cancellation.',replacementVoice='pocket-en-v2:alba',replaceMs=250)
    assert any(e['event']=='stop' for e in events)
    assert any(e['event']=='done' and e['detail'].startswith('replacement') for e in events)
    assert not any(e['event'] in ('error','exception','latePcm') for e in events),events
    adb('shell','am','force-stop','org.localvoice.tts')
    events=run_case('1.1-final-restart','shtorm-pocket-ru-fast:vctk-p228','Русский голос работает после перезапуска.',timeout=180)
    completed(events);summary['audio']['1.1-final-restart']=audio('1.1-final-restart')
    events=run_case('1.1-final-shtorm-unsupported','shtorm-pocket-ru-fast:vctk-p228','Hello world.',timeout=60)
    assert any(e['event']=='error' for e in events) and not any(e['event'] in ('done','firstPcm') for e in events),events
    summary['offlineNetworkNone']=True;summary['activePcmStopBothPocketBackends']=True
    summary['switchCancellation']=True;summary['processRestart']=True;summary['unsupportedTextError']=True
finally:
    adb('shell','cmd','connectivity','airplane-mode','enable' if settings['airplane_mode_on']=='1' else 'disable')
    adb('shell','svc','wifi','enable' if settings['wifi_on']=='1' else 'disable')
    adb('shell','svc','data','enable' if settings['mobile_data']=='1' else 'disable')
(ROOT/'evidence/1.1-offline-suite-summary.json').write_text(json.dumps(summary,indent=2))
print('PASS v1.1 public-client offline suite',summary,flush=True)
