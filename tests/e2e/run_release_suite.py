from android_test import *
import wave,hashlib

def good(e):
 assert any(x['event']=='done' for x in e),e
 assert not any(x['event'] in ('error','exception','latePcm') for x in e),e
 voices=json.loads(next(x['detail'] for x in e if x['event']=='voices'))
 assert len(voices)==52 and sum(v['network'] for v in voices)==4

for name,voice,text in [
 ('release-edge-en','edge:en-US-AriaNeural','The morning sunlight shines over a quiet river.'),
 ('release-edge-ru','edge:ru-RU-SvetlanaNeural','Доброе утро. Сегодня мы проверяем русский голос и восстановление связи.')]:
 e=run_case(name,voice,text,timeout=240);good(e)
print('PASS signed release actual Microsoft English and Russian PCM')
# Disable networking only inside our clone; wait for actual connectivity state to settle.
adb('shell','cmd','connectivity','airplane-mode','enable');adb('shell','svc','wifi','disable');adb('shell','svc','data','disable')
for i in range(45):
 state=adb('shell','dumpsys','connectivity')
 if 'Active default network: none' in state:break
 time.sleep(1)
else:raise AssertionError('Network remained active')
(ROOT/'evidence/release-offline-connectivity.txt').write_text(state)
for name,voice,text,extras in [
 ('release-super-en-cold','supertonic-3:en-US-0','A quiet evening falls beside the river.',{}),
 ('release-super-ru','supertonic-3:ru-RU-0','Тихий вечер наступает у реки. Сегодня мы проверяем русский голос.',{}),
 ('release-kokoro-en','kokoro-en:af_heart','The lantern glows beside the quiet river.',{}),
 ('release-kokoro-repeat-cold','kokoro-en:af_heart','The lantern glows beside the quiet river.',{}),
 ('release-rate-slow','supertonic-3:ru-RU-0','Сегодня хороший день для прогулки.',{'rate':.75}),
 ('release-rate-fast','supertonic-3:ru-RU-0','Сегодня хороший день для прогулки.',{'rate':1.25})]:
 e=run_case(name,voice,text,timeout=180,**extras);good(e)
 (ROOT/'evidence'/f'{name}-memory.txt').write_text(adb('shell','dumpsys','meminfo','org.localvoice.tts'))
longtext=('A lantern shines beside the river while travelers walk beneath the stars. '*24)
e=run_case('release-stop-active-pcm','supertonic-3:en-US-0',longtext,timeout=180,speak=True,stopOnFirstPcm=True)
assert any(x['event']=='firstPcm' for x in e) and any(x['event']=='stop' for x in e),e
assert not any(x['event'] in ('done','error','latePcm') for x in e),e
first=next(x for x in e if x['event']=='firstPcm');stop=next(x for x in e if x['event']=='stop');assert int(stop['detail'].split('bytes=')[1])>0
print('PASS stop during actual PCM playback',first,stop)
# A fresh process must rediscover installed voices and synthesize without a partial cache.
adb('shell','am','force-stop','org.localvoice.tts')
e=run_case('release-process-restart','kokoro-en:af_heart','A new process reads the installed local voice.',timeout=180);good(e)
with wave.open(str(ROOT/'evidence/release-rate-slow.wav'),'rb') as a: slow=a.getnframes()/a.getframerate()
with wave.open(str(ROOT/'evidence/release-rate-fast.wav'),'rb') as a: fast=a.getnframes()/a.getframerate()
assert slow>fast*1.3,(slow,fast)
summary={'apkSha256':hashlib.sha256((ROOT/'app/build/outputs/apk/release/app-release.apk').read_bytes()).hexdigest(),'offlineNetworkNone':True,'slowSeconds':slow,'fastSeconds':fast,'activePcmStop':True,'processRestart':True}
(ROOT/'evidence/release-suite-summary.json').write_text(json.dumps(summary,indent=2))
print('PASS final release suite',summary)
