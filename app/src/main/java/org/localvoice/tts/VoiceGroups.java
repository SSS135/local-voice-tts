package org.localvoice.tts;

import java.util.*;

/** Flattens voices into foldable model groups (and language sub-groups for split models) for the Voices list. */
final class VoiceGroups {
    private VoiceGroups() {}
    static final class Row {
        /** Null for a group header. */
        final Catalog.VoiceSpec voice;
        /** Fold-state key: model id, or model id + "/" + language tag for a sub-group. */
        final String key, label;
        final int depth, shown, total;
        final boolean expanded;
        Row(Catalog.VoiceSpec voice,String key,String label,int depth,int shown,int total,boolean expanded){this.voice=voice;this.key=key;this.label=label;this.depth=depth;this.shown=shown;this.total=total;this.expanded=expanded;}
    }
    private static final class Group { final String key,label;final List<Catalog.VoiceSpec> all=new ArrayList<>(),shown=new ArrayList<>();final Map<String,Group> languages=new LinkedHashMap<>();Group(String key,String label){this.key=key;this.label=label;} }

    /**
     * @param available installed voices in catalog order; groups follow first appearance
     * @param matched filter result (subset of available); groups without matches are hidden
     * @param filtering a query or language filter is active: groups default to expanded
     * @param modelLabels model id to header label
     * @param splitModels model ids whose voices are sub-grouped by language
     * @param anchor voice whose group and sub-group default to expanded when not filtering
     * @param folds explicit fold state by key; overrides every default
     */
    static List<Row> rows(List<Catalog.VoiceSpec> available,Collection<Catalog.VoiceSpec> matched,boolean filtering,Map<String,String> modelLabels,Set<String> splitModels,String anchor,Map<String,Boolean> folds,Locale displayLocale){
        Set<String> matchedIds=new HashSet<>();for(Catalog.VoiceSpec voice:matched)matchedIds.add(voice.id);
        Map<String,Group> groups=new LinkedHashMap<>();
        for(Catalog.VoiceSpec voice:available){
            Group group=groups.computeIfAbsent(voice.modelId,id->new Group(id,modelLabels.getOrDefault(id,id)));
            Group language=splitModels.contains(voice.modelId)?group.languages.computeIfAbsent(voice.languageTag,tag->new Group(voice.modelId+"/"+tag,Locale.forLanguageTag(tag).getDisplayName(displayLocale))):null;
            boolean shown=matchedIds.contains(voice.id);
            group.all.add(voice);if(shown)group.shown.add(voice);
            if(language!=null){language.all.add(voice);if(shown)language.shown.add(voice);}
        }
        List<Row> rows=new ArrayList<>();
        for(Group group:groups.values()){
            if(group.shown.isEmpty())continue;
            boolean open=expanded(group,filtering,anchor,folds);
            rows.add(new Row(null,group.key,group.label,0,group.shown.size(),group.all.size(),open));
            if(!open)continue;
            if(group.languages.isEmpty()){for(Catalog.VoiceSpec voice:group.shown)rows.add(new Row(voice,group.key,voice.label,1,1,1,true));continue;}
            for(Group language:group.languages.values()){
                if(language.shown.isEmpty())continue;
                boolean languageOpen=expanded(language,filtering,anchor,folds);
                rows.add(new Row(null,language.key,language.label,1,language.shown.size(),language.all.size(),languageOpen));
                if(languageOpen)for(Catalog.VoiceSpec voice:language.shown)rows.add(new Row(voice,language.key,voice.label,2,1,1,true));
            }
        }
        return rows;
    }
    private static boolean expanded(Group group,boolean filtering,String anchor,Map<String,Boolean> folds){
        Boolean saved=folds.get(group.key);if(saved!=null)return saved;
        if(filtering)return true;
        for(Catalog.VoiceSpec voice:group.all)if(voice.id.equals(anchor))return true;
        return false;
    }
}
