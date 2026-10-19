package org.localvoice.tts;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Real files/streams plus controlled filesystem identities; no Android/runtime or network. */
public final class StorageIntegrityCheck {
    @org.junit.Test public void check() throws Exception { main(new String[0]); }
    interface Action {void run()throws Exception;}
    static int passed;
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static void reject(Action action)throws Exception{try{action.run();throw new AssertionError("Expected IOException");}catch(IOException expected){}}
    static void test(String name,Action action)throws Exception{action.run();passed++;System.out.println("PASS "+name);}
    static String hash(byte[] bytes)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    static final class Identity implements ModelFiles.StatReader {
        long ctime=1;boolean strong=true;int reads;boolean changeAfterFirst;
        final Map<String,Long> versions=new HashMap<>();
        public ModelFiles.Stamp read(File file)throws IOException{
            if(!file.exists())throw new FileNotFoundException();
            reads++;long token=ctime+versions.getOrDefault(file.getAbsolutePath(),0L);if(changeAfterFirst&&reads>1)token++;
            return new ModelFiles.Stamp(file.length(),"device:inode:"+file.lastModified()+":"+token,file.isFile(),strong);
        }
    }
    public static void main(String[] args)throws Exception {
        File root=Files.createTempDirectory("localvoice-integrity-").toFile();
        byte[] payload=new byte[192*1024];new Random(42).nextBytes(payload);String expected=hash(payload);
        File file=new File(root,"fixture.bin");
        try{
            test("fresh/coarse/future ctime cannot authorize cached identity",()->{
                check(!ModelFiles.preciseAndSettled(10,0,20000),"coarse second");
                check(!ModelFiles.preciseAndSettled(10,1,10000),"fresh collision window");
                check(!ModelFiles.preciseAndSettled(10,999999999,12000),"not settled two seconds");
                check(!ModelFiles.preciseAndSettled(20,1,10000),"clock rollback/future");
                check(ModelFiles.preciseAndSettled(10,1,12000),"precise settled identity");
            });
            test("strong identity skips only the second complete checksum",()->{
                Files.write(file.toPath(),payload);Identity identity=new Identity();ModelFiles.VerificationCache cache=new ModelFiles.VerificationCache(identity);
                StorageMetrics.start();check(cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"cold hash");long bytes=StorageMetrics.HASH_BYTES.value.get();
                check(cache.verified(file,payload.length,expected,"r1",()->false,n->{throw new AssertionError("warm cache read");}),"warm cache");
                check(bytes==payload.length&&StorageMetrics.HASH_BYTES.value.get()==bytes,"one checksum pass");check(StorageMetrics.FILE_CACHE_HITS.value.get()==1,"actual cache hit");StorageMetrics.finish();
            });
            test("same-size corruption with restored mtime invalidates ctime identity",()->{
                Files.write(file.toPath(),payload);Identity identity=new Identity();ModelFiles.VerificationCache cache=new ModelFiles.VerificationCache(identity);
                check(cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"prime");FileTime time=Files.getLastModifiedTime(file.toPath());
                try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.write(17);}Files.setLastModifiedTime(file.toPath(),time);identity.ctime++;
                check(file.length()==payload.length,"same size");check(!cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"corrupt hash rejected despite restored time");
            });
            test("actual host filesystem restored-mtime corruption rejected",()->{
                Files.write(file.toPath(),payload);ModelFiles.VerificationCache cache=new ModelFiles.VerificationCache(ModelFiles::portableStamp);
                check(cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"prime");FileTime time=Files.getLastModifiedTime(file.toPath());
                try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.write(17);}Files.setLastModifiedTime(file.toPath(),time);
                check(!cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"real filesystem change rejected");
            });
            test("weak seconds-resolution/API26 identity always rehashes",()->{
                Files.write(file.toPath(),payload);Identity identity=new Identity();identity.strong=false;ModelFiles.VerificationCache cache=new ModelFiles.VerificationCache(identity);
                StorageMetrics.start();check(cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"first");check(cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"second");
                check(StorageMetrics.HASH_BYTES.value.get()==payload.length*2L,"weak identity cannot authorize hit");StorageMetrics.finish();
                long mtime=file.lastModified();try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.write(17);}check(file.setLastModified(mtime),"restore time");
                check(!cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"collision corrupt rejected");
            });
            test("rapid restored-time mutation sharing fresh ctime tick is rehashed",()->{
                Files.write(file.toPath(),payload);Identity identity=new Identity();identity.strong=ModelFiles.preciseAndSettled(10,123000000,10123);
                ModelFiles.VerificationCache cache=new ModelFiles.VerificationCache(identity);check(cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"prime young file");
                long mtime=file.lastModified();try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.write(17);}check(file.setLastModified(mtime),"restore time");
                // The fake ctime and inode are deliberately identical: a fractional coarse clock tick collided.
                check(!cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"fresh metadata cannot mask same-tick corrupt bytes");
            });
            test("weak certificate cannot become trusted merely by aging",()->{
                Files.write(file.toPath(),payload);Identity identity=new Identity();identity.strong=false;
                ModelFiles.VerificationCache cache=new ModelFiles.VerificationCache(identity);check(cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"prime young file");
                long mtime=file.lastModified();try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.write(17);}check(file.setLastModified(mtime),"restore time");
                identity.strong=true; // same token becomes old enough; it must first be rehashed while strong.
                check(!cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"aging alone cannot promote weak proof");
            });
            test("hash crossing from young to settled keeps weak proof",()->{
                Files.write(file.toPath(),payload);Identity identity=new Identity();identity.strong=false;
                ModelFiles.VerificationCache cache=new ModelFiles.VerificationCache(identity);
                check(cache.verified(file,payload.length,expected,"r1",()->false,n->identity.strong=true),"hash completes as stamp settles");
                long mtime=file.lastModified();try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.write(17);}check(file.setLastModified(mtime),"restore time");
                check(!cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"both sides of hash must have precise settled stamps");
            });
            test("remember cannot promote young streamed proof to settled proof",()->{
                Files.write(file.toPath(),payload);Identity identity=new Identity();identity.strong=false;ModelFiles.Stamp young=identity.read(file);identity.strong=true;
                ModelFiles.VerificationCache cache=new ModelFiles.VerificationCache(identity);cache.remember(file,payload.length,expected,"r1",young);
                long mtime=file.lastModified();try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.write(17);}check(file.setLastModified(mtime),"restore time");
                check(!cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"remember preserves weak certificate strength");
            });
            test("directory activation cannot promote a young cached proof",()->{
                File from=new File(root,"move-from"),to=new File(root,"move-to");check(from.mkdir(),"source dir");File source=new File(from,"weight");Files.write(source.toPath(),payload);
                Identity identity=new Identity();identity.strong=false;ModelFiles.VerificationCache cache=new ModelFiles.VerificationCache(identity);
                check(cache.verified(source,payload.length,expected,"r1",()->false,n->{}),"young checked source");check(from.renameTo(to),"directory activation fixture");identity.strong=true;cache.relocated(from,to);
                File target=new File(to,"weight");long mtime=target.lastModified();try(RandomAccessFile out=new RandomAccessFile(target,"rw")){out.write(17);}check(target.setLastModified(mtime),"restore time");
                check(!cache.verified(target,payload.length,expected,"r1",()->false,n->{}),"move cannot strengthen certificate");
            });
            test("new revision and cold-process cache require complete hash",()->{
                Files.write(file.toPath(),payload);Identity identity=new Identity();ModelFiles.VerificationCache cache=new ModelFiles.VerificationCache(identity);
                check(cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"prime");StorageMetrics.start();
                check(cache.verified(file,payload.length,expected,"r2",()->false,n->{}),"revision");
                check(new ModelFiles.VerificationCache(identity).verified(file,payload.length,expected,"r2",()->false,n->{}),"cold cache");
                check(StorageMetrics.HASH_BYTES.value.get()==payload.length*2L,"both full hashes");StorageMetrics.finish();
            });
            test("changed expected checksum cannot reuse previous cache",()->{
                Files.write(file.toPath(),payload);Identity identity=new Identity();ModelFiles.VerificationCache cache=new ModelFiles.VerificationCache(identity);
                check(cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"prime");check(!cache.verified(file,payload.length,hash(new byte[payload.length]),"r1",()->false,n->{}),"hash key participates");
            });
            test("file mutation during hash never publishes cache",()->{
                Files.write(file.toPath(),payload);Identity identity=new Identity();identity.changeAfterFirst=true;ModelFiles.VerificationCache cache=new ModelFiles.VerificationCache(identity);
                reject(()->cache.verified(file,payload.length,expected,"r1",()->false,n->{}));
                identity.changeAfterFirst=false;StorageMetrics.start();check(cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"next request recovers");check(StorageMetrics.HASH_BYTES.value.get()==payload.length,"failed hash was not cached");StorageMetrics.finish();
            });
            test("cancel before cached hit and during hash is honored",()->{
                Files.write(file.toPath(),payload);Identity identity=new Identity();ModelFiles.VerificationCache cache=new ModelFiles.VerificationCache(identity);
                check(cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"prime");reject(()->cache.verified(file,payload.length,expected,"r1",()->true,n->{}));
                AtomicBoolean cancel=new AtomicBoolean();reject(()->cache.verified(file,payload.length,expected,"r2",cancel::get,n->cancel.set(true)));
            });
            test("remove/reinstall invalidates verified file records",()->{
                Files.write(file.toPath(),payload);Identity identity=new Identity();ModelFiles.VerificationCache cache=new ModelFiles.VerificationCache(identity);
                check(cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"prime");cache.forget(root);StorageMetrics.start();
                check(cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"rehash");check(StorageMetrics.HASH_BYTES.value.get()==payload.length,"forget clears cache");StorageMetrics.finish();
            });
            test("stream checksum exact bytes plus fsync without disk hash pass",()->{
                StorageMetrics.start();DownloadFile.copyVerified(new ByteArrayInputStream(payload),file,payload.length,expected,()->false,n->{});
                check(StorageMetrics.STREAM_HASH_BYTES.value.get()==payload.length,"stream hash");check(StorageMetrics.HASH_BYTES.value.get()==0,"no post-read");check(StorageMetrics.SYNC_CALLS.value.get()==1,"fsync");StorageMetrics.finish();
                check(Arrays.equals(payload,Files.readAllBytes(file.toPath())),"persisted bytes exact");
            });
            test("stream rejects wrong checksum, short and oversized source",()->{
                reject(()->DownloadFile.copyVerified(new ByteArrayInputStream(payload),file,payload.length,hash(new byte[payload.length]),()->false,n->{}));
                reject(()->DownloadFile.copyVerified(new ByteArrayInputStream(payload),file,payload.length+1,expected,()->false,n->{}));
                reject(()->DownloadFile.copyVerified(new ByteArrayInputStream(payload),file,payload.length-1,expected,()->false,n->{}));
                check(!new File(root,"installed.manifest").exists(),"no bad package receipt");
            });
            test("stream cancellation before/during writes prevents success",()->{
                reject(()->DownloadFile.copyVerified(new ByteArrayInputStream(payload),file,payload.length,expected,()->true,n->{}));
                AtomicBoolean cancelled=new AtomicBoolean();reject(()->DownloadFile.copyVerified(new ByteArrayInputStream(payload),file,payload.length,expected,cancelled::get,n->cancelled.set(true)));
            });
            test("partial transfer retries restart and validate complete bytes",()->{
                InputStream failed=new ByteArrayInputStream(payload){int reads;public synchronized int read(byte[] b,int o,int l){if(reads++==1)throw new UncheckedIOException(new IOException("disconnect"));return super.read(b,o,l);}};
                try{DownloadFile.copyVerified(failed,file,payload.length,expected,()->false,n->{});throw new AssertionError("failed stream");}catch(UncheckedIOException expectedFailure){}
                check(file.length()<payload.length,"incomplete prefix");DownloadFile.copyVerified(new ByteArrayInputStream(payload),file,payload.length,expected,()->false,n->{});check(ModelFiles.sha256(file).equals(expected),"whole retry validated");
            });
            test("missing/replaced regular file cannot use previous identity",()->{
                Files.write(file.toPath(),payload);Identity identity=new Identity();ModelFiles.VerificationCache cache=new ModelFiles.VerificationCache(identity);
                check(cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"prime");check(file.delete(),"delete");check(!cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"missing rejected");
                check(file.mkdir(),"directory replacement");check(!cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"directory rejected");check(file.delete(),"remove directory");
            });
            test("stream proof cannot bless a later same-size mutation",()->{
                Identity identity=new Identity();ModelFiles.VerificationCache cache=new ModelFiles.VerificationCache(identity);
                DownloadFile.VerifiedWrite written=DownloadFile.copyAndHash(new ByteArrayInputStream(payload),file,payload.length,expected,()->false,n->{},identity);
                long mtime=file.lastModified();try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.write(17);}check(file.setLastModified(mtime),"restored time");identity.ctime++;
                reject(()->cache.remember(file,payload.length,expected,"r1",written.stamp));
                check(!cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"mutated stream result not trusted");
            });
            test("final barrier rejects earlier file changed while later file verifies",()->{
                Files.write(file.toPath(),payload);Identity identity=new Identity();ModelFiles.VerificationCache cache=new ModelFiles.VerificationCache(identity);
                check(cache.verified(file,payload.length,expected,"r1",()->false,n->{}),"earlier verified");
                File later=new File(root,"later.bin");Files.write(later.toPath(),payload);
                check(cache.verified(later,payload.length,expected,"r1",()->false,n->{identity.versions.put(file.getAbsolutePath(),2L);}),"later verified");
                reject(()->cache.requireUnchanged(file,payload.length,expected,"r1"));check(!new File(root,"installed.manifest").exists(),"barrier failure cannot activate");
            });
            test("stream identity mutation before result is rejected",()->{
                Identity identity=new Identity();identity.changeAfterFirst=true;
                reject(()->DownloadFile.copyAndHash(new ByteArrayInputStream(payload),file,payload.length,expected,()->false,n->{},identity));
            });
            System.out.println("PASS all "+passed+" storage integrity checks");
        }finally{StorageMetrics.finish();ModelFiles.remove(root);}
    }
}
