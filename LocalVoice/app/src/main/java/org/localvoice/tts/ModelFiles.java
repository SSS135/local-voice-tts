package org.localvoice.tts;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.concurrent.TimeUnit;

/** Disk-only installation rules, also exercised without an Android device. */
final class ModelFiles {
    private ModelFiles() {}

    static File child(File root, String relative) throws IOException {
        if (relative.isEmpty() || relative.startsWith("/") || relative.contains("\\") || relative.contains(":"))
            throw new IOException("Unsafe model path");
        for (String part : relative.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Unsafe model path");
        File lexical=new File(root,relative).getAbsoluteFile();
        requireOwnedPath(root,lexical);
        StorageMetrics.CANONICAL_CALLS.increment();
        File file = new File(root, relative).getCanonicalFile();
        StorageMetrics.CANONICAL_CALLS.increment();
        if (!file.getPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("Model path escapes storage");
        return file;
    }

    /** WORK owns this download namespace; platform root aliases are bound once, never directories' validity. */
    static final class DownloadPaths {
        private final Path root,staging,anchor;
        DownloadPaths(File modelsRoot,File directory)throws IOException {
            root=modelsRoot.toPath().toAbsolutePath().normalize();
            staging=directory.toPath().toAbsolutePath().normalize();
            if(!root.equals(staging.getParent()))throw new IOException("Invalid download storage");
            directory(root);directory(staging);
            StorageMetrics.CANONICAL_CALLS.add(2);
            Path canonicalRoot=modelsRoot.getCanonicalFile().toPath();
            anchor=directory.getCanonicalFile().toPath();
            if(!anchor.equals(canonicalRoot.resolve(staging.getFileName())))throw new IOException("Model path escapes storage");
        }
        File plan(String relative)throws IOException {
            if(relative.isEmpty()||relative.startsWith("/")||relative.contains("\\")||relative.contains(":"))throw new IOException("Unsafe model path");
            for(String part:relative.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("Unsafe model path");
            return anchor.resolve(relative).toFile();
        }
        File prepareWrite(String relative)throws IOException {return checked(relative,true);}
        File checked(String relative)throws IOException {return checked(relative,false);}
        private File checked(String relative,boolean writing)throws IOException {
            File result=plan(relative);
            // Recheck the original alias path at every IO boundary, including after network waits.
            directory(root);directory(staging);
            Path current=staging;String[] parts=relative.split("/");
            for(int i=0;i<parts.length;i++){
                current=current.resolve(parts[i]);boolean parent=i+1<parts.length;
                BasicFileAttributes attributes;
                try{attributes=attributes(current);}
                catch(NoSuchFileException missing){
                    if(!writing)throw missing;
                    if(!parent)return result;
                    Files.createDirectory(current);
                    attributes=attributes(current);
                }
                if(parent?!attributes.isDirectory():!attributes.isRegularFile())throw new IOException("Invalid download path");
            }
            return result;
        }
        private static BasicFileAttributes attributes(Path path)throws IOException {
            StorageMetrics.FILE_STATS.increment();
            return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        }
        private static void directory(Path path)throws IOException {
            if(!attributes(path).isDirectory())throw new IOException("Invalid download folder");
        }
    }

    static String sha256(File file) throws IOException {
        return sha256(file,()->false,n->{});
    }
    static String sha256(File file,BooleanSupplier cancelled,LongConsumer progress) throws IOException {
        try(InputStream input=new FileInputStream(file)){return sha256(input,cancelled,progress);}
    }
    static String sha256(InputStream input,BooleanSupplier cancelled,LongConsumer progress) throws IOException {
        return sha256(input,-1,cancelled,progress);
    }
    private static String sha256(InputStream input,long expected,BooleanSupplier cancelled,LongConsumer progress) throws IOException {
        long before=StorageMetrics.now();StorageMetrics.HASH_FILES.increment();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] bytes = new byte[65536];long count=0;int emptyReads=0;
                int n;
                while (true) {
                    if(cancelled.getAsBoolean())throw new InterruptedIOException("Verification cancelled");
                    int limit=expected<0||expected-count>=bytes.length?bytes.length:(int)(expected-count+1);
                    n=input.read(bytes,0,limit);StorageMetrics.HASH_READS.increment();
                    if(n==-1) break;
                    if(n==0){if(++emptyReads>16)throw new IOException("Model file stopped responding");continue;}emptyReads=0;
                    StorageMetrics.HASH_BYTES.add(n);StorageMetrics.DISK_READ_BYTES.add(n);
                    if(expected>=0&&n>expected-count)throw new IOException("Model file grew during verification");
                    count+=n;
                    digest.update(bytes,0,n);
                    progress.accept(n);
                }
                if(expected>=0&&count!=expected)throw new IOException("Model file changed size during verification");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        finally {StorageMetrics.elapsed(StorageMetrics.HASH_NANOS,before);}
    }

    static final class Stamp {
        final long size;final String token;final boolean regular,strong,precise,sealCandidate,sealed;
        Stamp(long size,String token,boolean regular,boolean strong){this(size,token,regular,strong,strong);}
        Stamp(long size,String token,boolean regular,boolean strong,boolean precise){this(size,token,regular,strong,precise,false,false);}
        Stamp(long size,String token,boolean regular,boolean strong,boolean precise,boolean sealCandidate,boolean sealed){this.size=size;this.token=token;this.regular=regular;this.strong=strong;this.precise=precise;this.sealCandidate=sealCandidate;this.sealed=sealed;}
        boolean same(Stamp other){return regular&&other.regular&&size==other.size&&token.equals(other.token);}
    }
    interface StatReader {Stamp read(File file)throws IOException;}
    interface HeldFile extends Closeable {
        InputStream input();Stamp stamp()throws IOException;
        default boolean seal()throws IOException{return false;}
    }
    interface FileOpener {HeldFile open(File file)throws IOException;}

    static void requireOwnedPath(File root,File file)throws IOException {
        Path anchor=root.toPath().toAbsolutePath().normalize(),path=file.toPath().toAbsolutePath().normalize();
        if(!path.startsWith(anchor)){
            // Windows may expand a trusted root's 8.3 alias when child() canonicalizes.
            StorageMetrics.CANONICAL_CALLS.increment();Path canonicalAnchor=root.getCanonicalFile().toPath();
            if(!path.startsWith(canonicalAnchor))throw new IOException("Model path escapes storage");
            path=anchor.resolve(canonicalAnchor.relativize(path));
        }
        if(path.equals(anchor))throw new IOException("Model path escapes storage");
        for(Path current=path;current!=null&&current.startsWith(anchor);current=current.getParent()){
            StorageMetrics.FILE_STATS.increment();
            if(Files.isSymbolicLink(current))throw new IOException("Symbolic link in model storage");
        }
    }
    /** Only successful creation by this job proves that there are no staged files to reuse. */
    static boolean prepareStaging(File root,File staging)throws IOException {
        requireOwnedPath(root,staging);
        StorageMetrics.FILE_STATS.increment();
        try {
            BasicFileAttributes attributes=Files.readAttributes(staging.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attributes.isDirectory())throw new IOException("Invalid download storage");
            return false;
        }catch(NoSuchFileException absent){
            Files.createDirectory(staging.toPath());
            requireOwnedPath(root,staging);
            return true;
        }
    }
    // This eligibility is only for an exclusive private installation job, never a cache hit.
    static boolean preciseFresh(long seconds,long nanos,long nowMillis){
        return nanos>0&&nanos%1000L!=0&&validTimestamp(seconds,nanos,nowMillis);
    }
    static boolean validTimestamp(long seconds,long nanos,long nowMillis){
        if(seconds<0||nowMillis<0||nanos<0||nanos>=1_000_000_000L)return false;
        return seconds<nowMillis/1000L||seconds==nowMillis/1000L&&nanos/1_000_000L<=nowMillis%1000L;
    }
    /** One stored-byte hash per new file. Certificates exist only while this job owns staging.
     * Every staging writer/remover is serialized by ModelStore.WORK; other same-UID/root
     * writers are outside that boundary. Sealed files rely on that ownership plus kernel 0400
     * permissions; this is not protection against hostile same-UID/root chmod/write/restore.
     * These certificates never strengthen generic cache records.
     */
    static final class FreshVerification implements Closeable {
        static final int MAX_OPEN_FILES=16;
        static final long MIN_FILE_BYTES=1024L*1024L;
        private static final class Proof {
            final HeldFile file;final Stamp stamp;final String expected;final boolean sealed;
            Proof(HeldFile file,Stamp stamp,String expected,boolean sealed){this.file=file;this.stamp=stamp;this.expected=expected;this.sealed=sealed;}
        }
        private final File staging;private final String revision;private final StatReader stats;private final FileOpener opener;
        private final Map<String,Proof> proofs=new HashMap<>();
        private boolean closed;
        FreshVerification(File staging,String revision,StatReader stats,FileOpener opener){this.staging=staging;this.revision=revision;this.stats=stats;this.opener=opener;}
        private void path(File file)throws IOException {
            requireOwnedPath(staging.getParentFile(),file);
            StorageMetrics.CANONICAL_CALLS.add(2);
            if(!file.getCanonicalFile().toPath().startsWith(staging.getCanonicalFile().toPath()))throw new IOException("File outside installation job");
        }
        private static void check(BooleanSupplier cancelled)throws IOException {if(cancelled.getAsBoolean())throw new InterruptedIOException("Verification cancelled");}
        boolean verifyFresh(File file,long size,String hash,BooleanSupplier cancelled,LongConsumer progress)throws IOException {
            check(cancelled);if(closed)throw new IOException("Installation verification is closed");
            if(size<MIN_FILE_BYTES||proofs.size()>=MAX_OPEN_FILES){StorageMetrics.FRESH_FALLBACKS.increment();return false;}
            path(file);Stamp before=stats.read(file);
            if(!before.regular||before.size!=size)throw new IOException("Downloaded file identity changed");
            if(!before.precise&&!before.sealCandidate){StorageMetrics.FRESH_FALLBACKS.increment();return false;}
            HeldFile held=opener.open(file);if(held==null){StorageMetrics.FRESH_FALLBACKS.increment();return false;}boolean retained=false;
            try {
                Stamp fdBefore=held.stamp();
                if(!before.same(fdBefore))throw new IOException("Downloaded file descriptor changed");
                boolean sealed=false;
                if(before.sealCandidate){
                    if(!fdBefore.sealCandidate)throw new IOException("Downloaded file ownership changed");
                    try{sealed=held.seal();}catch(IOException unavailable){StorageMetrics.FRESH_FALLBACKS.increment();return false;}
                    if(!sealed){StorageMetrics.FRESH_FALLBACKS.increment();return false;}
                    // fchmod legitimately changes ctime. Bind its new FD/path stamp before hashing.
                    fdBefore=held.stamp();path(file);before=stats.read(file);
                    if(!fdBefore.sealed||!before.sealed||!before.same(fdBefore))throw new IOException("Cannot verify read-only model seal");
                }else if(!fdBefore.precise)throw new IOException("Downloaded file identity changed");
                if(!sha256(held.input(),size,cancelled,progress).equals(hash))throw new IOException("Checksum mismatch. Retry the download");
                Stamp fdAfter=held.stamp();path(file);Stamp after=stats.read(file);check(cancelled);
                if(!(sealed?fdAfter.sealed&&after.sealed:fdAfter.precise&&after.precise)||!before.same(fdAfter)||!before.same(after))throw new IOException("Downloaded file changed during verification");
                String key=file.getAbsolutePath();if(proofs.containsKey(key))throw new IOException("File verified twice in one installation");
                proofs.put(key,new Proof(held,after,revision+":"+size+":"+hash,sealed));retained=true;StorageMetrics.FRESH_PROOFS.increment();if(sealed)StorageMetrics.SEALED_PROOFS.increment();return true;
            }finally{if(!retained)held.close();}
        }
        boolean accepts(File file,long size,String hash)throws IOException {
            if(closed)throw new IOException("Installation verification is closed");
            Proof proof=proofs.get(file.getAbsolutePath());if(proof==null)return false;
            path(file);Stamp descriptor=proof.file.stamp(),current=stats.read(file);
            if(!proof.expected.equals(revision+":"+size+":"+hash)||!(proof.sealed?descriptor.sealed&&current.sealed:descriptor.precise&&current.precise)||!proof.stamp.same(descriptor)||!proof.stamp.same(current))
                throw new IOException("Model changed before activation");
            return true;
        }
        public void close()throws IOException {
            closed=true;IOException first=null;
            for(Proof proof:proofs.values())try{proof.file.close();}catch(IOException failure){if(first==null)first=failure;}
            proofs.clear();if(first!=null)throw first;
        }
    }
    // A second-resolution/coarsely rounded or freshly changed identity cannot prove that two
    // rapid writes were distinct. Rehash until the observed ctime is safely in the past.
    static boolean preciseAndSettled(long seconds,long nanos,long nowMillis){
        if(nanos<=0||nanos>=1_000_000_000L)return false;
        long changedMillis=seconds*1000L+nanos/1_000_000L;
        return nowMillis>=changedMillis&&nowMillis-changedMillis>=2000L;
    }
    static Stamp portableStamp(File file)throws IOException {
        try {
            StorageMetrics.FILE_STATS.increment();
            Map<String,Object> a=Files.readAttributes(file.toPath(),"unix:dev,ino,size,mode,lastModifiedTime,ctime",LinkOption.NOFOLLOW_LINKS);
            long ctime=((FileTime)a.get("ctime")).to(TimeUnit.NANOSECONDS);
            return new Stamp((Long)a.get("size"),a.get("dev")+":"+a.get("ino")+":"+((FileTime)a.get("lastModifiedTime")).to(TimeUnit.NANOSECONDS)+":"+ctime,(((Integer)a.get("mode"))&0170000)==0100000,preciseAndSettled(ctime/1_000_000_000L,ctime%1_000_000_000L,System.currentTimeMillis()));
        }catch(UnsupportedOperationException|IllegalArgumentException unsupported){
            StorageMetrics.FILE_STATS.increment();
            BasicFileAttributes a=Files.readAttributes(file.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            return new Stamp(a.size(),a.fileKey()+":"+a.lastModifiedTime().to(TimeUnit.NANOSECONDS),a.isRegularFile(),false);
        }
    }
    /** Per-process only. Weak/second-resolution identities never authorize a cache hit. */
    static final class VerificationCache {
        private static final class Record {final Stamp stamp;final String expected;Record(Stamp s,String e){stamp=s;expected=e;}}
        private final Map<String,Record> records=new HashMap<>();
        private final StatReader stats;
        VerificationCache(StatReader stats){this.stats=stats;}
        synchronized boolean verified(File file,long size,String hash,String revision,BooleanSupplier cancelled,LongConsumer progress)throws IOException {
            if(cancelled.getAsBoolean())throw new InterruptedIOException("Verification cancelled");
            StorageMetrics.VERIFY_CHECKS.increment();
            String key=file.getAbsolutePath(),expected=revision+":"+size+":"+hash;
            Stamp before;
            try {before=stats.read(file);}catch(NoSuchFileException|FileNotFoundException missing){records.remove(key);return false;}
            if(!before.regular||before.size!=size){records.remove(key);return false;}
            Record known=records.get(key);
            if(before.strong&&known!=null&&known.stamp.strong&&known.expected.equals(expected)&&before.same(known.stamp)){
                StorageMetrics.FILE_CACHE_HITS.increment();return true;
            }
            StorageMetrics.FILE_CACHE_MISSES.increment();
            if(!sha256(file,cancelled,progress).equals(hash)){records.remove(key);return false;}
            Stamp after=stats.read(file);
            if(cancelled.getAsBoolean())throw new InterruptedIOException("Verification cancelled");
            if(!before.same(after)){records.remove(key);throw new IOException("Model file changed during verification");}
            records.put(key,new Record(new Stamp(after.size,after.token,after.regular,before.strong&&after.strong),expected));return true;
        }
        synchronized void remember(File file,long size,String hash,String revision,Stamp proof)throws IOException {
            Stamp stamp=stats.read(file);if(proof==null||!stamp.regular||stamp.size!=size||!proof.same(stamp))throw new IOException("Verified file changed after download");
            records.put(file.getAbsolutePath(),new Record(new Stamp(stamp.size,stamp.token,stamp.regular,proof.strong&&stamp.strong),revision+":"+size+":"+hash));
        }
        synchronized void requireUnchanged(File file,long size,String hash,String revision)throws IOException {
            Record known=records.get(file.getAbsolutePath());Stamp now=stats.read(file);
            if(known==null||!known.expected.equals(revision+":"+size+":"+hash)||!known.stamp.same(now))throw new IOException("Model changed before activation");
        }
        synchronized void forget(File directory){String prefix=directory.getAbsolutePath()+File.separator;records.keySet().removeIf(key->key.startsWith(prefix));}
        synchronized void relocated(File from,File to)throws IOException {
            String prefix=from.getAbsolutePath()+File.separator;Map<String,Record> moved=new HashMap<>();
            for(Map.Entry<String,Record> entry:records.entrySet())if(entry.getKey().startsWith(prefix)){
                File target=new File(to,entry.getKey().substring(prefix.length()));Stamp now=stats.read(target);
                if(entry.getValue().stamp.same(now))moved.put(target.getAbsolutePath(),new Record(new Stamp(now.size,now.token,now.regular,entry.getValue().stamp.strong&&now.strong),entry.getValue().expected));
            }
            forget(from);records.putAll(moved);
        }
    }

    static boolean verified(File file, long size, String hash) throws IOException {
        StorageMetrics.VERIFY_CHECKS.increment();StorageMetrics.FILE_STATS.increment();
        if(!file.isFile()) return false;
        StorageMetrics.FILE_STATS.increment();
        return file.length() == size && sha256(file).equals(hash);
    }

    static void writeReceipt(File directory, String revision) throws IOException {
        try (FileOutputStream out = new FileOutputStream(child(directory, "installed.manifest"))) {
            out.write(revision.getBytes(StandardCharsets.UTF_8));
            long before=StorageMetrics.now();StorageMetrics.SYNC_CALLS.increment();
            try {out.getFD().sync();} finally {StorageMetrics.elapsed(StorageMetrics.SYNC_NANOS,before);}
        }
    }

    static boolean receiptMatches(File directory, String revision) {
        try {
            File receipt = child(directory, "installed.manifest");
            StorageMetrics.FILE_STATS.increment();
            if (!receipt.isFile()) return false;
            StorageMetrics.FILE_STATS.increment();
            if(receipt.length() != revision.getBytes(StandardCharsets.UTF_8).length) return false;
            StorageMetrics.FILE_STATS.increment();
            byte[] value = new byte[(int) receipt.length()];
            StorageMetrics.RECEIPT_READS.increment();
            try (DataInputStream in = new DataInputStream(new FileInputStream(receipt))) {
                in.readFully(value);StorageMetrics.RECEIPT_BYTES.add(value.length);StorageMetrics.DISK_READ_BYTES.add(value.length);
            }
            return revision.equals(new String(value, StandardCharsets.UTF_8));
        } catch (IOException e) { return false; }
    }

    static void activate(File staging, File installed) throws IOException {
        if (installed.exists() || !staging.renameTo(installed)) throw new IOException("Cannot activate model atomically");
    }

    static void remove(File root) throws IOException {
        remove(root,()->false);
    }
    static void remove(File root,BooleanSupplier cancelled) throws IOException {
        if(cancelled.getAsBoolean())throw new InterruptedIOException("Storage operation cancelled");
        if(Files.isSymbolicLink(root.toPath()))throw new IOException("Unsafe stored path");
        File[] children = root.listFiles();
        if (children != null) for (File child : children) {
            if (!child.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())) throw new IOException("Unsafe stored path");
            remove(child,cancelled);
        }
        if (root.exists() && !root.delete()) throw new IOException("Cannot remove " + root.getName());
    }
}
