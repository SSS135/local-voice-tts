package org.localvoice.tts.backend;

import java.util.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.*;

/** Actual tone/speed/pitch/chunk/lifecycle and memory checks, with no Android runtime. */
public final class PcmTimeStretchCheck {
    interface Checked { void run() throws Exception; }
    static int passed;
    static void check(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    static void test(String label,Checked action)throws Exception {action.run();passed++;System.out.println("PASS "+label);}
    static final class Output implements SpeechBackend.PcmSink {
        final List<float[]> blocks=new ArrayList<>();int samples;
        public boolean write(float[] pcm){check(pcm.length>0 && pcm.length<=2048,"unbounded/empty output block");for(float value:pcm)check(Float.isFinite(value)&&Math.abs(value)<=1.01f,"invalid output");blocks.add(pcm);samples+=pcm.length;return true;}
        float[] array(){float[] result=new float[samples];int n=0;for(float[] block:blocks){System.arraycopy(block,0,result,n,block.length);n+=block.length;}return result;}
    }
    static float[] tone(int rate,double seconds,double frequency){float[] pcm=new float[(int)(rate*seconds)];for(int i=0;i<pcm.length;i++)pcm[i]=(float)(.6*Math.sin(2*Math.PI*frequency*i/rate));return pcm;}
    static float[] stretch(float[] input,int rate,float speed,int chunk)throws Exception{
        PcmTimeStretch processor=new PcmTimeStretch(rate,speed);Output output=new Output();
        for(int offset=0;offset<input.length;offset+=chunk)check(processor.write(Arrays.copyOfRange(input,offset,Math.min(input.length,offset+chunk)),()->false,output),"write stopped");
        check(processor.finish(()->false,output),"finish stopped");return output.array();
    }
    static double frequency(float[] pcm,int rate){int margin=Math.min(rate/5,pcm.length/8),crossings=0;for(int i=margin+1;i<pcm.length-margin;i++)if(pcm[i-1]<=0 && pcm[i]>0)crossings++;return (double)crossings*rate/(pcm.length-2*margin);}
    static int retainedBytes(PcmTimeStretch processor)throws Exception{
        int bytes=0;for(Field field:PcmTimeStretch.class.getDeclaredFields()){field.setAccessible(true);Object value=field.get(processor);if(value instanceof short[])bytes+=((short[])value).length*2;if(value instanceof Sonic)for(Field core:Sonic.class.getDeclaredFields()){core.setAccessible(true);Object buffer=core.get(value);if(buffer instanceof short[])bytes+=((short[])buffer).length*2;}}return bytes;
    }
    public static void main(String[] args)throws Exception{
        test("speed1 bypass preserves exact finite samples",()->{
            Random random=new Random(1);float[] input=new float[10003];for(int i=0;i<input.length;i++)input[i]=random.nextFloat()*2-1;input[0]=-0f;
            check(Arrays.equals(input,stretch(input,24000,1,10003)),"bypass changed samples");
        });
        test("speed changes duration while 220Hz pitch remains fixed",()->{
            float[] input=tone(24000,4,220);
            for(float speed:new float[]{.5f,.75f,1,1.25f,1.5f,2}){
                float[] output=stretch(input,24000,speed,1920);double hz=frequency(output,24000),expected=input.length/speed;
                check(Math.abs(output.length-expected)<=24000*.025+expected*.01,"duration mismatch speed="+speed+" samples="+output.length);
                check(Math.abs(hz-220)<3,"pitch shifted speed="+speed+" hz="+hz);
                System.out.printf(java.util.Locale.ROOT,"speed=%.2f samples=%d pitchHz=%.2f%n",speed,output.length,hz);
            }
        });
        test("sample rates8k through96k retain pitch and expected duration",()->{
            for(int rate:new int[]{8000,16000,24000,44100,48000,96000})for(float speed:new float[]{.5f,1.5f,2}){
                float[] input=tone(rate,1.5,220),output=stretch(input,rate,speed,511);
                check(Math.abs(output.length-input.length/speed)<rate*.04,"rate duration mismatch");check(Math.abs(frequency(output,rate)-220)<4,"rate pitch mismatch rate="+rate+" speed="+speed+" hz="+frequency(output,rate));
            }
        });
        test("arbitrary input chunk boundaries preserve Sonic output",()->{
            float[] input=tone(24000,2,220),reference=stretch(input,24000,1.5f,input.length);
            for(int size:new int[]{1,7,63,511,1920,2048})check(Arrays.equals(reference,stretch(input,24000,1.5f,size)),"output changed at chunk="+size);
        });
        test("empty and short streams finish without invalid output",()->{
            for(int size:new int[]{0,1,10,64,511})for(float speed:new float[]{.5f,.75f,1,1.5f,2})stretch(new float[size],24000,speed,17);
        });
        test("cancel before write or finish emits no audio",()->{
            PcmTimeStretch processor=new PcmTimeStretch(24000,.5f);AtomicInteger calls=new AtomicInteger();SpeechBackend.PcmSink sink=pcm->{calls.incrementAndGet();return true;};
            check(!processor.write(tone(24000,1,220),()->true,sink) && !processor.finish(()->false,sink) && calls.get()==0,"cancel emitted pending audio");
        });
        test("cancel during output discards all remaining audio",()->{
            PcmTimeStretch processor=new PcmTimeStretch(24000,.5f);AtomicBoolean stop=new AtomicBoolean();AtomicInteger calls=new AtomicInteger();SpeechBackend.PcmSink sink=pcm->{calls.incrementAndGet();stop.set(true);return true;};
            check(!processor.write(tone(24000,3,220),stop::get,sink),"cancelled write succeeded");check(!processor.finish(()->false,sink)&&calls.get()==1,"late output after cancellation");
        });
        test("sink rejection poisons processor and prevents late finish audio",()->{
            PcmTimeStretch processor=new PcmTimeStretch(24000,1.5f);AtomicInteger calls=new AtomicInteger();SpeechBackend.PcmSink sink=pcm->{calls.incrementAndGet();return false;};
            check(!processor.write(tone(24000,2,220),()->false,sink)&&!processor.finish(()->false,sink)&&calls.get()==1,"rejected output repeated");
        });
        test("sink exception prevents later output",()->{
            PcmTimeStretch processor=new PcmTimeStretch(24000,.75f);AtomicInteger calls=new AtomicInteger();SpeechBackend.PcmSink sink=pcm->{calls.incrementAndGet();throw new IllegalStateException("test sink");};
            try{processor.write(tone(24000,1,220),()->false,sink);throw new AssertionError("sink exception swallowed");}catch(IllegalStateException expected){}
            check(!processor.finish(()->false,sink)&&calls.get()==1,"late audio after sink error");
        });
        test("finish is idempotent and write after finish is rejected",()->{
            PcmTimeStretch processor=new PcmTimeStretch(24000,1.5f);Output output=new Output();processor.write(tone(24000,1,220),()->false,output);processor.finish(()->false,output);int count=output.samples;
            check(processor.finish(()->false,output)&&output.samples==count,"finish repeated audio");try{processor.write(new float[1],()->false,output);throw new AssertionError("write after finish accepted");}catch(IllegalStateException expected){}
        });
        test("invalid configuration and PCM fail instead of emitting NaNs",()->{
            for(float speed:new float[]{Float.NaN,0,.4f,2.1f})try{new PcmTimeStretch(24000,speed);throw new AssertionError("invalid speed accepted");}catch(IllegalArgumentException expected){}
            PcmTimeStretch processor=new PcmTimeStretch(24000,1);Output output=new Output();try{processor.write(new float[]{Float.NaN},()->false,output);throw new AssertionError("NaN accepted");}catch(IllegalArgumentException expected){}check(!processor.finish(()->false,output)&&output.samples==0,"invalid PCM emitted");
        });
        test("retained buffers stay bounded across two minutes of audio",()->{
            for(int rate:new int[]{24000,96000})for(float speed:new float[]{.5f,2}){
                PcmTimeStretch processor=new PcmTimeStretch(rate,speed);float[] block=tone(rate,.08,220);int peak=0;
                SpeechBackend.PcmSink discard=pcm->{check(pcm.length<=2048,"large drain");return true;};
                for(int i=0;i<1500;i++){check(processor.write(block,()->false,discard),"long stream stopped");peak=Math.max(peak,retainedBytes(processor));}
                processor.finish(()->false,discard);peak=Math.max(peak,retainedBytes(processor));check(peak<=256*1024,"retained buffers grew with duration: "+peak);
                System.out.println("sampleRate="+rate+" speed="+speed+" retainedBytesPeak="+peak);
            }
        });
        System.out.println("PASS: "+passed+" PCM time stretch checks");
    }
}
