package org.localvoice.tts;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in, process-wide counters for matching before/after storage profiles. No paths or synthesis text. */
final class StorageMetrics {
    private static final List<Counter> ALL = new ArrayList<>();
    private static volatile boolean enabled;
    private static volatile long started, finished;
    static final class Counter {
        final String name;
        final AtomicLong value = new AtomicLong();
        Counter(String name) { this.name=name; ALL.add(this); }
        void add(long amount) { if(enabled) value.addAndGet(amount); }
        void increment() { add(1); }
    }
    static final Counter HASH_BYTES=new Counter("hashBytes"), HASH_READS=new Counter("hashReadCalls"),
        HASH_FILES=new Counter("hashFiles"), HASH_NANOS=new Counter("hashNanos"),
        VERIFY_CHECKS=new Counter("verificationChecks"), DISK_READ_BYTES=new Counter("diskReadBytes"),
        DOWNLOAD_BYTES=new Counter("downloadReadBytes"), DOWNLOAD_READS=new Counter("downloadReadCalls"),
        WRITE_BYTES=new Counter("writeBytes"), COPY_NANOS=new Counter("copyNanos"),
        SYNC_CALLS=new Counter("syncCalls"), SYNC_NANOS=new Counter("syncNanos"),
        RECEIPT_READS=new Counter("receiptReads"), RECEIPT_BYTES=new Counter("receiptReadBytes"),
        FILE_STATS=new Counter("fileMetadataCalls"), CANONICAL_CALLS=new Counter("canonicalCalls"),
        INSTALLED_CALLS=new Counter("isInstalledCalls"), INSTALLED_MAIN=new Counter("isInstalledMainCalls"),
        INSTALLED_NANOS=new Counter("isInstalledNanos"), VERIFY_INSTALLED_NANOS=new Counter("verifyInstalledNanos"),
        MODEL_CACHE_HITS=new Counter("modelCacheHits"), FILE_CACHE_HITS=new Counter("fileCacheHits"),
        FILE_CACHE_MISSES=new Counter("fileCacheMisses"), PROGRESS_CALLS=new Counter("progressCalls"),
        CHANGE_POSTS=new Counter("changePosts"), LISTENER_CALLS=new Counter("listenerCalls"),
        INSTALL_NANOS=new Counter("installNanos"), DECOMPRESSED_BYTES=new Counter("decompressedBytes"),
        EXTRACTED_FILES=new Counter("extractedFiles"), STREAM_HASH_BYTES=new Counter("streamHashBytes"),
        PROGRESS_WRITES=new Counter("progressWrites"), PROGRESS_SKIPS=new Counter("progressSkippedWrites"),
        FRESH_PROOFS=new Counter("freshVerificationProofs"), FRESH_FALLBACKS=new Counter("freshVerificationFallbacks"),
        SEALED_PROOFS=new Counter("sealedVerificationProofs");
    static synchronized void start() {
        enabled=false;
        for(Counter counter:ALL) counter.value.set(0);
        started=System.nanoTime();finished=0;enabled=true;
    }
    static long now() { return enabled ? System.nanoTime() : 0; }
    static void elapsed(Counter counter,long before) { if(before!=0) counter.add(System.nanoTime()-before); }
    static synchronized String finish() { finished=System.nanoTime();enabled=false;return json(); }
    static synchronized String json() {
        StringBuilder out=new StringBuilder("{\"wallNanos\":");
        out.append(started==0?0:(finished==0?System.nanoTime():finished)-started);
        for(Counter counter:ALL) out.append(",\"").append(counter.name).append("\":").append(counter.value.get());
        return out.append('}').toString();
    }
    private StorageMetrics() {}
}
