package org.localvoice.tts.backend;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Embedded baseline catalog: constructing or reading this list never contacts Microsoft. */
public final class EdgeVoices {
    public final String shortName, locale, name, friendlyName, gender;
    private EdgeVoices(String shortName, String display, String gender) {
        this.shortName = shortName;
        this.locale = shortName.substring(0, shortName.indexOf('-', shortName.indexOf('-') + 1));
        this.name = "Microsoft Server Speech Text to Speech Voice (" + locale + ", " + shortName.substring(locale.length() + 1) + ")";
        this.friendlyName = display;
        this.gender = gender;
    }
    public static final List<EdgeVoices> BASELINE = Collections.unmodifiableList(Arrays.asList(
            new EdgeVoices("en-US-AriaNeural", "Microsoft Aria Online (Natural)", "Female"),
            new EdgeVoices("en-US-GuyNeural", "Microsoft Guy Online (Natural)", "Male"),
            new EdgeVoices("ru-RU-SvetlanaNeural", "Microsoft Svetlana Online (Natural)", "Female"),
            new EdgeVoices("ru-RU-DmitryNeural", "Microsoft Dmitry Online (Natural)", "Male")));
}
