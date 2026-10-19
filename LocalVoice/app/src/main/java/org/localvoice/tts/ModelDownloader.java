package org.localvoice.tts;

import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.function.LongConsumer;

/** HTTPS transfer of one pinned model file into staging. The only network access in model storage. */
final class ModelDownloader {
    private static final String TAG = "LocalVoiceDownload";
    private static final int MAX_REDIRECTS = 5;
    private static final int TIMEOUT_MS = 20000;

    private ModelDownloader() {}

    /** progress receives bytes written for this file; 0 is reported before connecting. */
    static void transfer(Catalog.Model model, Catalog.ModelFile file, File part, ModelStore.Job job, boolean mayReplaceIncomplete, ModelFiles.DownloadPaths paths, LongConsumer progress) throws IOException {
        progress.accept(0);
        long requestStarted = SystemClock.elapsedRealtime();
        Log.i(TAG, "connect model=" + model.id + " file=" + file.path);
        URL url = new URL(file.url);
        HttpURLConnection connection = null;
        try {
            for (int redirects = 0; ; redirects++) {
                ModelStore.checkCancelled(job);
                if (!url.getProtocol().equals("https") || url.getUserInfo() != null) throw new IOException("Unsafe download URL");
                connection = (HttpURLConnection) url.openConnection();
                synchronized (job) {
                    ModelStore.checkCancelled(job);
                    job.connection = connection;
                }
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setRequestProperty("Accept-Encoding", "identity");
                int code = connection.getResponseCode();
                Log.i(TAG, "response model=" + model.id + " status=" + code + " elapsedMs=" + (SystemClock.elapsedRealtime() - requestStarted));
                if (code >= 300 && code < 400) {
                    if (redirects >= MAX_REDIRECTS) throw new IOException("Too many download redirects");
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Invalid download redirect");
                    url = new URL(url, location);
                    connection.disconnect();
                    continue;
                }
                if (code != 200) throw new IOException("Download server returned HTTP " + code + ". Retry later");
                long length = connection.getContentLengthLong();
                if (length >= 0 && length != file.bytes) throw new IOException("Unexpected size for " + file.path);
                break;
            }
            try (InputStream input = connection.getInputStream()) {
                ModelStore.checkCancelled(job);
                paths.prepareWrite(file.path);
                if (part.exists()) {
                    if (!mayReplaceIncomplete) throw new IOException("Unexpected file in fresh download storage");
                    if (!Files.isRegularFile(part.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid incomplete model file");
                    if (!part.delete()) throw new IOException("Cannot replace incomplete model file");
                }
                DownloadFile.copyStored(input, part, file.bytes, () -> job.cancelled, progress);
            }
        } catch (IOException error) {
            Log.i(TAG, "failure model=" + model.id + " type=" + error.getClass().getSimpleName() + " elapsedMs=" + (SystemClock.elapsedRealtime() - requestStarted));
            throw error;
        } finally {
            if (connection != null) connection.disconnect();
            job.connection = null;
            if (job.cancelled) {
                try {
                    paths.checked(file.path).delete();
                } catch (IOException invalidPath) {
                    // Never clean up through a changed parent.
                }
            }
        }
    }
}
