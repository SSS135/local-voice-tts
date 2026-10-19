package org.localvoice.tts;

import java.io.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Bounded streaming shared by the downloader and interruption/cancellation tests. */
final class DownloadFile {
    interface Sync {void force(java.nio.channels.FileChannel output)throws IOException;}
    /** Fresh private staging only: checksum the stored file once after this writer closes. */
    static void copyStored(InputStream input,File part,long expected,BooleanSupplier cancelled,LongConsumer progress)throws IOException {
        copyStored(input,part,expected,cancelled,progress,output->output.force(true));
    }
    static void copyStored(InputStream input,File part,long expected,BooleanSupplier cancelled,LongConsumer progress,Sync sync)throws IOException {
        long before=StorageMetrics.now(),count=0;int emptyReads=0;
        try(java.nio.channels.FileChannel output=java.nio.channels.FileChannel.open(part.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
            byte[] buffer=new byte[65536];
            while(true){
                if(cancelled.getAsBoolean())throw new InterruptedIOException("Download cancelled");
                int n=input.read(buffer);StorageMetrics.DOWNLOAD_READS.increment();
                if(n==-1)break;
                if(cancelled.getAsBoolean())throw new InterruptedIOException("Download cancelled");
                if(n==0){if(++emptyReads>16)throw new IOException("Download stopped responding");continue;}emptyReads=0;
                if(n>expected-count)throw new IOException("Downloaded file exceeds pinned size");
                StorageMetrics.DOWNLOAD_BYTES.add(n);
                java.nio.ByteBuffer bytes=java.nio.ByteBuffer.wrap(buffer,0,n);
                while(bytes.hasRemaining()){if(cancelled.getAsBoolean())throw new InterruptedIOException("Download cancelled");output.write(bytes);}
                StorageMetrics.WRITE_BYTES.add(n);count+=n;progress.accept(count);
            }
            if(count!=expected||output.size()!=expected)throw new IOException("Download was interrupted. Retry to continue");
            if(cancelled.getAsBoolean())throw new InterruptedIOException("Download cancelled");
            long syncStarted=StorageMetrics.now();StorageMetrics.SYNC_CALLS.increment();
            try{sync.force(output);}finally{StorageMetrics.elapsed(StorageMetrics.SYNC_NANOS,syncStarted);}
        }finally{StorageMetrics.elapsed(StorageMetrics.COPY_NANOS,before);}
        if(cancelled.getAsBoolean())throw new InterruptedIOException("Download cancelled");
    }
    static String copyVerified(InputStream input,File part,long expected,String expectedHash,BooleanSupplier cancelled,LongConsumer progress)throws IOException {
        return copyAndHash(input,part,expected,expectedHash,cancelled,progress,null).hash;
    }
    static final class VerifiedWrite {
        final String hash;final ModelFiles.Stamp stamp;
        VerifiedWrite(String hash,ModelFiles.Stamp stamp){this.hash=hash;this.stamp=stamp;}
    }
    static VerifiedWrite copyAndHash(InputStream input,File part,long expected,String expectedHash,BooleanSupplier cancelled,LongConsumer progress,ModelFiles.StatReader stats)throws IOException {
        return copyAndHash(input,part,expected,expectedHash,cancelled,progress,stats,true);
    }
    static VerifiedWrite copyAndHash(InputStream input,File part,long expected,String expectedHash,BooleanSupplier cancelled,LongConsumer progress,ModelFiles.StatReader stats,boolean networkTransfer)throws IOException {
        long before=StorageMetrics.now(),count=0;
        ModelFiles.Stamp proof=null;
        try {
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            try(FileOutputStream output=new FileOutputStream(part)){
                byte[] buffer=new byte[65536];
                while(true){
                    if(cancelled.getAsBoolean())throw new InterruptedIOException("Download cancelled");
                    int n=input.read(buffer);
                    if(networkTransfer)StorageMetrics.DOWNLOAD_READS.increment();
                    if(n==-1)break;
                    if(networkTransfer)StorageMetrics.DOWNLOAD_BYTES.add(n);else StorageMetrics.DECOMPRESSED_BYTES.add(n);
                    if(cancelled.getAsBoolean())throw new InterruptedIOException("Download cancelled");
                    if(n>expected-count)throw new IOException("Downloaded file exceeds pinned size");
                    output.write(buffer,0,n);StorageMetrics.WRITE_BYTES.add(n);digest.update(buffer,0,n);StorageMetrics.STREAM_HASH_BYTES.add(n);count+=n;progress.accept(count);
                }
                long sync=StorageMetrics.now();StorageMetrics.SYNC_CALLS.increment();
                try{output.getFD().sync();}finally{StorageMetrics.elapsed(StorageMetrics.SYNC_NANOS,sync);}
                if(stats!=null)proof=stats.read(part);
            }
            if(cancelled.getAsBoolean())throw new InterruptedIOException("Download cancelled");
            if(count!=expected||part.length()!=expected)throw new IOException("Download was interrupted. Retry to continue");
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            String hash=hex.toString();if(!hash.equals(expectedHash))throw new IOException("Checksum mismatch. Retry the download");
            if(proof!=null&&(!proof.regular||proof.size!=expected||!proof.same(stats.read(part))))throw new IOException("Downloaded file changed during verification");
            return new VerifiedWrite(hash,proof);
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        finally{StorageMetrics.elapsed(StorageMetrics.COPY_NANOS,before);}
    }
    static void copy(InputStream input, File part, long expected, BooleanSupplier cancelled, LongConsumer progress) throws IOException {
        long before=StorageMetrics.now();
        long count=0;
        try (FileOutputStream output=new FileOutputStream(part)) {
            byte[] buffer=new byte[65536];
            while (true) {
                if(cancelled.getAsBoolean()) throw new InterruptedIOException("Download cancelled");
                int n=input.read(buffer);StorageMetrics.DOWNLOAD_READS.increment();
                if(n==-1) break;
                StorageMetrics.DOWNLOAD_BYTES.add(n);
                if(cancelled.getAsBoolean()) throw new InterruptedIOException("Download cancelled");
                count+=n;
                if(count>expected) throw new IOException("Downloaded file exceeds pinned size");
                output.write(buffer,0,n);StorageMetrics.WRITE_BYTES.add(n); progress.accept(count);
            }
            long sync=StorageMetrics.now();StorageMetrics.SYNC_CALLS.increment();
            try {output.getFD().sync();} finally {StorageMetrics.elapsed(StorageMetrics.SYNC_NANOS,sync);}
        } finally {
            StorageMetrics.elapsed(StorageMetrics.COPY_NANOS,before);
        }
        if(count!=expected) throw new IOException("Download was interrupted. Retry to continue");
    }
    static void requireSpace(long available,long remaining,long reserve) throws IOException {
        if(remaining<0 || available<remaining || available-remaining<reserve)
            throw new IOException("Not enough free storage. Need "+((remaining+reserve+1048575)/1048576)+" MiB free");
    }
}
