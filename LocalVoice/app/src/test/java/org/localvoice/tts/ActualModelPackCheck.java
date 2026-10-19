package org.localvoice.tts;

import java.io.*;
import java.nio.file.Files;

/** Optional existing-pack check; no conversion, download, activation or Android runtime. */
public final class ActualModelPackCheck {
    public static void main(String[] args)throws Exception{
        Catalog.Model model=Catalog.findModel("shtorm-pocket-ru-fast");
        if(model==null||model.files.size()!=14)throw new AssertionError("Expected the reviewed fourteen-file package plan");
        File archive=new File(args[0]),parent=new File(args[1]);
        if(!archive.isFile())throw new FileNotFoundException("Supply the existing pinned model archive");
        DownloadFile.requireSpace(parent.getUsableSpace(),Math.addExact(model.bytes(),archive.length()),32L*1024*1024);
        File staging=Files.createTempDirectory(parent.toPath(),"actual-pack-").toFile();
        try{
            StorageMetrics.start();
            try(InputStream input=new FileInputStream(archive)){SecureModelPackImporter.importPack(input,staging,model,()->false,n->{});}
            for(Catalog.ModelFile file:model.files)if(!ModelFiles.verified(ModelFiles.child(staging,file.path),file.bytes,file.sha256))throw new AssertionError("Extracted runtime does not match its pinned checksum");
            File[] output=staging.listFiles();if(output==null||output.length!=14)throw new AssertionError("Only expected runtime files may remain");
            if(new File(staging,"installed.manifest").exists()||new File(staging,".import-archive.zip").exists())throw new AssertionError("Helper must not publish or retain spool");
            System.out.println("PASS actual pinned Shtorm archive: fourteen runtime files, exact ZIP/manifest/runtime hashes, complete CRC/ZIP structure, no activation or spool; "+StorageMetrics.finish());
        }finally{StorageMetrics.finish();ModelFiles.remove(staging);}
    }
}
