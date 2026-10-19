package org.localvoice.tts.backend;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Fault injection against production helpers; virtual waits except one bounded real cancellation check. */
public final class EdgeRetryContractTest {
    @org.junit.Test public void check() throws Exception { main(new String[0]); }
    interface Checked { void run() throws Exception; }
    interface Wait { void apply(int count) throws InterruptedException; }
    private static int passed;
    private static final float[] COMPLETE = {.1f, .2f, .3f};
    static final class Network implements EdgeRetry.Network {
        boolean available=true;
        long now;
        final List<Long> delays = new ArrayList<>();
        Wait wait = count -> {};
        @Override public boolean isAvailable() { return available; }
        @Override public long nowMillis() { return now; }
        @Override public void await(long milliseconds, SpeechBackend.Cancellation cancellation) throws InterruptedException {
            check(milliseconds > 0 && milliseconds <= EdgeRetry.MAX_DELAY_MS, "unbounded retry delay");
            delays.add(milliseconds); now+=milliseconds; wait.apply(delays.size());
        }
    }
    public static void main(String[] args) throws Exception {
        test("offline waits without issuing requests, then recovers", EdgeRetryContractTest::offlineRecovery);
        test("transient failures retry the same text without abandonment", EdgeRetryContractTest::manyFailures);
        test("long offline period has no retry/time budget", EdgeRetryContractTest::longOffline);
        test("truncation and decode faults commit only recovered PCM", EdgeRetryContractTest::invalidAttempts);
        test("ordered chunks commit once with no skipped text", EdgeRetryContractTest::orderedChunks);
        test("cancellation before attempt emits no PCM", EdgeRetryContractTest::cancelBefore);
        test("cancellation during backoff prevents another attempt", EdgeRetryContractTest::cancelBackoff);
        test("cancellation after in-flight result discards stale PCM", EdgeRetryContractTest::cancelInFlight);
        test("replacement invalidates pending attempt and preserves fresh output", EdgeRetryContractTest::replacement);
        test("fatal authorization and voice errors do not retry", EdgeRetryContractTest::fatalErrors);
        test("configuration errors are surfaced without retry", EdgeRetryContractTest::configurationError);
        test("empty validation result retries without early success", EdgeRetryContractTest::nullResult);
        test("wait interruption exits without false completion", EdgeRetryContractTest::interruptedWait);
        test("complete MP3 frames required before decode", EdgeRetryContractTest::mp3Validation);
        test("compressed audio size is capped per attempt", EdgeRetryContractTest::mp3Bounds);
        test("decoded audio samples and finite PCM are bounded", EdgeRetryContractTest::pcmBounds);
        test("empty and unfinished PCM cannot commit", EdgeRetryContractTest::pcmRequiresCompletion);
        test("completed PCM cannot gain unchecked samples", EdgeRetryContractTest::pcmSealed);
        test("stop before PCM commit emits nothing", EdgeRetryContractTest::cancelBeforeCommit);
        test("stop during PCM commit suppresses later blocks", EdgeRetryContractTest::cancelDuringCommit);
        test("sink rejection stops commit without retrying audio", EdgeRetryContractTest::sinkRejects);
        test("validated PCM cannot commit twice", EdgeRetryContractTest::noSecondCommit);
        test("server Retry-After delays retry using bounded waits", EdgeRetryContractTest::retryAfter);
        test("stop interrupts a long Retry-After delay", EdgeRetryContractTest::cancelRetryAfter);
        test("default wait wakes promptly when cancelled", EdgeRetryContractTest::defaultWaitCancellation);
        test("unsupported audio format is fatal without retry", EdgeRetryContractTest::fatalFormat);
        System.out.println("PASS: " + passed + " Edge retry contract tests");
    }
    private static void test(String name, Checked action) throws Exception { action.run(); passed++; System.out.println("PASS " + name); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void cancelled(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("cancelled request completed"); }
        catch (EdgeRetry.CancelledException expected) { }
    }
    private static void await(CountDownLatch latch) throws InterruptedException { check(latch.await(3, TimeUnit.SECONDS), "worker timed out"); }
    private static void offlineRecovery() throws Exception {
        Network network=new Network(); network.available=false; AtomicInteger requests=new AtomicInteger();
        List<Boolean> state=new ArrayList<>();
        network.wait = count -> { check(requests.get()==0, "requested while offline"); if(count==7) network.available=true; };
        float[] result=EdgeRetry.run(() -> { requests.incrementAndGet(); return COMPLETE; }, () -> false, network, (waiting,attempts)->state.add(waiting));
        check(result==COMPLETE && requests.get()==1 && network.delays.size()==7, "did not recover exact pending request");
        check(state.get(0) && !state.get(state.size()-1), "waiting status not cleared");
    }
    private static void manyFailures() throws Exception {
        Network network=new Network(); List<String> requested=new ArrayList<>(); AtomicInteger attempts=new AtomicInteger();
        String pending="The exact pending text stays unchanged.";
        float[] result=EdgeRetry.run(() -> {
            requested.add(pending);
            if(attempts.incrementAndGet()<=512) throw new IOException("injected DNS/socket/stall");
            return COMPLETE;
        }, () -> false, network, EdgeRetry.NO_OBSERVER);
        check(result==COMPLETE && attempts.get()==513 && network.delays.size()==512, "fixed abandonment budget");
        check(requested.stream().allMatch(pending::equals), "pending text was changed or skipped");
        check(network.delays.stream().mapToLong(Long::longValue).sum()>60*60*1000L, "virtual failures did not exceed one hour");
        check(network.delays.stream().allMatch(delay->delay>=EdgeRetry.INITIAL_DELAY_MS),"normal backoff below minimum");
        check(network.delays.get(0)<2*EdgeRetry.INITIAL_DELAY_MS && network.delays.get(20)>=EdgeRetry.MAX_DELAY_MS*3/4, "backoff did not grow and cap");
    }
    private static void longOffline() throws Exception {
        Network network=new Network(); network.available=false; AtomicInteger attempts=new AtomicInteger();
        network.wait=count->{ if(count==10000) network.available=true; };
        float[] result=EdgeRetry.run(()->{attempts.incrementAndGet();return COMPLETE;},()->false,network,EdgeRetry.NO_OBSERVER);
        check(result==COMPLETE && attempts.get()==1 && network.delays.size()==10000, "offline request abandoned or polled transport");
    }
    private static void invalidAttempts() throws Exception {
        Network network=new Network(); AtomicInteger attempts=new AtomicInteger(); List<float[]> committed=new ArrayList<>();
        EdgeAudio.Pcm result=EdgeRetry.run(()->{
            int n=attempts.incrementAndGet();
            check(committed.isEmpty(), "partial audio committed before validation completed");
            if(n==1) throw new IOException("MP3 truncated before turn.end");
            byte[] mp3=frames();
            EdgeAudio.validateMp3(n==2 ? Arrays.copyOf(mp3,mp3.length-1) : mp3);
            EdgeAudio.Pcm stage=new EdgeAudio.Pcm();
            if(n==3) { stage.add(new float[]{.9f}); throw new IOException("decoder failed after producing temporary PCM"); }
            stage.add(COMPLETE);return stage.complete();
        },()->false,network,EdgeRetry.NO_OBSERVER);
        check(result.commit(()->false,block->{committed.add(block);return true;}),"recovered PCM did not commit");
        check(attempts.get()==4 && committed.size()==1 && Arrays.equals(committed.get(0),COMPLETE), "failed attempt leaked/repeated partial PCM");
    }
    private static void orderedChunks() throws Exception {
        List<String> chunks=List.of("First sentence.","Second sentence.","Last sentence.");
        List<String> requested=new ArrayList<>(), committed=new ArrayList<>();
        for(int i=0;i<chunks.size();i++) {
            String chunk=chunks.get(i);float marker=i+1;
            AtomicInteger attempts=new AtomicInteger();
            EdgeAudio.Pcm result=EdgeRetry.run(()->{
                requested.add(chunk);if(attempts.incrementAndGet()<4) throw new IOException("drop");
                EdgeAudio.validateMp3(frames());EdgeAudio.Pcm pcm=new EdgeAudio.Pcm();pcm.add(new float[]{marker});return pcm.complete();
            },()->false,new Network(),EdgeRetry.NO_OBSERVER);
            check(result.commit(()->false,block->{check(block.length==1&&block[0]==marker,"wrong chunk PCM");committed.add(chunk);return true;}),"chunk did not complete");
        }
        check(committed.equals(chunks), "chunks duplicated, skipped or reordered");
        for(int i=0;i<chunks.size();i++) for(int j=0;j<4;j++) check(requested.get(i*4+j).equals(chunks.get(i)), "advanced text before recovery");
    }
    private static void cancelBefore() throws Exception {
        AtomicInteger attempts=new AtomicInteger(); Network network=new Network();
        cancelled(()->EdgeRetry.run(()->{attempts.incrementAndGet();return COMPLETE;},()->true,network,EdgeRetry.NO_OBSERVER));
        check(attempts.get()==0 && network.delays.isEmpty(), "cancelled request started work");
    }
    private static void cancelBackoff() throws Exception {
        AtomicBoolean stop=new AtomicBoolean(); AtomicInteger attempts=new AtomicInteger(); Network network=new Network();
        network.wait=count->stop.set(true);
        cancelled(()->EdgeRetry.run(()->{attempts.incrementAndGet();throw new IOException("offline");},stop::get,network,EdgeRetry.NO_OBSERVER));
        check(attempts.get()==1 && network.delays.size()==1, "attempt after backoff cancellation");
    }
    private static void cancelInFlight() throws Exception {
        AtomicBoolean stop=new AtomicBoolean(); CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), done=new CountDownLatch(1);
        AtomicReference<Throwable> error=new AtomicReference<>(); AtomicInteger committed=new AtomicInteger();
        Thread worker=new Thread(()->{
            try {
                EdgeRetry.run(()->{entered.countDown();await(release);return COMPLETE;},stop::get,new Network(),EdgeRetry.NO_OBSERVER);
                committed.incrementAndGet();
            } catch(EdgeRetry.CancelledException expected) { } catch(Throwable e) {error.set(e);} finally {done.countDown();}
        },"edge-retry-fake-transport");
        worker.setDaemon(true); worker.start();
        try { await(entered);stop.set(true);release.countDown();await(done); }
        finally { release.countDown(); }
        check(error.get()==null && committed.get()==0, "late in-flight result committed after stop: "+error.get());
    }
    private static void replacement() throws Exception {
        AtomicInteger generation=new AtomicInteger(1); AtomicInteger oldCommitted=new AtomicInteger();
        Network network=new Network();network.wait=count->generation.incrementAndGet();
        cancelled(()->{EdgeRetry.run(()->{throw new IOException("old voice offline");},()->generation.get()!=1,network,EdgeRetry.NO_OBSERVER);oldCommitted.incrementAndGet();});
        float[] replacement=EdgeRetry.run(()->COMPLETE,()->generation.get()!=2,new Network(),EdgeRetry.NO_OBSERVER);
        check(oldCommitted.get()==0 && replacement==COMPLETE, "stale retry poisoned replacement generation");
    }
    private static void fatalErrors() throws Exception {
        for(String reason:List.of("HTTP 401 unauthorized","HTTP 403 forbidden","HTTP 400 invalid voice")) {
            AtomicInteger attempts=new AtomicInteger();Network network=new Network();EdgeRetry.FatalException fatal=new EdgeRetry.FatalException(reason);
            try { EdgeRetry.run(()->{attempts.incrementAndGet();throw fatal;},()->false,network,EdgeRetry.NO_OBSERVER);throw new AssertionError("fatal error returned success"); }
            catch(EdgeRetry.FatalException actual) { check(actual==fatal,"fatal cause replaced"); }
            check(attempts.get()==1 && network.delays.isEmpty(),"fatal error retried");
        }
    }
    private static void configurationError() throws Exception {
        AtomicInteger attempts=new AtomicInteger();Network network=new Network();
        try {EdgeRetry.run(()->{attempts.incrementAndGet();throw new IllegalArgumentException("invalid voice config");},()->false,network,EdgeRetry.NO_OBSERVER);throw new AssertionError("configuration error returned success");}
        catch(IllegalArgumentException expected) { }
        check(attempts.get()==1 && network.delays.isEmpty(),"configuration error retried");
    }
    private static void nullResult() throws Exception {
        AtomicInteger attempts=new AtomicInteger();Network network=new Network();
        float[] result=EdgeRetry.run(()->attempts.incrementAndGet()<3?null:COMPLETE,()->false,network,EdgeRetry.NO_OBSERVER);
        check(attempts.get()==3 && result==COMPLETE,"null audio completed request");
    }
    private static void interruptedWait() throws Exception {
        Network network=new Network();network.wait=count->{throw new InterruptedException("shutdown");};
        try {EdgeRetry.run(()->{throw new IOException("drop");},()->false,network,EdgeRetry.NO_OBSERVER);throw new AssertionError("interrupted retry returned success");}
        catch(InterruptedException expected) { }
        check(network.delays.size()==1,"continued after interruption");
    }
    private static byte[] frames() {
        byte[] bytes=new byte[288];
        for(int start:new int[]{0,144}) { bytes[start]=(byte)0xff;bytes[start+1]=(byte)0xf3;bytes[start+2]=0x64;bytes[start+3]=(byte)0xc4; }
        return bytes;
    }
    private static void ioFailure(Checked action) throws Exception {
        try {action.run();throw new AssertionError("invalid audio was accepted");} catch(IOException expected) { }
    }
    private static void mp3Validation() throws Exception {
        byte[] valid=frames();EdgeAudio.validateMp3(valid);
        for(int size:new int[]{0,1,3,143,144,145,287}) ioFailure(()->EdgeAudio.validateMp3(Arrays.copyOf(valid,size)));
        byte[] wrongRate=valid.clone();wrongRate[2]=0x60;ioFailure(()->EdgeAudio.validateMp3(wrongRate));
        byte[] stereo=valid.clone();stereo[3]=(byte)0x84;ioFailure(()->EdgeAudio.validateMp3(stereo));
        byte[] invalidHeader=valid.clone();invalidHeader[0]=0;ioFailure(()->EdgeAudio.validateMp3(invalidHeader));
        byte[] invalidTag=new byte[10];invalidTag[0]='I';invalidTag[1]='D';invalidTag[2]='3';invalidTag[6]=(byte)0x80;
        ioFailure(()->EdgeAudio.validateMp3(invalidTag));
    }
    private static void mp3Bounds() throws Exception { ioFailure(()->EdgeAudio.validateMp3(new byte[EdgeAudio.MAX_MP3_BYTES+1])); }
    private static void pcmBounds() throws Exception {
        EdgeAudio.Pcm finite=new EdgeAudio.Pcm();
        for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}) ioFailure(()->finite.add(new float[]{.1f,bad}));
        check(finite.samples()==0,"invalid block partially staged");
        EdgeAudio.Pcm bounded=new EdgeAudio.Pcm();float[] block=new float[1000];
        for(int i=0;i<EdgeAudio.MAX_PCM_SAMPLES/1000;i++) bounded.add(block);
        int remaining=EdgeAudio.MAX_PCM_SAMPLES-bounded.samples();if(remaining>0) bounded.add(new float[remaining]);
        ioFailure(()->bounded.add(new float[1]));
        check(bounded.samples()==EdgeAudio.MAX_PCM_SAMPLES,"PCM limit not exact or overflow altered state");bounded.complete();
    }
    private static void pcmRequiresCompletion() throws Exception {
        ioFailure(()->new EdgeAudio.Pcm().complete());
        EdgeAudio.Pcm unfinished=new EdgeAudio.Pcm();unfinished.add(COMPLETE);AtomicInteger writes=new AtomicInteger();
        try {unfinished.commit(()->false,b->{writes.incrementAndGet();return true;});throw new AssertionError("unfinished PCM committed");}
        catch(IllegalStateException expected) { }
        check(writes.get()==0,"PCM leaked before complete validation");
    }
    private static void pcmSealed() throws Exception {
        EdgeAudio.Pcm pcm=new EdgeAudio.Pcm();pcm.add(COMPLETE);pcm.complete();
        try {pcm.add(new float[]{.9f});throw new AssertionError("validated PCM mutated");} catch(IllegalStateException expected) { }
    }
    private static EdgeAudio.Pcm twoBlocks() throws IOException {EdgeAudio.Pcm pcm=new EdgeAudio.Pcm();pcm.add(new float[]{.1f});pcm.add(new float[]{.2f});return pcm.complete();}
    private static void cancelBeforeCommit() throws Exception {
        AtomicInteger writes=new AtomicInteger();check(!twoBlocks().commit(()->true,b->{writes.incrementAndGet();return true;}) && writes.get()==0,"stopped staged PCM committed");
    }
    private static void cancelDuringCommit() throws Exception {
        AtomicBoolean stop=new AtomicBoolean();AtomicInteger writes=new AtomicInteger();
        boolean complete=twoBlocks().commit(stop::get,b->{writes.incrementAndGet();stop.set(true);return true;});
        check(!complete && writes.get()==1,"later staged blocks escaped stop");
    }
    private static void sinkRejects() throws Exception {
        AtomicInteger attempts=new AtomicInteger(),writes=new AtomicInteger();
        EdgeAudio.Pcm pcm=EdgeRetry.run(()->{attempts.incrementAndGet();return twoBlocks();},()->false,new Network(),EdgeRetry.NO_OBSERVER);
        check(!pcm.commit(()->false,b->{writes.incrementAndGet();return false;}) && attempts.get()==1 && writes.get()==1,"sink rejection retried or replayed PCM");
    }
    private static void noSecondCommit() throws Exception {
        EdgeAudio.Pcm pcm=twoBlocks();AtomicInteger writes=new AtomicInteger();pcm.commit(()->false,b->{writes.incrementAndGet();return true;});
        try {pcm.commit(()->false,b->{writes.incrementAndGet();return true;});throw new AssertionError("second PCM commit accepted");} catch(IllegalStateException expected) { }
        check(writes.get()==2,"PCM replayed after commit");
    }
    private static void retryAfter() throws Exception {
        Network network=new Network();AtomicInteger attempts=new AtomicInteger();
        float[] result=EdgeRetry.run(()->{
            int n=attempts.incrementAndGet();
            if(n==1) throw new EdgeRetry.TransientException("HTTP 429",60001,null);
            check(network.now>=60001,"retried before server Retry-After");return COMPLETE;
        },()->false,network,EdgeRetry.NO_OBSERVER);
        check(result==COMPLETE && attempts.get()==2 && network.delays.size()>=5,"server delay abandoned request or exceeded wait cap");
    }
    private static void cancelRetryAfter() throws Exception {
        Network network=new Network();AtomicBoolean stop=new AtomicBoolean();AtomicInteger attempts=new AtomicInteger();network.wait=count->{if(count==2) stop.set(true);};
        cancelled(()->EdgeRetry.run(()->{attempts.incrementAndGet();throw new EdgeRetry.TransientException("HTTP 503",3600000,null);},stop::get,network,EdgeRetry.NO_OBSERVER));
        check(attempts.get()==1 && network.delays.size()==2,"server wait ignored stop");
    }
    private static void defaultWaitCancellation() throws Exception {
        AtomicBoolean stop=new AtomicBoolean();CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        Thread worker=new Thread(()->{
            try {EdgeRetry.DEFAULT_NETWORK.await(15000,stop::get);} catch(Throwable e) {error.set(e);} finally {done.countDown();}
        },"edge-retry-default-wait");worker.setDaemon(true);worker.start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(worker.getState()!=Thread.State.TIMED_WAITING && worker.isAlive() && System.nanoTime()<deadline) Thread.yield();
            check(worker.getState()==Thread.State.TIMED_WAITING,"default wait did not enter sleep");
            stop.set(true);await(done);check(error.get()==null,"default wait failed: "+error.get());
        } finally {stop.set(true);worker.interrupt();}
    }
    private static void fatalFormat() throws Exception {
        byte[] unsupported=frames();unsupported[2]=0x60;Network network=new Network();AtomicInteger attempts=new AtomicInteger();
        try {EdgeRetry.run(()->{attempts.incrementAndGet();EdgeAudio.validateMp3(unsupported);return COMPLETE;},()->false,network,EdgeRetry.NO_OBSERVER);throw new AssertionError("unsupported audio completed");}
        catch(EdgeRetry.FatalException expected) { }
        check(attempts.get()==1 && network.delays.isEmpty(),"unsupported format retried indefinitely");
    }
}
