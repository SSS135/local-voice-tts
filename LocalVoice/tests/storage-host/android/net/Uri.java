package android.net;
import java.io.File;
public final class Uri {
    public final File fixture;
    private Uri(File file){fixture=file;}
    public static Uri fromFile(File file){return new Uri(file);}
    public static Uri parse(String encoded){return new Uri(new File(encoded));}
    @Override public String toString(){return fixture.getAbsolutePath();}
    @Override public boolean equals(Object other){return other instanceof Uri&&fixture.equals(((Uri)other).fixture);}
    @Override public int hashCode(){return fixture.hashCode();}
}
