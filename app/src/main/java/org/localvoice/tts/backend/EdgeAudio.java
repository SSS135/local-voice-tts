package org.localvoice.tts.backend;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Per-turn validation/staging bounds, independent of Android and the network. */
public final class EdgeAudio {
    public static final int MAX_MP3_BYTES = 2 * 1024 * 1024;
    public static final int MAX_PCM_SAMPLES = 24000 * 180;
    private static final int[] V1_BITRATE = {0,32,40,48,56,64,80,96,112,128,160,192,224,256,320};
    private static final int[] V2_BITRATE = {0,8,16,24,32,40,48,56,64,80,96,112,128,144,160};
    private static final int[] SAMPLE_RATES = {44100,48000,32000};

    public static void validateMp3(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_MP3_BYTES) throw new IOException("Invalid Edge MP3 size");
        int offset=0, frames=0;
        if (bytes.length >= 10 && bytes[0]=='I' && bytes[1]=='D' && bytes[2]=='3') {
            int length=0;
            for(int i=6;i<10;i++) {
                if((bytes[i]&128)!=0) throw new IOException("Invalid Edge MP3 tag");
                length=(length<<7)|(bytes[i]&127);
            }
            offset=10+length+((bytes[5]&16)!=0?10:0);
        }
        while(offset<bytes.length) {
            if(bytes.length-offset<4) throw new IOException("Truncated Edge MP3 frame");
            int h=((bytes[offset]&255)<<24)|((bytes[offset+1]&255)<<16)|((bytes[offset+2]&255)<<8)|(bytes[offset+3]&255);
            int version=(h>>>19)&3, layer=(h>>>17)&3, rateIndex=(h>>>10)&3, bitIndex=(h>>>12)&15;
            if((h&0xFFE00000)!=0xFFE00000 || version==1 || layer!=1 || rateIndex==3 || bitIndex==0 || bitIndex==15)
                throw new IOException("Invalid Edge MP3 frame");
            int rate=SAMPLE_RATES[rateIndex]/(version==3?1:version==2?2:4);
            if(rate!=24000 || ((h>>>6)&3)!=3) throw new EdgeRetry.FatalException("Unsupported Edge MP3 format");
            int bitrate=(version==3?V1_BITRATE:V2_BITRATE)[bitIndex]*1000;
            int size=(version==3?144:72)*bitrate/rate+((h>>>9)&1);
            if(size<4 || size>bytes.length-offset) throw new IOException("Truncated Edge MP3 frame");
            offset+=size; frames++;
        }
        if(frames<2) throw new IOException("Edge MP3 contains insufficient complete audio");
    }

    public static final class Pcm {
        private final List<float[]> blocks = new ArrayList<>();
        private int samples;
        private boolean complete, committed;
        public void add(float[] block) throws IOException {
            if(complete) throw new IllegalStateException("Edge PCM is already validated");
            if(block.length>MAX_PCM_SAMPLES-samples) throw new IOException("Edge decoded audio exceeds limit");
            for(float value:block) if(!Float.isFinite(value)) throw new IOException("Invalid Edge decoded PCM");
            if(block.length>0) { blocks.add(block); samples+=block.length; }
        }
        public Pcm complete() throws IOException {
            if(samples==0) throw new IOException("Edge decoded no audio");
            complete=true;
            return this;
        }
        public boolean commit(SpeechBackend.Cancellation cancellation, SpeechBackend.PcmSink sink) {
            if(!complete || committed) throw new IllegalStateException("Edge PCM must be validated and committed once");
            committed=true;
            for(float[] block:blocks) {
                if(cancellation.isCancelled() || !sink.write(block)) return false;
            }
            return !cancellation.isCancelled();
        }
        public int samples() { return samples; }
    }
    private EdgeAudio() {}
}
