package org.localvoice.tts;

/** Preview identity prevents old Android TTS callbacks from changing a newer row. */
final class PreviewSession {
    private long sequence;
    private String id="",voice="";
    private boolean closed;
    String begin(String voice){if(closed)return "";this.voice=voice;id="preview-"+(++sequence);return id;}
    String voice(){return voice;}
    boolean accepts(String callbackId){return !closed&&!id.isEmpty()&&id.equals(callbackId);}
    void clear(){id="";voice="";}
    void close(){closed=true;clear();}
}
