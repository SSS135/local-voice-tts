package org.localvoice.tts;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Host component profile of the exact cached-retry checksum workload; not an Android/UI benchmark. */
public final class StorageHashProfile {
    public static void main(String[] args) throws Exception {
        if(args.length<3||args.length>4) throw new IllegalArgumentException("dataset-root manifest.tsv model-label [cache]");
        boolean optimized=args.length==4&&args[3].equals("cache");
        ModelFiles.VerificationCache cache=new ModelFiles.VerificationCache(ModelFiles::portableStamp);
        File root=new File(args[0]);
        List<String> entries=Files.readAllLines(new File(args[1]).toPath(),StandardCharsets.UTF_8);
        long bytes=0;
        for(String entry:entries) bytes+=Long.parseLong(entry.split("\t")[1]);
        StorageMetrics.start();
        for(int pass=1;pass<=2;pass++) {
            long before=System.nanoTime();
            for(String entry:entries) {
                String[] item=entry.split("\t");
                File file=ModelFiles.child(root,item[0]);
                if(!(optimized?cache.verified(file,Long.parseLong(item[1]),item[2],"profile-revision",()->false,n->{}):ModelFiles.verified(file,Long.parseLong(item[1]),item[2])))
                    throw new AssertionError("Dataset does not match the pinned manifest: "+item[0]);
            }
            System.out.println("{\"scope\":\"host-JVM-component-only\",\"model\":\""+args[2]+"\",\"pass\":"+pass+",\"passNanos\":"+(System.nanoTime()-before)+",\"metrics\":"+StorageMetrics.json()+"}");
        }
        System.out.println("{\"scope\":\"host-JVM-component-only\",\"model\":\""+args[2]+"\",\"workload\":\""+(optimized?"identity-cache-two-validation-passes":"baseline-cached-retry-two-checksum-passes")+"\",\"filesPerPass\":"+entries.size()+",\"datasetBytes\":"+bytes+",\"metrics\":"+StorageMetrics.finish()+"}");
    }
}
