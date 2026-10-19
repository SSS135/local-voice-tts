package org.localvoice.tts.backend;

import java.nio.ShortBuffer;
import java.util.Arrays;

/** Bounded mono PCM speed and pitch adjustment with unchanged sample rate; pitch keeps duration. One instance per stream. */
public final class PcmTimeStretch {
    static final int CHUNK_SAMPLES=2048;
    private final Sonic sonic;
    private final short[] input=new short[CHUNK_SAMPLES],output=new short[CHUNK_SAMPLES];
    private boolean finished,stopped;

    public PcmTimeStretch(int sampleRate,float speed) { this(sampleRate,speed,1f); }
    public PcmTimeStretch(int sampleRate,float speed,float pitch) {
        if(sampleRate<8000 || sampleRate>96000 || !Float.isFinite(speed) || speed<0.5f || speed>2f || !Float.isFinite(pitch) || pitch<0.5f || pitch>2f)
            throw new IllegalArgumentException("Unsupported PCM sample rate, speed or pitch");
        sonic=speed==1f&&pitch==1f?null:new Sonic(sampleRate,1,speed,pitch,sampleRate);
    }
    public boolean write(float[] pcm,SpeechBackend.Cancellation cancellation,SpeechBackend.PcmSink sink) {
        if(stopped || cancellation.isCancelled()) return stop();
        if(finished) throw new IllegalStateException("PCM stream is finished");
        for(int offset=0;offset<pcm.length;offset+=CHUNK_SAMPLES) {
            if(cancellation.isCancelled()) return stop();
            int n=Math.min(CHUNK_SAMPLES,pcm.length-offset);
            for(int i=0;i<n;i++) if(!Float.isFinite(pcm[offset+i])) {stop();throw new IllegalArgumentException("Non-finite PCM sample");}
            if(sonic==null) {
                if(!emit(Arrays.copyOfRange(pcm,offset,offset+n),cancellation,sink)) return false;
            } else {
                for(int i=0;i<n;i++) input[i]=(short)Math.round(Math.max(-1f,Math.min(1f,pcm[offset+i]))*32767f);
                sonic.queueInput(ShortBuffer.wrap(input,0,n));
                if(!drain(cancellation,sink)) return false;
            }
        }
        return cancellation.isCancelled()?stop():true;
    }
    public boolean finish(SpeechBackend.Cancellation cancellation,SpeechBackend.PcmSink sink) {
        if(stopped || cancellation.isCancelled()) return stop();
        if(finished) return true;
        finished=true;
        if(sonic!=null) {sonic.queueEndOfStream();return drain(cancellation,sink);}
        return cancellation.isCancelled()?stop():true;
    }
    private boolean drain(SpeechBackend.Cancellation cancellation,SpeechBackend.PcmSink sink) {
        while(sonic.getOutputSize()>0) {
            if(cancellation.isCancelled()) return stop();
            int n=Math.min(CHUNK_SAMPLES,sonic.getOutputSize()/2);
            sonic.getOutput(ShortBuffer.wrap(output,0,n));
            float[] pcm=new float[n];for(int i=0;i<n;i++) pcm[i]=output[i]/32768f;
            if(!emit(pcm,cancellation,sink)) return false;
        }
        return cancellation.isCancelled()?stop():true;
    }
    private boolean emit(float[] pcm,SpeechBackend.Cancellation cancellation,SpeechBackend.PcmSink sink) {
        if(cancellation.isCancelled()) return stop();
        boolean accepted;
        try {accepted=sink.write(pcm);}catch(RuntimeException|Error failure){stop();throw failure;}
        return !accepted || cancellation.isCancelled()?stop():true;
    }
    private boolean stop() {stopped=true;if(sonic!=null)sonic.flush();return false;}
}
