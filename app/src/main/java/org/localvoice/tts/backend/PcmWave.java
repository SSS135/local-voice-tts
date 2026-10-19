package org.localvoice.tts.backend;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Bounded reader for the pinned mono PCM16 24 kHz Pocket reference assets. */
final class PcmWave {
    static final class Clip {
        final float[] samples;
        final int sampleRate;
        Clip(float[] samples,int sampleRate){this.samples=samples;this.sampleRate=sampleRate;}
    }
    private PcmWave() {}
    static Clip read(File file,int maximumFrames)throws IOException {
        if(maximumFrames<=0||maximumFrames>24000*20||file.length()>2*1024*1024)
            throw new IOException("Invalid Pocket reference size");
        try(RandomAccessFile input=new RandomAccessFile(file,"r")) {
            if(!fourcc(input).equals("RIFF"))throw new IOException("Pocket reference is not RIFF");
            long limit=unsignedInt(input)+8;
            if(limit>input.length()||limit<12||!fourcc(input).equals("WAVE"))
                throw new IOException("Invalid Pocket reference header");
            boolean format=false;
            for(int chunks=0;chunks<128&&input.getFilePointer()+8<=limit;chunks++) {
                String kind=fourcc(input);long length=unsignedInt(input),next=input.getFilePointer()+length;
                if(next>limit)throw new IOException("Truncated Pocket reference");
                if(kind.equals("fmt ")) {
                    if(format||length<16||length>1024)throw new IOException("Invalid Pocket reference format");
                    int encoding=unsignedShort(input),channels=unsignedShort(input);
                    long rate=unsignedInt(input),bytesPerSecond=unsignedInt(input);
                    int alignment=unsignedShort(input),bits=unsignedShort(input);
                    if(encoding!=1||channels!=1||rate!=24000||bytesPerSecond!=48000||alignment!=2||bits!=16)
                        throw new IOException("Pocket reference must be mono PCM16 at 24 kHz");
                    format=true;
                } else if(kind.equals("data")) {
                    if(!format||length<=0||(length&1)!=0||length>maximumFrames*2L)
                        throw new IOException("Invalid Pocket reference PCM");
                    byte[] pcm=new byte[(int)length];input.readFully(pcm);
                    float[] samples=new float[pcm.length/2];
                    for(int i=0;i<samples.length;i++)
                        samples[i]=(short)((pcm[i*2]&255)|(pcm[i*2+1]<<8))/32768f;
                    return new Clip(samples,24000);
                }
                input.seek(next+(length&1));
            }
            throw new IOException("Pocket reference contains no supported PCM");
        }
    }
    private static String fourcc(RandomAccessFile input)throws IOException {
        byte[] text=new byte[4];input.readFully(text);return new String(text,StandardCharsets.US_ASCII);
    }
    private static int unsignedShort(RandomAccessFile input)throws IOException {
        int a=input.readUnsignedByte(),b=input.readUnsignedByte();return a|(b<<8);
    }
    private static long unsignedInt(RandomAccessFile input)throws IOException {
        return (long)unsignedShort(input)|((long)unsignedShort(input)<<16);
    }
}
