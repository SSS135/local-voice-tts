package org.localvoice.tts;
import java.util.*;

public final class VoiceGroupsCheck {
    @org.junit.Test public void check() throws Exception { main(new String[0]); }
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static Catalog.VoiceSpec voice(String model,String name,String tag){return new Catalog.VoiceSpec(model,name,name,tag,0,Collections.emptyMap());}
    private static String shape(List<VoiceGroups.Row> rows){StringBuilder out=new StringBuilder();for(VoiceGroups.Row row:rows)out.append(row.voice==null?(row.expanded?"+":"-")+row.key:row.voice.id).append(row.voice==null?"("+row.shown+"/"+row.total+")":"").append(' ');return out.toString().trim();}
    public static void main(String[] args){
        List<Catalog.VoiceSpec> all=Arrays.asList(voice("kokoro","a","en-US"),voice("kokoro","b","en-GB"),voice("silero","c","ru-RU"),voice("edge","d","en-US"),voice("edge","e","fr-FR"),voice("edge","f","en-US"));
        Map<String,String> labels=new HashMap<>();labels.put("kokoro","Kokoro · English");labels.put("silero","Silero · Russian");labels.put("edge","Microsoft Edge · Online");
        Set<String> split=Collections.singleton("edge");Map<String,Boolean> none=Collections.emptyMap();
        List<VoiceGroups.Row> rows=VoiceGroups.rows(all,all,false,labels,split,"silero:c",none,Locale.ENGLISH);
        check(shape(rows).equals("-kokoro(2/2) +silero(1/1) silero:c -edge(3/3)"),"catalog order; only the selected voice's group expanded: "+shape(rows));
        check(rows.get(0).label.equals("Kokoro · English"),"header uses model label");
        rows=VoiceGroups.rows(all,all,false,labels,split,"edge:f",none,Locale.ENGLISH);
        check(shape(rows).equals("-kokoro(2/2) -silero(1/1) +edge(3/3) +edge/en-US(2/2) edge:d edge:f -edge/fr-FR(1/1)"),"split model sub-groups by language; only selected language expanded: "+shape(rows));
        check(rows.get(3).label.equals("English (United States)")&&rows.get(3).depth==1&&rows.get(4).depth==2,"language sub-group label and depth");
        Map<String,Boolean> saved=new HashMap<>();saved.put("kokoro",true);saved.put("edge",false);
        check(shape(VoiceGroups.rows(all,all,false,labels,split,"edge:f",saved,Locale.ENGLISH)).equals("+kokoro(2/2) kokoro:a kokoro:b -silero(1/1) -edge(3/3)"),"saved fold state overrides selection default");
        List<Catalog.VoiceSpec> matched=Arrays.asList(all.get(0),all.get(4));
        check(shape(VoiceGroups.rows(all,matched,true,labels,split,"silero:c",Collections.emptyMap(),Locale.ENGLISH)).equals("+kokoro(1/2) kokoro:a +edge(1/3) +edge/fr-FR(1/1) edge:e"),"filter auto-expands matches, hides empty groups and sub-groups, counts matches");
        Map<String,Boolean> transientFolds=new HashMap<>();transientFolds.put("kokoro",false);
        check(shape(VoiceGroups.rows(all,matched,true,labels,split,"silero:c",transientFolds,Locale.ENGLISH)).equals("-kokoro(1/2) +edge(1/3) +edge/fr-FR(1/1) edge:e"),"explicit fold during filter collapses one group");
        check(VoiceGroups.rows(all,Collections.emptyList(),true,labels,split,"",none,Locale.ENGLISH).isEmpty(),"no matches: no rows");
        check(VoiceGroups.rows(Collections.emptyList(),Collections.emptyList(),false,labels,split,"",none,Locale.ENGLISH).isEmpty(),"no voices: no rows");
        check(shape(VoiceGroups.rows(all,all,false,labels,split,"",none,Locale.ENGLISH)).equals("-kokoro(2/2) -silero(1/1) -edge(3/3)"),"no selection: all collapsed");
        check(VoiceGroups.rows(Collections.singletonList(voice("unknown","x","en-US")),Collections.singletonList(voice("unknown","x","en-US")),false,labels,split,"",none,Locale.ENGLISH).get(0).label.equals("unknown"),"missing label falls back to model id");
        System.out.println("PASS: "+checks+" voice group cases (catalog order, selection default, saved folds, language sub-groups, filter expansion/counts/hiding)");
    }
}
