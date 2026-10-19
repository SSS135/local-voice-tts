package org.localvoice.tts.backend;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/** Authentic Russian Shtorm BPE IDs are carried through the proven native PUA bridge. */
public final class ShtormBackend extends PocketBackend {
    private ShtormBpeFrontend frontend;
    @Override protected void loadFrontend(File directory,Map<String,String> config)throws IOException {
        frontend=new ShtormBpeFrontend(
            new File(path(directory,config,"bpeVocab")),
            new File(path(directory,config,"bpeScores")),
            new File(path(directory,config,"bpeNormalizer")));
    }
    @Override protected java.util.List<Prepared> prepareChunks(String text,Cancellation cancellation) {
        // Exact pinned SentencePiece normalization precedes app-owned bounded cardinal expansion.
        String normalized=frontend.normalize(text);
        if(normalized.startsWith("\u2581"))normalized=normalized.substring(1);
        normalized=normalized.replace('\u2581',' ');
        String expanded=RussianCardinals.expand(normalized,RussianCardinals::spellout);
        java.util.List<Prepared> chunks=new java.util.ArrayList<>();
        for(String chunk:PocketPrompt.boundedChunks(expanded)){
        if(cancellation.isCancelled())return java.util.Collections.emptyList();
        String spoken=PocketPrompt.prepare(chunk);
        int[] ids=frontend.encode(spoken);
        // 127 voice/BOS frames + 256 text tokens + 500 generated frames fit the pinned 1000-slot KV cache.
        if(ids.length>256)throw new IllegalArgumentException("Shtorm sentence exceeds its supported token budget");
        StringBuilder bridge=new StringBuilder(ids.length);
        for(int id:ids)bridge.append((char)(0xe000+id));
        chunks.add(new Prepared(bridge.toString(),PocketPrompt.words(spoken)<=4?3:1));
        }
        return chunks;
    }
    @Override public void close(){super.close();frontend=null;}
}
