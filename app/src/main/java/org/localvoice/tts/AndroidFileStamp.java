package org.localvoice.tts;

import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.system.ErrnoException;
import java.io.*;

final class AndroidFileStamp {
    static ModelFiles.Stamp read(File file)throws IOException {
        try {
            StorageMetrics.FILE_STATS.increment();StructStat s=Os.lstat(file.getAbsolutePath());
            return stamp(s);
        }catch(ErrnoException e){if(e.errno==OsConstants.ENOENT)throw new FileNotFoundException("Model file missing");throw new IOException("Cannot inspect model file",e);}
    }
    static ModelFiles.HeldFile open(File file)throws IOException {
        if(Build.VERSION.SDK_INT<27)return null;
        try {
            FileDescriptor fd=Os.open(file.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC,0);
            try {
              FileInputStream input=new FileInputStream(fd);
              return new ModelFiles.HeldFile(){
                private boolean closed;
                public InputStream input(){return input;}
                public boolean seal()throws IOException {
                    if(closed)throw new IOException("Model descriptor is closed");
                    try{Os.fchmod(fd,0400);return true;}catch(ErrnoException unavailable){return false;}
                }
                public ModelFiles.Stamp stamp()throws IOException {
                    try{StorageMetrics.FILE_STATS.increment();return AndroidFileStamp.stamp(Os.fstat(fd));}
                    catch(ErrnoException error){throw new IOException("Cannot inspect model descriptor",error);}
                }
                public void close()throws IOException {
                    if(closed)return;closed=true;IOException first=null;
                    try{input.close();}catch(IOException failure){first=failure;}
                    // Android's FileInputStream(FileDescriptor) borrows its descriptor.
                    try{Os.close(fd);}catch(ErrnoException failure){if(first==null)first=new IOException("Cannot release model descriptor",failure);}
                    if(first!=null)throw first;
                }
              };
            }catch(RuntimeException|Error failure){try{Os.close(fd);}catch(ErrnoException close){failure.addSuppressed(close);}throw failure;}
        }catch(ErrnoException error){if(error.errno==OsConstants.EMFILE||error.errno==OsConstants.ENFILE)return null;throw new IOException("Cannot open model descriptor",error);}
    }
    private static ModelFiles.Stamp stamp(StructStat s){
        if(Build.VERSION.SDK_INT>=27)return Api27.read(s);
        return new ModelFiles.Stamp(s.st_size,s.st_dev+":"+s.st_ino+":"+s.st_mtime+":"+s.st_ctime,OsConstants.S_ISREG(s.st_mode),false,false);
    }
    @android.annotation.TargetApi(27)
    private static final class Api27 {
        static ModelFiles.Stamp read(StructStat s){
            long now=System.currentTimeMillis();
            boolean regular=OsConstants.S_ISREG(s.st_mode);
            boolean owned=regular&&s.st_uid==Os.getuid()&&s.st_nlink==1&&ModelFiles.validTimestamp(s.st_ctim.tv_sec,s.st_ctim.tv_nsec,now)&&ModelFiles.validTimestamp(s.st_mtim.tv_sec,s.st_mtim.tv_nsec,now);
            return new ModelFiles.Stamp(s.st_size,s.st_dev+":"+s.st_ino+":"+s.st_mtim.tv_sec+":"+s.st_mtim.tv_nsec+":"+s.st_ctim.tv_sec+":"+s.st_ctim.tv_nsec,OsConstants.S_ISREG(s.st_mode),
                ModelFiles.preciseAndSettled(s.st_ctim.tv_sec,s.st_ctim.tv_nsec,now),
                owned&&ModelFiles.preciseFresh(s.st_ctim.tv_sec,s.st_ctim.tv_nsec,now)&&ModelFiles.preciseFresh(s.st_mtim.tv_sec,s.st_mtim.tv_nsec,now),owned,owned&&(s.st_mode&07777)==0400);
        }
    }
    private AndroidFileStamp(){}
}
