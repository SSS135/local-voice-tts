package org.localvoice.tts;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;

/** Adversarial ZIP fixtures against the production importer; never downloads model weights. */
public final class ModelPackImportCheck {
    interface Action{void run()throws Exception;}
    static int passed;
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static void reject(Action action)throws Exception{try{action.run();throw new AssertionError("Expected IOException");}catch(IOException expected){}}
    static void test(String name,Action action)throws Exception{action.run();passed++;System.out.println("PASS "+name);}
    static String hash(byte[] bytes)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    static byte[] one=new byte[98304],two="vocabulary and scores fixture".getBytes(StandardCharsets.UTF_8),manifest="{\"id\":\"fixture\",\"revision\":\"r1\",\"note\":\"configuration is pinned in the application\"}".getBytes(StandardCharsets.UTF_8);
    static Catalog.Model model()throws Exception{
        return model(valid());
    }
    static Catalog.Model model(byte[] archive)throws Exception{
        Map<String,String> config=new HashMap<>();config.put("importManifestSha256",hash(manifest));config.put("importManifestBytes",Integer.toString(manifest.length));
        config.put("importArchiveBytes",Integer.toString(archive.length));config.put("importArchiveSha256",hash(archive));
        return new Catalog.Model("fixture","Fixture","fixture","r1",false,true,Collections.emptyList(),Collections.emptyList(),Arrays.asList(new Catalog.ModelFile("one.bin","https://example.invalid/one",one.length,hash(one)),new Catalog.ModelFile("two.bin","https://example.invalid/two",two.length,hash(two))),config,"test","https://example.invalid/license","fixture","","fixture");
    }
    static byte[] zip(String[] names,byte[][] data)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream out=new ZipOutputStream(bytes,StandardCharsets.UTF_8)){
            for(int i=0;i<names.length;i++){
                ZipEntry entry=new ZipEntry(names[i]);entry.setTime(315532800000L);entry.setMethod(ZipEntry.STORED);entry.setSize(data[i].length);CRC32 crc=new CRC32();crc.update(data[i]);entry.setCrc(crc.getValue());out.putNextEntry(entry);out.write(data[i]);out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    static byte[] valid()throws IOException{return zip(new String[]{"manifest.json","one.bin","two.bin"},new byte[][]{manifest,one,two});}
    static File staging(File root)throws IOException{File result=new File(root,"case-"+UUID.randomUUID());if(!result.mkdir())throw new IOException("fixture mkdir");return result;}
    static void importZip(byte[] zip,File directory)throws Exception{SecureModelPackImporter.importPack(new ByteArrayInputStream(zip),directory,model(zip),()->false,n->{});check(!new File(directory,"installed.manifest").exists(),"helper never publishes receipt");}
    static byte[] replaceName(byte[] bytes,String before,String after){check(before.length()==after.length(),"equal name length");byte[] result=bytes.clone(),from=before.getBytes(StandardCharsets.UTF_8),to=after.getBytes(StandardCharsets.UTF_8);for(int i=0;i<=result.length-from.length;i++){boolean match=true;for(int j=0;j<from.length;j++)match&=result[i+j]==from[j];if(match){System.arraycopy(to,0,result,i,to.length);i+=to.length-1;}}return result;}
    public static void main(String[] args)throws Exception{
        new Random(99).nextBytes(one);File root=Files.createTempDirectory("localvoice-pack-").toFile();
        try{
            test("complete exact pinned archive extracts without activating",()->{File dir=staging(root);importZip(valid(),dir);check(ModelFiles.sha256(new File(dir,"one.bin")).equals(hash(one)),"one hash");check(ModelFiles.sha256(new File(dir,"two.bin")).equals(hash(two)),"two hash");});
            test("manifest may follow payload with same exact pins",()->importZip(zip(new String[]{"two.bin","one.bin","manifest.json"},new byte[][]{two,one,manifest}),staging(root)));
            test("missing payload rejected",()->reject(()->importZip(zip(new String[]{"manifest.json","one.bin"},new byte[][]{manifest,one}),staging(root))));
            test("missing manifest rejected",()->reject(()->importZip(zip(new String[]{"one.bin","two.bin"},new byte[][]{one,two}),staging(root))));
            test("unlisted extra file rejected",()->reject(()->importZip(zip(new String[]{"manifest.json","one.bin","two.bin","extra.txt"},new byte[][]{manifest,one,two,new byte[0]}),staging(root))));
            test("duplicate central and local entry rejected",()->reject(()->importZip(replaceName(valid(),"two.bin","one.bin"),staging(root))));
            test("traversal absolute drive and backslash names rejected",()->{for(String unsafe:new String[]{"../escape.bin","/absolute.bin","C:/drive.bin","folder\\bad.bin","a/../one.bin"})reject(()->importZip(zip(new String[]{unsafe},new byte[][]{one}),staging(root)));check(!new File(root,"escape.bin").exists(),"ZipSlip creates no outside file");});
            test("directory entry rejected",()->reject(()->importZip(zip(new String[]{"folder/"},new byte[][]{new byte[0]}),staging(root))));
            test("same-size modified runtime bytes rejected",()->{byte[] bad=one.clone();bad[0]^=1;reject(()->importZip(zip(new String[]{"manifest.json","one.bin","two.bin"},new byte[][]{manifest,bad,two}),staging(root)));});
            test("same-size modified manifest rejected",()->{byte[] bad=manifest.clone();bad[3]^=1;reject(()->importZip(zip(new String[]{"manifest.json","one.bin","two.bin"},new byte[][]{bad,one,two}),staging(root)));});
            test("short and oversized pinned entry rejected",()->{reject(()->importZip(zip(new String[]{"manifest.json","one.bin","two.bin"},new byte[][]{manifest,Arrays.copyOf(one,one.length-1),two}),staging(root)));reject(()->importZip(zip(new String[]{"manifest.json","one.bin","two.bin"},new byte[][]{manifest,Arrays.copyOf(one,one.length+1),two}),staging(root)));});
            test("truncated payload rejected",()->{byte[] complete=valid();reject(()->importZip(Arrays.copyOf(complete,complete.length/2),staging(root)));});
            test("truncated central directory and EOCD rejected",()->{byte[] complete=valid();reject(()->importZip(Arrays.copyOf(complete,complete.length-22),staging(root)));reject(()->importZip(Arrays.copyOf(complete,complete.length-1),staging(root)));});
            test("CRC damage rejected",()->{byte[] bad=valid();int offset=30+"manifest.json".length()+manifest.length+30+"one.bin".length();bad[offset]^=1;reject(()->importZip(bad,staging(root)));});
            test("whole archive byte size and checksum pins enforced",()->{
                byte[] bad=valid();bad[20]^=1;reject(()->SecureModelPackImporter.importPack(new ByteArrayInputStream(bad),staging(root),model(),()->false,n->{}));
                byte[] extra=Arrays.copyOf(valid(),valid().length+1);reject(()->SecureModelPackImporter.importPack(new ByteArrayInputStream(extra),staging(root),model(),()->false,n->{}));
            });
            test("cancel before read and during decompression rejected",()->{
                File first=staging(root);reject(()->SecureModelPackImporter.importPack(new ByteArrayInputStream(valid()),first,model(),()->true,n->{}));
                AtomicBoolean cancel=new AtomicBoolean();File second=staging(root);reject(()->SecureModelPackImporter.importPack(new ByteArrayInputStream(valid()),second,model(),cancel::get,n->cancel.set(true)));
                check(!new File(first,"installed.manifest").exists()&&!new File(second,"installed.manifest").exists(),"cancellation never activates");
            });
            test("cancel while reading archive removes bounded spool and retry succeeds",()->{
                AtomicBoolean cancel=new AtomicBoolean();byte[] bytes=valid();File dir=staging(root);
                InputStream source=new FilterInputStream(new ByteArrayInputStream(bytes)){public int read(byte[] b,int off,int len)throws IOException{int n=super.read(b,off,len);if(n>0)cancel.set(true);return n;}};
                reject(()->SecureModelPackImporter.importPack(source,dir,model(),cancel::get,n->{}));
                check(!new File(dir,".import-archive.zip").exists(),"cancelled spool removed");check(!new File(dir,"installed.manifest").exists(),"no activation");
                importZip(bytes,dir);check(ModelFiles.sha256(new File(dir,"one.bin")).equals(hash(one)),"explicit retry validates full package");
            });
            test("interrupted input removes private spool and never publishes",()->{
                byte[] bytes=valid();File dir=staging(root);
                InputStream source=new FilterInputStream(new ByteArrayInputStream(bytes)){int received;public int read(byte[] b,int off,int len)throws IOException{if(received>0)throw new IOException("injected provider interruption");int n=super.read(b,off,Math.min(len,1024));received+=n;return n;}};
                reject(()->SecureModelPackImporter.importPack(source,dir,model(),()->false,n->{}));
                check(!new File(dir,".import-archive.zip").exists(),"interrupted spool removed");check(!new File(dir,"installed.manifest").exists(),"no receipt");
            });
            test("oversized archive plan rejected before consuming source",()->{
                Catalog.Model plan=model();plan.config.put("importArchiveBytes","1500000001");AtomicBoolean read=new AtomicBoolean();
                InputStream source=new InputStream(){public int read(){read.set(true);return -1;}};
                reject(()->SecureModelPackImporter.importPack(source,staging(root),plan,()->false,n->{}));check(!read.get(),"compressed cap enforced before read");
            });
            test("progress bounded and monotonic; extraction counters truthful",()->{
                long[] previous={0};Catalog.Model plan=model();StorageMetrics.start();
                SecureModelPackImporter.importPack(new ByteArrayInputStream(valid()),staging(root),plan,()->false,n->{check(n>=previous[0],"monotonic progress");check(n<=plan.bytes()+manifest.length,"bounded total");previous[0]=n;});
                check(StorageMetrics.DECOMPRESSED_BYTES.value.get()==plan.bytes()+manifest.length,"actual decompressed bytes including checked manifest");check(StorageMetrics.EXTRACTED_FILES.value.get()==2,"only runtime files written; manifest checked without extraction");System.out.println("  counters "+StorageMetrics.finish());
            });
            System.out.println("PASS all "+passed+" model-pack import checks");
        }finally{StorageMetrics.finish();ModelFiles.remove(root);}
    }
}
