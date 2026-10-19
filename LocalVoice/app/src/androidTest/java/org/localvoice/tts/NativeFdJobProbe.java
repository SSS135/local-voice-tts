package org.localvoice.tts;

import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.system.Os;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Actual Android ModelStore jobs and native descriptors; transport is test-only memory input. */
public final class NativeFdJobProbe {
    private static final int FILES=20,BYTES=1024*1024,GATE_FILE=8;
    private static final byte[] PAYLOAD=new byte[BYTES];
    private static final AtomicReference<Trial> ACTIVE=new AtomicReference<>();
    private static final class Trial {
        final String kind;final CountDownLatch gate=new CountDownLatch(1),release=new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicIntegerArray requests=new java.util.concurrent.atomic.AtomicIntegerArray(FILES);volatile boolean recovered;
        final String modelId;Trial(String kind){this.kind=kind;modelId="patch-fd-"+kind+"-"+System.nanoTime();}
        boolean publicationGate(){return kind.equals("success")||kind.equals("writable")||kind.equals("replacement");}
    }
    private static void require(boolean okay,String message){if(!okay)throw new AssertionError(message);}
    private static String hash()throws Exception {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(PAYLOAD);StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
    private static void transport() {
        URL.setURLStreamHandlerFactory(protocol->!protocol.equals("https")?null:new URLStreamHandler(){
            @Override protected URLConnection openConnection(URL url)throws IOException {
                if(!url.getHost().equals("fixture.invalid"))throw new IOException("Unexpected external URL in descriptor fixture");
                Trial trial=ACTIVE.get();if(trial==null)throw new IOException("No active descriptor fixture");
                int index=Integer.parseInt(url.getPath().substring(1));
                trial.requests.incrementAndGet(index);
                return new HttpURLConnection(url){
                    volatile InputStream current;
                    @Override public void connect(){}@Override public boolean usingProxy(){return false;}
                    @Override public void disconnect(){try{if(current!=null)current.close();}catch(IOException ignored){}}
                    @Override public int getResponseCode(){return 200;}@Override public long getContentLengthLong(){return BYTES;}
                    @Override public InputStream getInputStream(){
                        if(trial.recovered)return current=new ByteArrayInputStream(PAYLOAD);
                        if(trial.publicationGate()&&index==FILES-1)return current=new ByteArrayInputStream(PAYLOAD){
                            boolean gated;
                            @Override public synchronized int read(byte[] target,int offset,int length){
                                if(!gated){gated=true;trial.gate.countDown();try{if(!trial.release.await(15,TimeUnit.SECONDS))throw new AssertionError("Success observation gate timeout");}catch(InterruptedException error){throw new AssertionError(error);}}
                                return super.read(target,offset,length);
                            }
                        };
                        if(index!=GATE_FILE||trial.publicationGate())return current=new ByteArrayInputStream(PAYLOAD);
                        return current=new InputStream(){
                            volatile boolean closed;
                            @Override public int read()throws IOException {return read(new byte[1],0,1);}
                            @Override public int read(byte[] target,int offset,int length)throws IOException {
                                trial.gate.countDown();try{if(!trial.release.await(15,TimeUnit.SECONDS))throw new IOException("Fixture release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Fixture interrupted");}
                                throw new IOException(closed?"Controlled cancelled input":"Controlled input failure");
                            }
                            @Override public void close(){closed=true;trial.release.countDown();}
                        };
                    }
                };
            }
        });
    }
    private static int[] descriptors(String modelId)throws IOException {
        String[] names=new File("/proc/self/fd").list();if(names==null)throw new IOException("Cannot inspect own native descriptors");int readOnly=0,writers=0;
        for(String name:names)try {
            String path=Os.readlink("/proc/self/fd/"+name);if(!path.contains("/models/"+modelId+"-"))continue;
            String flags="";try(BufferedReader reader=new BufferedReader(new FileReader("/proc/self/fdinfo/"+name))){for(String line;(line=reader.readLine())!=null;)if(line.startsWith("flags:")){flags=line.substring(6).trim();break;}}
            if((Long.parseLong(flags,8)&3)==0)readOnly++;else writers++;
        }catch(Exception racedClose){/* A descriptor can close between the native snapshots. */}
        return new int[]{names.length,readOnly,writers};
    }
    private interface CheckedAction {void run()throws Exception;}
    private static String fileHash(File file)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] bytes=new byte[65536];for(int n;(n=in.read(bytes))!=-1;)digest.update(bytes,0,n);}
        StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();
    }
    private static JSONObject sealedWriteDenied(File file)throws Exception {
        require(Os.getuid()>=10000,"Write-denial proof must run as ordinary app UID");
        android.system.StructStat before=Os.lstat(file.getAbsolutePath());require((before.st_mode&0777)==0400,"Fresh stored file is not0400");
        String digest=fileHash(file);boolean denied=false;
        try{java.io.FileDescriptor fd=Os.open(file.getAbsolutePath(),android.system.OsConstants.O_WRONLY|android.system.OsConstants.O_CLOEXEC,0);Os.close(fd);}
        catch(android.system.ErrnoException error){require(error.errno==android.system.OsConstants.EACCES,"Unexpected write-open errno "+error.errno);denied=true;}
        require(denied,"Ordinary same-size write-open was allowed");require(fileHash(file).equals(digest),"Write-denial attempt changed payload");
        return new JSONObject().put("appUid",Os.getuid()).put("modeOctal","0400").put("ordinaryWriteOpenErrno","EACCES").put("sha256Before",digest).put("sha256After",fileHash(file)).put("bytesUnchanged",true);
    }
    private static JSONObject repairReadOnly(ModelStore store,Catalog.Model model,File staging,Trial trial,String expected)throws Exception {
        java.lang.reflect.Field worker=ModelStore.class.getDeclaredField("WORK");worker.setAccessible(true);
        ((ExecutorService)worker.get(null)).submit(()->{}).get(20,TimeUnit.SECONDS);
        File damaged=ModelFiles.child(staging,"file-00.bin");JSONObject before=rawStamp(damaged);long modified=damaged.lastModified();
        require((Os.lstat(damaged.getAbsolutePath()).st_mode&0777)==0400,"Interrupted staged file not sealed");
        Os.chmod(damaged.getAbsolutePath(),0600);try(RandomAccessFile file=new RandomAccessFile(damaged,"rw")){file.seek(0);file.write(0x33);file.getFD().sync();}
        require(damaged.setLastModified(modified),"Cannot restore fixture millisecond mtime");Os.chmod(damaged.getAbsolutePath(),0400);JSONObject corrupted=rawStamp(damaged);
        require(damaged.length()==BYTES&&damaged.lastModified()==modified&&!fileHash(damaged).equals(expected),"Corruption fixture invalid");
        trial.recovered=true;store.download(model.id);long deadline=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<deadline){ModelStore.Progress progress=store.progress(model.id);if(!progress.running&&progress.state.equals("Installed"))break;if(!progress.running&&progress.state.contains("failed"))throw new AssertionError("Read-only repair failed: "+progress.detail);Thread.sleep(5);}
        require(store.isInstalled(model.id),"Read-only repair did not install");((ExecutorService)worker.get(null)).submit(()->{}).get(20,TimeUnit.SECONDS);
        require(trial.requests.get(0)==2,"Damaged read-only file was not re-fetched once");for(int i=1;i<GATE_FILE;i++)require(trial.requests.get(i)==1,"Good sealed staged file was re-fetched");
        require(fileHash(new File(store.modelDirectory(model.id),"file-00.bin")).equals(expected),"Read-only repair output pin mismatch");
        int[] descriptors=descriptors(model.id);require(descriptors[1]==0&&descriptors[2]==0,"Read-only retry leaked descriptors");
        return new JSONObject().put("sameSizeCorruption",true).put("restoredMtimeUnit","milliseconds (raw nanoseconds captured separately)").put("before",before).put("corrupted",corrupted).put("damagedFileRequests",trial.requests.get(0)).put("goodFirstSevenFilesReused",true).put("installed",true).put("ownedReadOnlyAfter",descriptors[1]).put("ownedWritersAfter",descriptors[2]);
    }
    private static JSONObject rawStamp(File file)throws Exception {
        android.system.StructStat stat=Os.lstat(file.getAbsolutePath());ModelFiles.Stamp stamp=AndroidFileStamp.read(file);
        JSONObject value=new JSONObject().put("bytes",stat.st_size).put("inode",stat.st_ino).put("device",stat.st_dev).put("modeOctal",Integer.toOctalString(stat.st_mode&0777)).put("regular",stamp.regular).put("strong",stamp.strong).put("precise",stamp.precise);
        if(android.os.Build.VERSION.SDK_INT>=27)value.put("mtimeSeconds",stat.st_mtim.tv_sec).put("mtimeNanos",stat.st_mtim.tv_nsec).put("ctimeSeconds",stat.st_ctim.tv_sec).put("ctimeNanos",stat.st_ctim.tv_nsec);
        return value;
    }
    private static void rejects(CheckedAction action,String message)throws Exception {
        boolean rejected=false;try{action.run();}catch(IOException expected){rejected=true;}
        require(rejected,message);
    }
    private static JSONObject symlinks(Context target)throws Exception {
        File root=new File(target.getNoBackupFilesDir(),"patch-symlink-"+System.nanoTime());
        File directory=new File(root,"real"),file=new File(directory,"payload.bin");
        File fileLink=new File(root,"file-link"),directoryLink=new File(root,"directory-link");
        require(directory.mkdirs(),"Cannot create private symlink fixture");
        try {
            try(FileOutputStream out=new FileOutputStream(file)){out.write(new byte[]{1,2,3});out.getFD().sync();}
            Os.symlink(file.getAbsolutePath(),fileLink.getAbsolutePath());
            Os.symlink(directory.getAbsolutePath(),directoryLink.getAbsolutePath());
            rejects(()->ModelFiles.child(root,"file-link"),"Lexical file symlink accepted");
            rejects(()->ModelFiles.child(root,"directory-link/payload.bin"),"Lexical ancestor symlink accepted");
            rejects(()->ModelFiles.prepareStaging(root,directoryLink),"Staging symlink leaf accepted");
            rejects(()->ModelFiles.prepareStaging(root,new File(directoryLink,"stage")),"Staging symlink ancestor accepted");
            File stage=new File(root,"new-stage");
            require(ModelFiles.prepareStaging(root,stage),"New atomic staging leaf not recognized");
            require(!ModelFiles.prepareStaging(root,stage),"Existing staging leaf treated as new");
            require(stage.delete(),"Cannot remove private staging fixture");
            rejects(()->{try(ModelFiles.HeldFile held=AndroidFileStamp.open(fileLink)){if(held==null)throw new AssertionError("FD exhaustion in symlink fixture");}},"O_NOFOLLOW accepted file symlink");
            try(ModelFiles.HeldFile held=AndroidFileStamp.open(file)){
                require(held!=null&&held.stamp().regular&&held.input().read()==1,"Real native file descriptor failed");
            }
            return new JSONObject().put("realAndroidOsSymlink",true).put("regularFileStamp",rawStamp(file)).put("lexicalFileRejected",true)
                .put("lexicalAncestorRejected",true).put("nativeNoFollowRejected",true).put("regularFileOpenedAndClosed",true)
                .put("stagingLeafSymlinkRejected",true).put("stagingAncestorSymlinkRejected",true).put("newStagingTrueThenExistingFalse",true);
        }finally {
            // Delete only the exact private fixture objects; never follow either link.
            if(fileLink.exists())require(fileLink.delete(),"Cannot unlink private file symlink");
            if(directoryLink.exists())require(directoryLink.delete(),"Cannot unlink private directory symlink");
            require(!file.exists()||file.delete(),"Cannot remove private fixture payload");
            require(!directory.exists()||directory.delete(),"Cannot remove private fixture directory");
            require(!root.exists()||root.delete(),"Cannot remove private fixture root");
        }
    }
    public static JSONObject cleanupDiagnostics(Context target)throws Exception {
        File root=new File(target.getNoBackupFilesDir(),"models").getCanonicalFile();JSONArray removed=new JSONArray();
        File[] directories=root.listFiles();require(directories!=null,"Cannot inspect private fixture root");
        for(File directory:directories)if(directory.getName().matches("patch-fd-(success|cancel|failure|writable|replacement)-[0-9]+-fd1(?:\\.download)?")) {
            File owned=ModelFiles.child(root,directory.getName());require(owned.isDirectory()&&owned.getCanonicalFile().getParentFile().equals(root),"Unexpected FD fixture path");
            JSONArray stamps=new JSONArray();File[] files=owned.listFiles();require(files!=null,"Cannot inspect FD fixture");
            for(File file:files){require(file.getName().matches("file-[0-9]{2}\\.bin|installed\\.manifest"),"Unexpected FD fixture member");File pinned=ModelFiles.child(owned,file.getName());require(pinned.isFile(),"Unexpected FD fixture object");if(file.getName().equals("file-00.bin")||file.getName().equals("file-19.bin"))stamps.put(new JSONObject().put("file",file.getName()).put("stat",rawStamp(pinned)));}
            ModelFiles.remove(owned);require(!owned.exists(),"FD fixture cleanup failed");removed.put(new JSONObject().put("fixture",directory.getName()).put("completedFileStamps",stamps));
        }
        return new JSONObject().put("removedOnlyStrictNamedPrivateFixtures",removed).put("realModelFilesTouched",false);
    }
    public static JSONObject run(Instrumentation instrumentation,Context target)throws Exception {
        return run(instrumentation,target,false);
    }
    public static JSONObject run(Instrumentation instrumentation,Context target,boolean coarseFallbackExpected)throws Exception {
        JSONObject symlinkResult=symlinks(target);
        Arrays.fill(PAYLOAD,(byte)0x5a);String expected=hash();EdgeCatalogRepository.get(target);transport();
        List<Catalog.Model> original=Catalog.MODELS;JSONArray rows=new JSONArray();
        try {
            for(String kind:coarseFallbackExpected?new String[]{"success","cancel","failure"}:new String[]{"success","cancel","failure","writable","replacement"}) {
                Trial trial=new Trial(kind);List<Catalog.ModelFile> files=new ArrayList<>();
                for(int i=0;i<FILES;i++)files.add(new Catalog.ModelFile(String.format(Locale.ROOT,"file-%02d.bin",i),"https://fixture.invalid/"+i,BYTES,expected));
                Catalog.Model model=new Catalog.Model(trial.modelId,"Native FD fixture","fixture","fd1",false,true,Collections.emptyList(),Collections.emptyList(),files,Collections.emptyMap(),"test","","","","");
                List<Catalog.Model> catalog=new ArrayList<>(original);catalog.add(model);Catalog.MODELS=Collections.unmodifiableList(catalog);ACTIVE.set(trial);
                ModelStore store=new ModelStore(target);File installed=store.modelDirectory(model.id),staging=new File(installed.getPath()+".download");
                require(!installed.exists()&&!staging.exists(),"Descriptor fixture not fresh");Thread.sleep(250);
                int before=descriptors(model.id)[0],peakRead=0,peakWriter=0,peakTotal=before;boolean acted=false;StorageMetrics.start();store.download(model.id);
                long deadline=SystemClock.elapsedRealtime()+30000;final long[] cancelNanos={-1};JSONObject retainedStamp=null,sealEvidence=null,mutationEvidence=null,gateProgress=null;int stableRetained=-1;
                while(SystemClock.elapsedRealtime()<deadline) {
                    int[] sample=descriptors(model.id);peakTotal=Math.max(peakTotal,sample[0]);peakRead=Math.max(peakRead,sample[1]);peakWriter=Math.max(peakWriter,sample[2]);
                    // The final fallback SHA can temporarily add one ordinary reader
                    // to the sixteen held certificates. Observe certificates alone at
                    // the blocked last-input checkpoint below.
                    require(sample[1]<=17,"More than16 held certificates plus one fallback SHA reader");
                    if(retainedStamp==null&&new File(staging,"file-00.bin").isFile())retainedStamp=rawStamp(new File(staging,"file-00.bin"));
                    if(trial.publicationGate()&&!acted&&trial.gate.getCount()==0){
                        stableRetained=sample[1];if(!coarseFallbackExpected)require(stableRetained==16,"Stable last-input retained descriptor cap not16: "+stableRetained);
                        File first=new File(staging,"file-00.bin");retainedStamp=rawStamp(first);
                        if(!coarseFallbackExpected)sealEvidence=sealedWriteDenied(first);
                        if(kind.equals("writable")){Os.chmod(first.getAbsolutePath(),0600);mutationEvidence=new JSONObject().put("kind","made writable after hash").put("after",rawStamp(first));}
                        if(kind.equals("replacement")){long oldInode=Os.lstat(first.getAbsolutePath()).st_ino;require(first.delete(),"Cannot replace owned fixture inode");try(FileOutputStream out=new FileOutputStream(first)){out.write(PAYLOAD);out.getFD().sync();}Os.chmod(first.getAbsolutePath(),0400);long replacement=Os.lstat(first.getAbsolutePath()).st_ino;require(replacement!=oldInode,"Replacement reused live held inode");mutationEvidence=new JSONObject().put("kind","replaced path after hash").put("heldInode",oldInode).put("replacementInode",replacement).put("after",rawStamp(first));}
                        trial.release.countDown();acted=true;
                    }
                    if(!trial.publicationGate()&&!acted&&trial.gate.getCount()==0){
                        require(coarseFallbackExpected||sample[1]>0,"No live Android retained descriptor before fault");
                        ModelStore.Progress held=store.progress(model.id);
                        require(held.state.equals("Downloading and checking")&&held.phaseTotal==model.bytes()&&held.phaseBytes>=0&&held.phaseBytes<=model.bytes(),"Later stalled body left stale per-file phase: "+held.state);
                        Thread.sleep(250);ModelStore.Progress later=store.progress(model.id);
                        require(later.state.equals(held.state)&&later.phaseTotal==held.phaseTotal&&later.phaseBytes>=held.phaseBytes,"Stalled body phase regressed");
                        gateProgress=new JSONObject().put("state",later.state).put("globalBytes",later.phaseBytes).put("globalTotal",later.phaseTotal).put("firstFileSealedBeforeLaterBodyStall",sample[1]>0).put("noStalePerFileVerifying",true);
                        if(kind.equals("cancel"))instrumentation.runOnMainSync(()->{long began=System.nanoTime();store.cancel(model.id);cancelNanos[0]=System.nanoTime()-began;});else trial.release.countDown();acted=true;
                    }
                    ModelStore.Progress progress=store.progress(model.id);
                    if(!progress.running&&(progress.state.equals("Installed")||progress.state.equals("Cancelled")||progress.state.toLowerCase(Locale.ROOT).contains("failed")))break;
                    Thread.sleep(1);
                }
                ModelStore.Progress terminal=store.progress(model.id);require(!terminal.running,"Descriptor job timeout");
                if(kind.equals("success")){require(terminal.state.equals("Installed")&&store.isInstalled(model.id),"Success fixture not installed");require(coarseFallbackExpected||stableRetained==16,"Did not exercise actual16-descriptor cap: stable="+stableRetained+" peak="+peakRead+" metrics="+StorageMetrics.json()+" stamp="+retainedStamp);}
                else {require(acted&&!store.isInstalled(model.id)&&!new File(staging,"installed.manifest").exists(),"Fault published a model");require(terminal.state.equals(kind.equals("cancel")?"Cancelled":"Download failed"),"Unexpected fault state "+terminal.state);}
                int[] after=descriptors(model.id);for(int i=0;i<200&&after[1]+after[2]>0;i++){Thread.sleep(10);after=descriptors(model.id);}
                require(after[1]==0&&after[2]==0,"Model descriptors leaked after terminal state");
                require(after[0]<=before+4,"Unexplained native descriptor growth");
                JSONObject metrics=new JSONObject(StorageMetrics.finish());
                if(coarseFallbackExpected)require(metrics.getLong("freshVerificationProofs")==0&&metrics.getLong("freshVerificationFallbacks")>0,"Expected actual coarse-timestamp fallback branch");
                else require(metrics.getLong("freshVerificationProofs")>0,"Native proof fast path was never exercised");
                if(kind.equals("success")&&!coarseFallbackExpected)require(metrics.getLong("freshVerificationProofs")==16&&metrics.getLong("freshVerificationFallbacks")>=4,"Cap/fallback branch not exercised");
                if(kind.equals("cancel"))require(cancelNanos[0]>=0&&cancelNanos[0]<500000000L,"UI cancellation call blocked for500ms");
                JSONObject row=new JSONObject().put("kind",kind).put("modelId",model.id).put("files",FILES).put("bytesPerFile",BYTES).put("totalNativeBefore",before).put("peakNativeTotal",peakTotal).put("peakOwnedReadOnly",peakRead).put("peakReadOnlyIncludesOneFallbackHashReader",true).put("stableRetainedAtInputGate",stableRetained).put("peakOwnedWriters",peakWriter).put("totalNativeAfter",after[0]).put("ownedReadOnlyAfter",after[1]).put("ownedWritersAfter",after[2]).put("terminal",terminal.state).put("retainedFileStamp",retainedStamp).put("sealWriteDenial",sealEvidence).put("mutation",mutationEvidence).put("uiCancelCallNanos",cancelNanos[0]).put("storageMetrics",metrics).put("realAndroidOsDescriptors",true);
                if(gateProgress!=null)row.put("stalledBodyProgress",gateProgress);
                if(kind.equals("failure")&&!coarseFallbackExpected)row.put("readOnlyRetry",repairReadOnly(store,model,staging,trial,expected));rows.put(row);
                store.remove(model.id,false);long cleanup=SystemClock.elapsedRealtime()+10000;while((installed.exists()||staging.exists())&&SystemClock.elapsedRealtime()<cleanup)Thread.sleep(10);require(!installed.exists()&&!staging.exists(),"Descriptor fixture cleanup incomplete");
                ACTIVE.set(null);
            }
        }finally{Trial trial=ACTIVE.getAndSet(null);if(trial!=null)trial.release.countDown();Catalog.MODELS=original;}
        return new JSONObject().put("symlinks",symlinkResult).put("trials",rows).put("transport","In-memory pinned input; no HTTP/DNS connection").put("retainedNativeReadLimit",16).put("writerFdCountSeparate",true).put("totalNativeAllowance",4).put("coarseFallbackExpected",coarseFallbackExpected).put("retainedCapExercised",!coarseFallbackExpected);
    }
}
