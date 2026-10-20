package org.localvoice.tts.backend;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Java port of the tested VN Reader / edge-tts 7.3.1 GEC v2. LGPLv3; see notices. */
final class EdgeGec {
    private static final int[] INITIAL = {0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19};
    private static final int[] STATE = {0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A, 0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2};
    private static final int[] SCHEDULE = {0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C, 0xD1DD9491, 0xC0DD25A0, 0xF9E70AB6, 0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33, 0xD258B060, 0x72766564};
    private static final int[] ROUND = {0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5, 0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174, 0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA, 0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967, 0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85, 0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070, 0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3, 0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2};
    private static final int[] COEFFICIENT = {0xE82E91B1, 0x44DE24C6, 0x3BEB65BC, 0xC89C5933, 0x8D1EC211, 0x314EF5D5, 0xE92491B1, 0xF0A5B9D5, 0x06064CF3, 0xDA79FA60, 0xBED20560, 0x89F9C9F8, 0x35FA38C6, 0xCB49E3C6};
    private static final int[] OFFSET = {0xB32700D0, 0x88DFF51D, 0x29514038, 0xB328CBDF, 0xD405B4D5, 0x26E55F3C, 0x607801C6, 0x82B2C1FB, 0xFA51A3DA, 0x207CC464, 0x3C3C74F3, 0xD2D14B61, 0xBB04A2C1, 0xC5626167};
    private static int sigma0(int x) { return Integer.rotateRight(x, 7) ^ Integer.rotateRight(x, 18) ^ (x >>> 3); }
    private static int sigma1(int x) { return Integer.rotateRight(x, 17) ^ Integer.rotateRight(x, 19) ^ (x >>> 10); }
    private static void expand(int[] w, int start) {
        for (int i = start; i < 64; i++) w[i] = sigma1(w[i - 2]) + w[i - 7] + sigma0(w[i - 15]) + w[i - 16];
    }
    private static int[] rounds(int[] state, int[] w, int start) {
        int a=state[0], b=state[1], c=state[2], d=state[3], e=state[4], f=state[5], g=state[6], h=state[7];
        for (int i = start; i < 64; i++) {
            int k = start == 14 && i == 14 ? 0xC9DC06A7 : ROUND[i];
            int t1 = h + (Integer.rotateRight(e,6) ^ Integer.rotateRight(e,11) ^ Integer.rotateRight(e,25))
                    + (g ^ (e & (f ^ g))) + k + w[i];
            int t2 = (Integer.rotateRight(a,2) ^ Integer.rotateRight(a,13) ^ Integer.rotateRight(a,22))
                    + ((a & b) | (c & (a | b)));
            h=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2;
        }
        return new int[]{a,b,c,d,e,f,g,h};
    }
    private static byte[] digest(byte[] data) {
        int[] w = new int[64];
        w[14] = ((data[0] & 255) << 16) | ((data[1] & 255) << 8) | (data[2] & 255);
        w[15] = ByteBuffer.wrap(data,3,4).getInt();
        for (int i=16; i<=29; i++) {
            int v=SCHEDULE[i-16];
            if (i-2>=14) v += sigma1(w[i-2]) ^ (i==16 ? 0x000B92C0 : 0);
            if (i-7>=14) v += w[i-7];
            if (i-15>=14) v += sigma0(w[i-15]) ^ (i==29 ? 0x059C0B80 : 0);
            w[i]=v;
        }
        w[30]=sigma1(w[28])+w[23]+sigma0(w[15])+w[14]+0xC9DC06A7-ROUND[14];
        expand(w,31);
        int[] state=rounds(STATE,w,14);
        for (int i=0;i<8;i++) state[i]+=INITIAL[i];
        int length=57+data.length, padding=((55-length)%64+64)%64;
        ByteBuffer tail=ByteBuffer.allocate(data.length-7+1+padding+8);
        tail.put(data,7,data.length-7).put((byte)0x80).put(new byte[padding]).putLong((long)length*8);
        byte[] bytes=tail.array();
        for (int offset=0;offset<bytes.length;offset+=64) {
            int[] block=new int[64]; ByteBuffer buffer=ByteBuffer.wrap(bytes,offset,64);
            for(int i=0;i<16;i++) block[i]=buffer.getInt();
            expand(block,16);
            int[] result=rounds(state,block,0);
            for(int i=0;i<8;i++) state[i]+=result[i];
        }
        ByteBuffer result=ByteBuffer.allocate(32);
        for(int value:state) result.putInt(value);
        return result.array();
    }
    private static byte[] cs64(byte[] data, byte[] digest) {
        ByteBuffer buffer=ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN);
        int ka=buffer.getInt()|1, kb=buffer.getInt()|1;
        int[] factors={ka*0x82893933,kb*0x82893933};
        int count=((data.length+1+3)/4+1)/2*2;
        int[] terms=new int[COEFFICIENT.length+count], offsets=new int[OFFSET.length+count];
        for(int i=0;i<COEFFICIENT.length;i++) terms[i]=COEFFICIENT[i]*factors[i%2];
        System.arraycopy(OFFSET,0,offsets,0,OFFSET.length);
        byte[] raw=new byte[count*4]; System.arraycopy(data,0,raw,1,data.length);
        ByteBuffer input=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<count;i++) {
            int word=input.getInt(), term=word*(i%2==0?ka:kb);
            int off=Integer.rotateLeft(word,16)*(i%2==0?0xEF0569FB:0xC31713DB);
            if(i==0) { term+=0xF2F90B1A*factors[0]; off+=0x0B1A0000; }
            terms[COEFFICIENT.length+i]=term; offsets[OFFSET.length+i]=off;
        }
        int swapAcc=0,swapLast=0,state=0,acc=0;
        for(int i=0;i<terms.length;i+=2) {
            int x=terms[i],y=terms[i+1];
            int q=Integer.rotateLeft(offsets[i]+x,16)*0x76F50F34;
            swapAcc+=Integer.rotateLeft(q,16)*0xC3EFEA97+q;
            q=Integer.rotateLeft(offsets[i+1]+y,16)*0x1881CE3C;
            swapLast=Integer.rotateLeft(q,16)*0x35BD1EC9+q; swapAcc+=swapLast;
            state=state*ka+x;
            state=Integer.rotateLeft(state,16)*0xCF98B111;
            state=Integer.rotateLeft(state,16)*0x87085B9F;
            state=Integer.rotateLeft(state,16)*0x12CEB96D;
            state=Integer.rotateLeft(state,16)*0x257E1D83; acc+=state;
            int t=state*kb+y;
            t=Integer.rotateLeft(t,16)*0xA27416F5;
            t=Integer.rotateLeft(t,16)*0xD38396FF;
            state=Integer.rotateLeft(Integer.rotateLeft(t,16)*0x7C932B89,16)*0xBFA49F69; acc+=state;
        }
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(swapLast^state).putInt(swapAcc^acc).array();
    }
    private static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder();
        for(byte b:bytes) out.append(String.format(Locale.ROOT,"%02X",b&255));
        return out.toString();
    }
    static String value(long unixSeconds) {
        long seconds=unixSeconds+11644473600L, ticks=(seconds-seconds%300)*10000000L;
        byte[] input=(Long.toString(ticks)+EdgeProtocol.CLIENT_TOKEN).getBytes(StandardCharsets.US_ASCII);
        byte[] digest=digest(input);
        return hex(cs64(input,digest))+"-"+hex(digest);
    }
    /** Server clock minus device clock in ms; the token is time-bucketed, so a skewed device clock gets 403. */
    static volatile long skewMillis;
    /** Port of edge-tts DRM.handle_client_response_error: learn the skew from a rejected handshake's Date header. */
    static void syncClock(String serverDate, long nowMillis) {
        if (serverDate == null) return;
        try {
            skewMillis = java.time.ZonedDateTime.parse(serverDate.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - nowMillis;
        } catch (RuntimeException invalidDate) { }
    }
    static String value() { return value((System.currentTimeMillis()+skewMillis)/1000); }
}
