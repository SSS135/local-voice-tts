/*
 * SentencePiece BPE/normalizer semantics adapted from Google SentencePiece 0.2.2,
 * Copyright 2016 Google Inc., Apache License 2.0.
 * https://github.com/google/sentencepiece/tree/e0cce7d37b065b5140349dbe12c6bcf6192fdd78
 */
package org.localvoice.tts.backend;

import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Exact pinned Shtorm BPE. Numeric expansion belongs to the Android adapter. */
public final class ShtormBpeFrontend {
    private static final int VOCAB_SIZE=4000, MAX_FILE=2*1024*1024, MAX_INPUT=32768, MAX_NORMALIZED=262144;
    private final Map<String,Integer> vocabulary=new HashMap<>();
    private final Map<String,Float> scores=new HashMap<>();
    /** Code points that are pieces on their own: BPE output over these alone never hits <unk>. */
    private final Set<Integer> singles=new HashSet<>();
    private final int[] trie;
    private final byte[] replacements;

    public ShtormBpeFrontend(File vocab,File tokenScores) throws IOException {
        this(vocab,tokenScores,new File(vocab.getParentFile(),"normalizer.bin"));
    }
    public ShtormBpeFrontend(File vocab,File tokenScores,File normalizer) throws IOException {
        try {
            JSONObject pieces=object(vocab), ranks=object(tokenScores);
            if(pieces.length()!=VOCAB_SIZE || ranks.length()!=VOCAB_SIZE)throw new IOException("Shtorm vocabulary must contain 4000 pieces");
            boolean[] ids=new boolean[VOCAB_SIZE];
            for(Iterator<?> keys=pieces.keys();keys.hasNext();) {
                String piece=(String)keys.next();Object rawId=pieces.get(piece),rawScore=ranks.get(piece);
                if(piece.isEmpty() || piece.length()>1024 || !(rawId instanceof Number) || !(rawScore instanceof Number))throw new IOException("Invalid Shtorm vocabulary entry");
                int id=((Number)rawId).intValue();float score=((Number)rawScore).floatValue();
                if(id<0 || id>=VOCAB_SIZE || ((Number)rawId).doubleValue()!=id || ids[id] || !Float.isFinite(score))throw new IOException("Invalid Shtorm piece ID or score");
                ids[id]=true;vocabulary.put(piece,id);scores.put(piece,score);
                if(id>=3 && piece.codePointCount(0,piece.length())==1)singles.add(piece.codePointAt(0));
            }
            if(!Integer.valueOf(0).equals(vocabulary.get("<unk>")) || !Integer.valueOf(1).equals(vocabulary.get("<s>")) || !Integer.valueOf(2).equals(vocabulary.get("</s>")))throw new IOException("Unexpected Shtorm reserved pieces");
        } catch(JSONException error){throw new IOException("Invalid Shtorm vocabulary JSON",error);}
        byte[] data=read(normalizer);
        if(data.length<1029)throw new IOException("Shtorm normalizer is truncated");
        ByteBuffer buffer=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);int size=buffer.getInt();
        if(size<1024 || (size&1023)!=0 || size>=data.length-4 || data[data.length-1]!=0)throw new IOException("Invalid Shtorm normalization map");
        trie=new int[size/4];for(int i=0;i<trie.length;i++)trie[i]=buffer.getInt();
        replacements=Arrays.copyOfRange(data,4+size,data.length);
        validateTrie();
    }

    /** Includes SentencePiece's escaped dummy prefix, with no case folding. */
    public String normalize(String input) {
        if(input==null || input.length()>MAX_INPUT)throw new IllegalArgumentException("Shtorm text is missing or too long");
        for(int i=0;i<input.length();i++)if(Character.isSurrogate(input.charAt(i))) {
            if(!Character.isHighSurrogate(input.charAt(i)) || i+1==input.length() || !Character.isLowSurrogate(input.charAt(++i)))throw new IllegalArgumentException("Shtorm text contains malformed Unicode");
        }
        byte[] bytes=input.getBytes(StandardCharsets.UTF_8);int offset=0;
        while(offset<bytes.length) { Prefix p=prefix(bytes,offset);if(!p.text.equals(" "))break;offset+=p.bytes; }
        if(offset==bytes.length)return "";
        StringBuilder normalized=new StringBuilder("\u2581");boolean previousSpace=true;
        while(offset<bytes.length) {
            Prefix p=prefix(bytes,offset);String piece=p.text;int start=0;
            if(previousSpace)while(start<piece.length() && piece.charAt(start)==' ')start++;
            if(start<piece.length()) {
                if((long)normalized.length()+piece.length()-start>MAX_NORMALIZED)throw new IllegalArgumentException("Shtorm normalized text is too long");
                for(int i=start;i<piece.length();i++)normalized.append(piece.charAt(i)==' '?'\u2581':piece.charAt(i));
                previousSpace=piece.charAt(piece.length()-1)==' ';
            }
            offset+=p.bytes;
        }
        while(normalized.length()>0 && normalized.charAt(normalized.length()-1)=='\u2581')normalized.setLength(normalized.length()-1);
        return normalized.toString();
    }

    public int[] encode(String input) {
        String normalized=normalize(input);
        for(int i=0;i<normalized.length();) {
            int cp=normalized.codePointAt(i);i+=Character.charCount(cp);
            if(Character.UnicodeScript.of(cp)==Character.UnicodeScript.LATIN)throw new IllegalArgumentException("Shtorm supports Russian text; Latin text is unsupported");
        }
        return encodeNormalized(normalized);
    }

    /**
     * Never throws on content: malformed UTF-16 and over-long input are cut, then every normalized code point that
     * is not a vocabulary piece by itself (Latin, digits, emoji, other OOV) is dropped before BPE.
     */
    public int[] encodeSpeakable(String input) {
        if(input==null)return new int[0];
        StringBuilder clean=new StringBuilder(Math.min(input.length(),MAX_INPUT));
        for(int i=0;i<input.length() && clean.length()<MAX_INPUT-1;i++) {
            char c=input.charAt(i);
            if(Character.isHighSurrogate(c) && i+1<input.length() && Character.isLowSurrogate(input.charAt(i+1))){clean.append(c).append(input.charAt(++i));}
            else if(!Character.isSurrogate(c))clean.append(c);
        }
        String normalized=normalize(clean.toString());
        StringBuilder kept=new StringBuilder(normalized.length());
        for(int i=0;i<normalized.length();) {
            int cp=normalized.codePointAt(i);i+=Character.charCount(cp);
            boolean space=cp=='▁';
            if(space? kept.length()>0 && kept.charAt(kept.length()-1)!='▁' : singles.contains(cp) && Character.UnicodeScript.of(cp)!=Character.UnicodeScript.LATIN)kept.appendCodePoint(cp);
        }
        while(kept.length()>0 && kept.charAt(kept.length()-1)=='▁')kept.setLength(kept.length()-1);
        if(kept.length()==0 || kept.charAt(0)!='▁')kept.insert(0,'▁');
        return kept.length()==1?new int[0]:encodeNormalized(kept.toString());
    }

    private int[] encodeNormalized(String normalized) {
        if(normalized.isEmpty())return new int[0];
        List<Symbol> symbols=new ArrayList<>();
        for(int i=0;i<normalized.length();) {
            int end=i+Character.charCount(normalized.codePointAt(i));Symbol symbol=new Symbol(normalized.substring(i,end),symbols.size()-1,symbols.size()+1);symbols.add(symbol);i=end;
        }
        symbols.get(symbols.size()-1).next=-1;
        PriorityQueue<Pair> agenda=new PriorityQueue<>((a,b)->{int score=Float.compare(b.score,a.score);return score!=0?score:Integer.compare(a.left,b.left);});
        for(int i=0;i<symbols.size()-1;i++)addPair(symbols,agenda,i,i+1);
        while(!agenda.isEmpty()) {
            Pair pair=agenda.remove();Symbol left=symbols.get(pair.left),right=symbols.get(pair.right);
            if(left.version!=pair.leftVersion || right.version!=pair.rightVersion || left.next!=pair.right || right.piece.isEmpty())continue;
            left.piece+=right.piece;left.next=right.next;left.version++;right.piece="";right.version++;
            if(left.next>=0)symbols.get(left.next).previous=pair.left;
            addPair(symbols,agenda,left.previous,pair.left);addPair(symbols,agenda,pair.left,left.next);
        }
        int[] output=new int[symbols.size()];int count=0;
        for(int index=0;index>=0;index=symbols.get(index).next) {
            Integer id=vocabulary.get(symbols.get(index).piece);
            if(id==null || id<3)throw new IllegalArgumentException("Shtorm text contains unsupported characters or tokens");
            output[count++]=id;
        }
        return Arrays.copyOf(output,count);
    }
    /** Pass bare symbols: the separately pinned bridge vocabulary consumes Sherpa's prefix. */
    public String encodeBridge(String input) {
        int[] ids=encode(input);StringBuilder bridge=new StringBuilder(ids.length);
        for(int id:ids)bridge.append((char)(0xE000+id));
        return bridge.toString();
    }

    private void addPair(List<Symbol> symbols,PriorityQueue<Pair> agenda,int left,int right) {
        if(left<0 || right<0)return;Symbol a=symbols.get(left),b=symbols.get(right);String combined=a.piece+b.piece;
        Integer id=vocabulary.get(combined);if(id!=null && id>=3)agenda.add(new Pair(left,right,a.version,b.version,scores.get(combined)));
    }
    private static final class Symbol { String piece;int previous,next,version;Symbol(String p,int before,int after){piece=p;previous=before;next=after;} }
    private static final class Pair { final int left,right,leftVersion,rightVersion;final float score;Pair(int a,int b,int av,int bv,float s){left=a;right=b;leftVersion=av;rightVersion=bv;score=s;} }
    private static final class Prefix { final String text;final int bytes;Prefix(String value,int consumed){text=value;bytes=consumed;} }

    private Prefix prefix(byte[] input,int start) {
        int node=offset(trie[0]),longest=0,value=0;
        for(int i=start;i<input.length;i++) {
            node^=input[i]&255;int unit=unit(node);
            if((unit&0x800000FF)!=(input[i]&255))break;
            node^=offset(unit);
            if((unit&256)!=0){longest=i-start+1;value=unit(node)&0x7FFFFFFF;}
        }
        if(longest>0) {
            if(value>=replacements.length)throw new IllegalArgumentException("Damaged Shtorm normalizer");
            int end=value;while(end<replacements.length && replacements[end]!=0)end++;
            if(end==replacements.length)throw new IllegalArgumentException("Damaged Shtorm normalization string");
            return new Prefix(new String(replacements,value,end-value,StandardCharsets.UTF_8),longest);
        }
        int first=input[start]&255;int length=first<128?1:first<224?2:first<240?3:4;
        return new Prefix(new String(input,start,length,StandardCharsets.UTF_8),length);
    }
    private int unit(int index){if(index<0 || index>=trie.length)throw new IllegalArgumentException("Damaged Shtorm normalization trie");return trie[index];}
    private static int offset(int unit){return (unit>>>10)<<((unit&512)>>>6);}
    private void validateTrie() throws IOException {
        if((trie[0]&0x800001FF)!=0 || offset(trie[0])==0 || (offset(trie[0])|255)>=trie.length)throw new IOException("Invalid Shtorm normalization root");
        for(int i=1;i<trie.length;i++) {
            int label=trie[i]&0x800000FF;
            if(label>=0 && label<=255) { int target=i^offset(trie[i]);if(target<0 || (target|255)>=trie.length)throw new IOException("Invalid Shtorm normalization transition"); }
            else if((trie[i]&0x7FFFFFFF)>=replacements.length)throw new IOException("Invalid Shtorm normalization replacement");
        }
    }
    private static byte[] read(File file) throws IOException {
        if(!file.isFile() || file.length()>MAX_FILE)throw new IOException("Missing or oversized Shtorm frontend file: "+file.getName());
        try(InputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream((int)Math.min(file.length(),MAX_FILE))){
            byte[] buffer=new byte[8192];int n,total=0;
            while((n=input.read(buffer))!=-1){total+=n;if(total>MAX_FILE)throw new IOException("Shtorm frontend file exceeds size limit");output.write(buffer,0,n);}
            return output.toByteArray();
        }
    }
    private static JSONObject object(File file) throws IOException,JSONException {
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(read(file))).toString();
        JSONTokener tokener=new JSONTokener(text);Object result=tokener.nextValue();if(!(result instanceof JSONObject)||tokener.nextClean()!=0)throw new IOException("Invalid Shtorm frontend JSON");return (JSONObject)result;
    }
}
