import subprocess, pathlib, json, time, xml.etree.ElementTree as ET, re, sys, shlex
ROOT=pathlib.Path(__file__).resolve().parents[2]
(ROOT/'evidence').mkdir(exist_ok=True)
import os
ADB=str(pathlib.Path(os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT',str(pathlib.Path.home()/'AppData/Local/Android/Sdk'))))/'platform-tools'/'adb.exe')
SERIAL=os.environ.get('LOCALVOICE_TEST_SERIAL','emulator-5582')

def adb(*args,binary=False):
    r=subprocess.run([ADB,'-s',SERIAL,*args],capture_output=True,check=True)
    return r.stdout if binary else r.stdout.decode('utf-8',errors='replace').strip()
def xml():
    adb('shell','rm','-f','/sdcard/window.xml')
    adb('shell','uiautomator','dump','/sdcard/window.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/window.xml'))
def snapshot(name):
    (ROOT/'evidence'/f'{name}.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
    tree=xml(); ET.ElementTree(tree).write(ROOT/'evidence'/f'{name}.xml',encoding='utf-8')
    return [(n.get('text'),n.get('bounds')) for n in tree.iter('node') if n.get('text')]
def click(text,exact=True,index=0):
    nodes=[n for n in xml().iter('node') if n.get('text','').casefold()==text.casefold() or (not exact and text.casefold() in n.get('text','').casefold())]
    n=nodes[index]; x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
def probe(name,voice=None,text=None,**extras):
    adb('shell','am','force-stop','org.localvoice.probe')
    args=['shell','am','start','-W','-n','org.localvoice.probe/.ProbeActivity','--es','name',name]
    if voice: args+=['--es','voice',voice]
    if text: args+=['--es','text',shlex.quote(text)]
    for key,value in extras.items(): args += ['--ez' if isinstance(value,bool) else '--ef' if isinstance(value,float) else '--ei' if isinstance(value,int) else '--es',key,shlex.quote(str(value).lower() if isinstance(value,bool) else str(value))]
    print(adb(*args))
def collect(name):
    for source,target in [('events.json',f'{name}-events.json'),(name+'.wav',name+'.wav')]:
        result=subprocess.run([ADB,'-s',SERIAL,'pull','/sdcard/Android/data/org.localvoice.probe/files/'+source,str(ROOT/'evidence'/target)],capture_output=True,text=True)
        print(result.stdout.strip(),result.stderr.strip())
    (ROOT/'evidence'/f'{name}-engine.log').write_text(adb('logcat','-d','-s','LocalVoice','LocalVoiceTiming','LVProbe','-v','threadtime'),encoding='utf-8')
def run_case(name,voice,text,timeout=120,**extras):
    adb('logcat','-c');probe(name,voice,text,**extras)
    deadline=time.time()+timeout
    while time.time()<deadline:
        try: events=json.loads(adb('shell','cat','/sdcard/Android/data/org.localvoice.probe/files/events.json'))
        except (ValueError,subprocess.CalledProcessError): time.sleep(1);continue
        expected='replacement' if 'replacement' in extras else name
        if any(e['event'] in ('done','error') and e['detail'].startswith(expected) for e in events):break
        if ('stopMs' in extras or extras.get('stopOnFirstPcm')) and any(e['event']=='stop' and e['detail'].startswith(name) for e in events):time.sleep(3);break
        time.sleep(1)
    else: raise RuntimeError('Timed out '+name)
    collect(name)
    if 'replacement' in extras:adb('pull','/sdcard/Android/data/org.localvoice.probe/files/replacement.wav',str(ROOT/'evidence'/f'{name}-replacement.wav'))
    events=json.loads((ROOT/'evidence'/f'{name}-events.json').read_text())
    print([(e['event'],e['elapsedMs'],e['detail']) for e in events if e['event'] not in ('voices',)])
    return events
if __name__=='__main__':
    if sys.argv[1]=='snapshot': print(snapshot(sys.argv[2]))
    elif sys.argv[1]=='click': click(sys.argv[2],index=int(sys.argv[3]) if len(sys.argv)>3 else 0)
    elif sys.argv[1]=='probe': probe(*sys.argv[2:])
    elif sys.argv[1]=='collect': collect(sys.argv[2])
