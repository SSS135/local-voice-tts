package org.localvoice.tts;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Matching local-source transfer/hash component only; excludes activation, Android and network latency. */
public final class StorageTransferProfile {
    public static void main(String[] args)throws Exception{
        File root=new File(args[0]),target=new File(args[3]);
        List<String> entries=Files.readAllLines(new File(args[1]).toPath(),StandardCharsets.UTF_8);
        if(target.exists())throw new IOException("Profile temporary target already exists");
        try{
            for(boolean stream:new boolean[]{false,true}){
                StorageMetrics.start();
                for(String entry:entries){
                    String[] item=entry.split("\t");File source=ModelFiles.child(root,item[0]);long size=Long.parseLong(item[1]);
                    try(InputStream input=new FileInputStream(source)){
                        if(stream)DownloadFile.copyAndHash(input,target,size,item[2],()->false,n->{},ModelFiles::portableStamp);
                        else{DownloadFile.copy(input,target,size,()->false,n->{});if(!ModelFiles.verified(target,size,item[2]))throw new IOException("Baseline copy mismatch");}
                    }
                }
                System.out.println("{\"scope\":\"host-JVM-transfer-hash-component-only\",\"model\":\""+args[2]+"\",\"workload\":\""+(stream?"stream-digest-with-write-identity-proof":"baseline-write-then-full-disk-hash")+"\",\"files\":"+entries.size()+",\"metrics\":"+StorageMetrics.finish()+"}");
            }
        }finally{StorageMetrics.finish();if(target.exists()&&!target.delete())throw new IOException("Cannot delete profile temporary data");}
    }
}
