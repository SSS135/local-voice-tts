package org.localvoice.tts.backend;

import java.io.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** A single explicitly requested metadata fetch, with independent cancellation and no retry. */
public final class EdgeCatalogClient {
    private volatile Call call;
    private volatile boolean cancelled;
    private final String endpoint;
    private final long timeoutMillis;
    public EdgeCatalogClient() { this(null,20000); }
    /** Loopback constructor for isolated HTTP fixture tests; application uses the default. */
    public EdgeCatalogClient(String endpoint,long timeoutMillis) {
        if(endpoint!=null && !endpoint.matches("http://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}(/[^?#]*)?"))
            throw new IllegalArgumentException("Catalog test endpoint must be loopback");
        if(timeoutMillis<100 || timeoutMillis>20000) throw new IllegalArgumentException("Invalid catalog timeout");
        this.endpoint=endpoint;this.timeoutMillis=timeoutMillis;
    }
    public void cancel() { cancelled=true;Call current=call;if(current!=null) current.cancel(); }
    public String fetch() throws IOException {
        if(cancelled) throw new InterruptedIOException("Edge catalog refresh cancelled");
        Request request=EdgeBackend.headers(endpoint!=null?endpoint:EdgeBackend.url("/voices/list").removeAllQueryParameters("TrustedClientToken")
                .addQueryParameter("trustedclienttoken",EdgeProtocol.CLIENT_TOKEN).build().toString())
                .header("Accept","*/*").header("Sec-Fetch-Site","none").header("Sec-Fetch-Mode","cors")
                .header("Sec-Fetch-Dest","empty").build();
        call=EdgeBackend.CLIENT.newBuilder().callTimeout(timeoutMillis,TimeUnit.MILLISECONDS).build().newCall(request);
        if(cancelled) call.cancel();
        try(Response response=call.execute()) {
            if(response.code()!=200) throw new IOException("Edge catalog HTTP "+response.code());
            ResponseBody body=response.body();
            if(body==null || body.contentLength()>EdgeCatalogData.MAX_BYTES) throw new IOException("Invalid Edge catalog response size");
            try(InputStream input=body.byteStream()) { return EdgeCatalogData.read(input,EdgeCatalogData.MAX_BYTES,()->cancelled); }
        } finally { call=null; }
    }
}
