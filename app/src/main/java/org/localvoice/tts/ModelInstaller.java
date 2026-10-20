package org.localvoice.tts;

import android.os.StatFs;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Staging, verification and atomic publication of pinned model files.
 * install/delete run only on ModelStore's single worker; verify also runs on the inference worker.
 */
final class ModelInstaller {
    private static final long RESERVE = 32L * 1024 * 1024;
    private static final String TRANSFER_PHASE = "Downloading and checking";
    private static final ModelFiles.VerificationCache VERIFIED = new ModelFiles.VerificationCache(AndroidFileStamp::read);
    private final ModelStore store;
    private final File root;

    ModelInstaller(ModelStore store, File root) {
        this.store = store;
        this.root = root;
    }

    static File directory(File root, Catalog.Model model, boolean staging) {
        return new File(root, model.id + "-" + model.revision + (staging ? ".download" : ""));
    }

    File directory(Catalog.Model model, boolean staging) {
        return directory(root, model, staging);
    }

    /** Receipt and file sizes only; no hashing. Every inspection updates the shared installed snapshot. */
    boolean inspectInstalled(Catalog.Model model) {
        boolean installed = inspect(model);
        ModelStore.recordInstalled(directory(model, false), installed);
        return installed;
    }

    private boolean inspect(Catalog.Model model) {
        File dir = directory(model, false);
        if (!ModelFiles.receiptMatches(dir, model.revision)) return false;
        try {
            for (Catalog.ModelFile file : model.files) {
                File local = ModelFiles.child(dir, file.path);
                ModelFiles.Stamp stamp = AndroidFileStamp.read(local);
                if (!stamp.regular || stamp.size != file.bytes) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Hashes (or reuses cached hashes of) every installed file; a mismatch invalidates the installation. */
    void verify(Catalog.Model model, BooleanSupplier cancelled) throws IOException {
        File dir = directory(model, false);
        for (Catalog.ModelFile file : model.files) {
            if (VERIFIED.verified(ModelFiles.child(dir, file.path), file.bytes, file.sha256, model.revision, cancelled, n -> {})) continue;
            new File(dir, "installed.manifest").delete();
            VERIFIED.forget(dir);
            ModelStore.recordInstalled(dir, false);
            throw new IOException("Model is damaged. Download it again in LocalVoice");
        }
    }

    /** Removes staging and, unless stagingOnly, the installed model. */
    void delete(Catalog.Model model, boolean stagingOnly) throws IOException {
        File staging = directory(model, true);
        ModelFiles.remove(staging);
        VERIFIED.forget(staging);
        if (stagingOnly) return;
        File installed = directory(model, false);
        ModelFiles.remove(installed);
        VERIFIED.forget(installed);
        ModelStore.recordInstalled(installed, false);
    }

    /** Deletes directories of model ids/revisions no longer in the catalog; the UI cannot reach them, so they would leak disk. */
    void removeOrphans() {
        Set<String> current = new HashSet<>();
        for (Catalog.Model model : Catalog.MODELS) {
            current.add(directory(model, false).getName());
            current.add(directory(model, true).getName());
        }
        File[] children = root.listFiles();
        if (children != null) for (File child : children) {
            if (current.contains(child.getName())) continue;
            try {
                ModelFiles.remove(child);
            } catch (IOException error) {
                android.util.Log.w("LocalVoiceDownload", "Cannot remove stale model " + child.getName(), error);
            }
        }
    }

    /** Verified staging files are reused after a retry or process restart. Reports its own terminal progress. */
    void install(Catalog.Model model, ModelStore.Job job) {
        long before = StorageMetrics.now();
        long completed = 0;
        File staging = directory(model, true);
        ModelFiles.FreshVerification fresh = new ModelFiles.FreshVerification(staging, model.revision, AndroidFileStamp::read, AndroidFileStamp::open);
        try {
            ModelStore.checkCancelled(job);
            if (reuseInstalled(model, job)) return;
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create private model storage");
            File damaged = directory(model, false);
            ModelStore.checkCancelled(job);
            if (damaged.isDirectory() && !staging.exists()) {
                // A rejected installation is never advertised. Rehash its files for explicit repair.
                File from = ModelFiles.child(root, damaged.getName());
                File to = ModelFiles.child(root, staging.getName());
                File receipt = ModelFiles.child(from, "installed.manifest");
                ModelStore.checkCancelled(job);
                if (receipt.exists() && !receipt.delete()) throw new IOException("Cannot invalidate damaged model receipt");
                VERIFIED.forget(damaged);
                ModelStore.checkCancelled(job);
                if (!from.renameTo(to)) throw new IOException("Cannot prepare damaged model for repair");
                ModelStore.recordInstalled(damaged, false);
            }
            boolean freshStaging = ModelFiles.prepareStaging(root, staging);
            ModelFiles.DownloadPaths paths = new ModelFiles.DownloadPaths(root, staging);
            ModelStore.checkCancelled(job);
            Set<String> reusable = new HashSet<>();
            long[] checked = {0};
            int filesDone = 0;
            if (!freshStaging) {
                ModelStore.phase(model, job, "Checking files", "", 0, 0, model.bytes(), 0, true);
                for (Catalog.ModelFile file : model.files) {
                    ModelStore.checkCancelled(job);
                    File local = ModelFiles.child(staging, file.path);
                    String name = new File(file.path).getName();
                    final int index = filesDone;
                    final long done = completed;
                    boolean valid = VERIFIED.verified(local, file.bytes, file.sha256, model.revision, () -> job.cancelled, n -> {
                        checked[0] += n;
                        ModelStore.phase(model, job, "Checking files", name, done, checked[0], model.bytes(), index, false);
                    });
                    if (valid) {
                        completed += file.bytes;
                        reusable.add(file.path);
                    }
                    filesDone++;
                    ModelStore.phase(model, job, "Checking files", name, completed, checked[0], model.bytes(), filesDone, false);
                }
            }
            DownloadFile.requireSpace(new StatFs(root.getPath()).getAvailableBytes(), model.bytes() - completed, RESERVE);
            ModelStore.phase(model, job, TRANSFER_PHASE, "", completed, completed, model.bytes(), 0, true);
            for (Catalog.ModelFile file : model.files) {
                ModelStore.checkCancelled(job);
                if (reusable.contains(file.path)) continue;
                File local = paths.plan(file.path);
                String name = new File(file.path).getName();
                final long base = completed;
                // Staging is never advertised. An interrupted file is fully rechecked and overwritten on retry.
                ModelDownloader.transfer(model, file, local, job, !freshStaging, paths,
                    count -> ModelStore.phase(model, job, TRANSFER_PHASE, name, base + count, base + count, model.bytes(), 0, false));
                ModelStore.checkCancelled(job);
                final long downloaded = completed + file.bytes;
                fresh.verifyFresh(local, file.bytes, file.sha256, () -> job.cancelled,
                    n -> ModelStore.phase(model, job, TRANSFER_PHASE, name, downloaded, downloaded, model.bytes(), 0, false));
                completed += file.bytes;
            }
            int finalFiles = 0;
            long accepted = 0;
            ModelStore.phase(model, job, "Verifying", "", completed, 0, model.bytes(), 0, true);
            for (Catalog.ModelFile file : model.files) {
                final int index = finalFiles;
                final long done = completed;
                final long base = accepted;
                final long[] hashed = {0};
                String name = new File(file.path).getName();
                ModelStore.checkCancelled(job);
                File local = paths.checked(file.path);
                boolean valid = fresh.accepts(local, file.bytes, file.sha256)
                    || VERIFIED.verified(local, file.bytes, file.sha256, model.revision, () -> job.cancelled, n -> {
                        hashed[0] += n;
                        ModelStore.phase(model, job, "Verifying", name, done, base + hashed[0], model.bytes(), index, false);
                    });
                if (!valid) throw new IOException("Model changed before activation. Retry the download");
                finalFiles++;
                // Phase completion counts bytes whose identities/checksums have been accepted, including cache hits.
                accepted += file.bytes;
                ModelStore.phase(model, job, "Verifying", name, completed, accepted, model.bytes(), finalFiles, false);
            }
            ModelStore.phase(model, job, "Finalizing", "", completed, model.bytes(), model.bytes(), model.files.size(), true);
            ModelStore.checkCancelled(job);
            // A later file's verification must not hide a change to any earlier verified file.
            for (Catalog.ModelFile file : model.files) {
                File local = paths.checked(file.path);
                if (!fresh.accepts(local, file.bytes, file.sha256)) VERIFIED.requireUnchanged(local, file.bytes, file.sha256, model.revision);
            }
            ModelStore.checkCancelled(job);
            fresh.close();
            ModelStore.checkCancelled(job);
            if (!publishInstallation(model, job, staging)) return;
            ModelStore.update(model, "Installed", "Ready for offline speech", completed, false);
            store.broadcastVoices();
        } catch (Exception error) {
            if (!job.cancelled) android.util.Log.w("LocalVoiceDownload", "Install failed model=" + model.id, error);
            ModelStore.update(model, job.cancelled ? "Cancelled" : "Download failed", job.cancelled ? "Verified files kept for retry" : ModelStore.safeError(error), completed, false);
        } finally {
            try {
                fresh.close();
            } catch (IOException ignored) {
                // All read-only closes were attempted; a pre-publication failure was already reported.
            }
            StorageMetrics.elapsed(StorageMetrics.INSTALL_NANOS, before);
            java.net.HttpURLConnection connection = job.connection;
            if (connection != null) connection.disconnect();
        }
    }

    private boolean reuseInstalled(Catalog.Model model, ModelStore.Job job) throws IOException {
        if (!inspectInstalled(model)) return false;
        try {
            store.verifyInstalled(model.id, () -> job.cancelled);
            synchronized (job) {
                ModelStore.checkCancelled(job);
                job.activated = true;
            }
            ModelStore.update(model, "Installed", "", model.bytes(), false);
            return true;
        } catch (InterruptedIOException cancelled) {
            throw cancelled;
        } catch (IOException damaged) {
            ModelStore.checkCancelled(job);
            return false;
        }
    }

    /** The worker alone owns payload writes; only the cancellation/rename decision needs the job monitor. */
    private boolean publishInstallation(Catalog.Model model, ModelStore.Job job, File staging) throws IOException {
        ModelStore.checkCancelled(job);
        ModelFiles.writeReceipt(staging, model.revision);
        ModelStore.checkCancelled(job);
        File target = directory(model, false);
        if (target.exists()) {
            // A valid existing model wins even if it appeared while this job was queued.
            if (reuseInstalled(model, job)) return false;
            ModelStore.checkCancelled(job);
            ModelFiles.remove(target, () -> job.cancelled);
        }
        ModelStore.checkCancelled(job);
        synchronized (job) {
            ModelStore.checkCancelled(job);
            ModelFiles.activate(staging, target);
            job.activated = true;
        }
        try {
            VERIFIED.relocated(staging, target);
        } catch (IOException cacheMiss) {
            VERIFIED.forget(staging);
            VERIFIED.forget(target);
        }
        ModelStore.recordInstalled(target, true);
        return true;
    }
}
