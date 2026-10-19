package org.localvoice.tts;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic HttpURLConnection boundary. All HTTPS stays inside this JVM; no sockets/TLS claim. */
final class FakeModelTransport {
    static final Map<String,byte[]> payloads=new ConcurrentHashMap<>();
    static final List<String> requests=Collections.synchronizedList(new ArrayList<>());
    static volatile Runnable beforeBody,beforeResponse;
    static volatile int responseCode=200;
    static void install(){URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){
        @Override protected URLConnection openConnection(URL url)throws IOException{
            if(!url.getHost().equals("example.invalid"))throw new IOException("Only fixture URLs are allowed");
            byte[] bytes=payloads.get(url.getPath());if(bytes==null)throw new IOException("No fixture response");
            return new HttpURLConnection(url){
                @Override public void connect(){}
                @Override public void disconnect(){}
                @Override public boolean usingProxy(){return false;}
                @Override public int getResponseCode(){requests.add(url.getPath());Runnable hook=beforeResponse;if(hook!=null)hook.run();return FakeModelTransport.responseCode;}
                @Override public long getContentLengthLong(){return bytes.length;}
                @Override public InputStream getInputStream(){Runnable hook=beforeBody;if(hook!=null)hook.run();return new ByteArrayInputStream(bytes);}
            };
        }
    }:null);}
    static void reset(byte[] bytes){requests.clear();beforeBody=null;beforeResponse=null;responseCode=200;payloads.clear();payloads.put("/first.bin",bytes);payloads.put("/nested/last.bin",bytes);}
}
