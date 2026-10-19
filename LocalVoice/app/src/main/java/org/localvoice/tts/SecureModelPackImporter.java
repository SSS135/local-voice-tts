package org.localvoice.tts;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.zip.*;

/** Imports only the exact application-pinned package. Never accepts configuration from a ZIP. */
final class SecureModelPackImporter {
    static void importPack(InputStream input,File staging,Catalog.Model model,BooleanSupplier cancelled,LongConsumer progress)throws IOException {
        importPack(input,staging,model,cancelled,n->{},progress);
    }
    static void importPack(InputStream input,File staging,Catalog.Model model,BooleanSupplier cancelled,LongConsumer archiveProgress,LongConsumer progress)throws IOException {
        long archiveSize=number(model,"importArchiveBytes"),manifestSize=number(model,"importManifestBytes");
        if(archiveSize>1_500_000_000L||manifestSize>262144||model.files.size()>1024)throw new IOException("Unsupported model package");
        File archive=ModelFiles.child(staging,".import-archive.zip");
        try {
            // A private bounded spool permits complete central-directory validation before extraction.
            // It is removed before publication; model storage checks reserve its full extra size.
            copyArchive(input,archive,archiveSize,pin(model,"importArchiveSha256"),cancelled,archiveProgress);
            validateEnvelope(archive,model.files.size()+1);
            Map<String,Catalog.ModelFile> allowed=new HashMap<>();
            for(Catalog.ModelFile file:model.files){ModelFiles.child(staging,file.path);if(allowed.put(file.path,file)!=null||file.path.equals("manifest.json")||file.path.equals(".import-archive.zip"))throw new IOException("Invalid pinned package plan");}
            Set<String> names=new HashSet<>();
            try(ZipFile zip=new ZipFile(archive)) {
                Enumeration<? extends ZipEntry> entries=zip.entries();
                while(entries.hasMoreElements()){
                    ZipEntry entry=entries.nextElement();String name=entry.getName();
                    if(entry.isDirectory()||!names.add(name)||(!allowed.containsKey(name)&&!name.equals("manifest.json")))throw new IOException("Unexpected model package entry");
                    ModelFiles.child(staging,name);
                    long expected=name.equals("manifest.json")?manifestSize:allowed.get(name).bytes;
                    if(entry.getSize()!=expected||entry.getMethod()!=ZipEntry.STORED&&entry.getMethod()!=ZipEntry.DEFLATED)throw new IOException("Invalid model package entry size or format");
                }
                if(names.size()!=allowed.size()+1||!names.contains("manifest.json")||!names.containsAll(allowed.keySet()))throw new IOException("Incomplete model package");
            }
            // Also walk local headers: reject hidden/duplicate local records and CRC damage.
            Set<String> extracted=new HashSet<>();long[] total={0};
            try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))){
                ZipEntry entry;
                while((entry=zip.getNextEntry())!=null){
                    check(cancelled);String name=entry.getName();
                    if(!names.contains(name)||!extracted.add(name)||entry.isDirectory())throw new IOException("Invalid local model package entry");
                    long base=total[0];
                    if(name.equals("manifest.json")){
                        java.security.MessageDigest digest=digest();long count=0;byte[] buffer=new byte[65536];
                        for(int n;(n=zip.read(buffer))!=-1;){check(cancelled);if(n>manifestSize-count)throw new IOException("Oversized package manifest");count+=n;digest.update(buffer,0,n);StorageMetrics.DECOMPRESSED_BYTES.add(n);progress.accept(base+count);}
                        if(count!=manifestSize||!hex(digest.digest()).equals(pin(model,"importManifestSha256")))throw new IOException("Package manifest checksum mismatch");
                        total[0]+=count;
                    }else{
                        Catalog.ModelFile file=allowed.get(name);File target=ModelFiles.child(staging,name);
                        if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IOException("Cannot create model package folder");
                        DownloadFile.copyAndHash(zip,target,file.bytes,file.sha256,cancelled,n->progress.accept(base+n),null,false);
                        total[0]+=file.bytes;StorageMetrics.EXTRACTED_FILES.increment();
                    }
                    zip.closeEntry();
                }
            }
            if(!extracted.equals(names))throw new IOException("Incomplete local package entries");
            check(cancelled);
        } finally {if(archive.exists()&&!archive.delete())throw new IOException("Cannot remove private import archive");}
    }
    private static void copyArchive(InputStream input,File target,long expected,String hash,BooleanSupplier cancelled,LongConsumer progress)throws IOException{
        java.security.MessageDigest digest=digest();long count=0;int emptyReads=0;
        try(FileOutputStream output=new FileOutputStream(target)){
            byte[] buffer=new byte[65536];
            while(true){
                check(cancelled);int n=input.read(buffer);if(n==-1)break;
                check(cancelled);if(n==0){if(++emptyReads>16)throw new IOException("File provider stopped responding");continue;}emptyReads=0;
                if(n>expected-count)throw new IOException("Selected package exceeds pinned size");
                count+=n;StorageMetrics.DISK_READ_BYTES.add(n);StorageMetrics.WRITE_BYTES.add(n);StorageMetrics.STREAM_HASH_BYTES.add(n);
                digest.update(buffer,0,n);output.write(buffer,0,n);progress.accept(count);
            }
            check(cancelled);if(count!=expected||!hex(digest.digest()).equals(hash))throw new IOException("Selected package checksum mismatch");
            long before=StorageMetrics.now();StorageMetrics.SYNC_CALLS.increment();try{output.getFD().sync();}finally{StorageMetrics.elapsed(StorageMetrics.SYNC_NANOS,before);}
        }
    }
    private static long number(Catalog.Model model,String key)throws IOException{try{long value=Long.parseLong(model.config.get(key));if(value<0)throw new NumberFormatException();return value;}catch(RuntimeException e){throw new IOException("Invalid pinned package size");}}
    private static String pin(Catalog.Model model,String key)throws IOException{String value=model.config.get(key);if(value==null||!value.matches("[a-f0-9]{64}"))throw new IOException("Invalid pinned package checksum");return value;}
    private static void check(BooleanSupplier cancellation)throws IOException{if(cancellation.getAsBoolean())throw new InterruptedIOException("Model import cancelled");}
    private static java.security.MessageDigest digest(){try{return java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static long u32(byte[] b,int i){return (b[i]&255L)|((b[i+1]&255L)<<8)|((b[i+2]&255L)<<16)|((b[i+3]&255L)<<24);}
    private static int u16(byte[] b,int i){return (b[i]&255)|((b[i+1]&255)<<8);}
    private static void validateEnvelope(File file,int entries)throws IOException{
        try(RandomAccessFile input=new RandomAccessFile(file,"r")){
            int length=(int)Math.min(file.length(),65557);byte[] tail=new byte[length];input.seek(file.length()-length);input.readFully(tail);
            for(int i=length-22;i>=0;i--)if(u32(tail,i)==0x06054b50L&&i+22+u16(tail,i+20)==length){
                long offset=file.length()-length+i;
                if(u16(tail,i+4)!=0||u16(tail,i+6)!=0||u16(tail,i+8)!=entries||u16(tail,i+10)!=entries||u32(tail,i+12)+u32(tail,i+16)!=offset)throw new IOException("Invalid model ZIP directory");
                return;
            }
            throw new IOException("Truncated model ZIP directory");
        }
    }
}
