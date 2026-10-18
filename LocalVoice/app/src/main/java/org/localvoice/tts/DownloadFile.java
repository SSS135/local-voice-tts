package org.localvoice.tts;

import java.io.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Bounded streaming shared by the downloader and interruption/cancellation tests. */
final class DownloadFile {
    static void copy(InputStream input, File part, long expected, BooleanSupplier cancelled, LongConsumer progress) throws IOException {
        long count=0;
        try (FileOutputStream output=new FileOutputStream(part)) {
            byte[] buffer=new byte[65536];
            while (true) {
                if(cancelled.getAsBoolean()) throw new InterruptedIOException("Download cancelled");
                int n=input.read(buffer);
                if(n==-1) break;
                if(cancelled.getAsBoolean()) throw new InterruptedIOException("Download cancelled");
                count+=n;
                if(count>expected) throw new IOException("Downloaded file exceeds pinned size");
                output.write(buffer,0,n); progress.accept(count);
            }
            output.getFD().sync();
        }
        if(count!=expected) throw new IOException("Download was interrupted. Retry to continue");
    }
    static void requireSpace(long available,long remaining,long reserve) throws IOException {
        if(remaining<0 || available<remaining || available-remaining<reserve)
            throw new IOException("Not enough free storage. Need "+((remaining+reserve+1048575)/1048576)+" MiB free");
    }
}
