package com.uae4arm2026;

import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class BootstrapCueImportWorkflow {

    interface Callbacks {
        File getInternalCd0Dir();

        void deleteRecursive(File fileOrDir);

        void ensureDir(File dir);

        String lowerExt(String name);

        boolean isValidCdExtension(String name);

        String safeFilename(String name, String fallback);

        boolean importToFile(Uri uri, File dest);

        void logInfo(String message);

        void fixCueTrackFilenameCase(File cueDest);

        boolean cueHasMissingTracks(File cueDest);

        List<String> parseCueTrackFilenames(File cueDest);

        String normalizeCueTrackRelativePath(String rawTrack);

        Uri buildSiblingDocumentUriFromCue(Uri cueUri, String relTrack);

        boolean importCueTrackFromFilesystemSibling(Uri cueUri, String relTrack, File dest);

        boolean importCueTrackFromConfiguredCdroms(Uri cueUri, String relTrack, File dest);

        int importAllCueCompanionFilesFromFilesystemFolder(Uri cueUri, File cd0Dir);

        int importAllCueCompanionFilesFromConfiguredCdroms(Uri cueUri, File cd0Dir);

        void applyCdSelectionPath(String cdPathOrUri, String sourceName);
    }

    private BootstrapCueImportWorkflow() {
    }

    static void handleCueImport(BootstrapActivity activity,
                                List<Uri> uris,
                                Map<Uri, String> nameByUri,
                                Uri cueUri,
                                String cueLabel,
                                Callbacks callbacks) {
        File cd0Dir = callbacks.getInternalCd0Dir();
        callbacks.deleteRecursive(cd0Dir);
        callbacks.ensureDir(cd0Dir);

        File cueDest = null;
        for (Uri u : uris) {
            String name = nameByUri.get(u);
            if (name == null || name.trim().isEmpty()) name = "track.bin";
            String ext = callbacks.lowerExt(name);
            if (!callbacks.isValidCdExtension(name) && !"bin".equals(ext)) continue;

            File dest = new File(cd0Dir, callbacks.safeFilename(name, "track.bin"));
            if (!callbacks.importToFile(u, dest)) {
                callbacks.logInfo("Failed to import CD companion file: " + name);
                continue;
            }
            if (u.equals(cueUri)) cueDest = dest;
        }

        if (cueDest == null || !cueDest.exists() || cueDest.length() <= 0) {
            Toast.makeText(activity, "Failed to import CUE file", Toast.LENGTH_LONG).show();
            return;
        }

        callbacks.fixCueTrackFilenameCase(cueDest);

        if (callbacks.cueHasMissingTracks(cueDest)) {
            List<String> tracks = callbacks.parseCueTrackFilenames(cueDest);
            List<String> missing = new ArrayList<>();
            for (String track : tracks) {
                if (track == null || track.trim().isEmpty()) continue;
                if (!new File(cd0Dir, track).exists()) {
                    missing.add(track);
                }
            }

            if (!missing.isEmpty()) {
                importMissingCueTracksFromCueFolder(cueUri, cd0Dir, missing, callbacks);
                callbacks.fixCueTrackFilenameCase(cueDest);
                if (!callbacks.cueHasMissingTracks(cueDest)) {
                    callbacks.applyCdSelectionPath(cueDest.getAbsolutePath(), cueLabel);
                    Toast.makeText(activity, "CUE imported with sibling BIN tracks", Toast.LENGTH_SHORT).show();
                    return;
                }

                int fsBulkCopied = callbacks.importAllCueCompanionFilesFromFilesystemFolder(cueUri, cd0Dir);
                if (fsBulkCopied > 0) {
                    callbacks.fixCueTrackFilenameCase(cueDest);
                    if (!callbacks.cueHasMissingTracks(cueDest)) {
                        callbacks.applyCdSelectionPath(cueDest.getAbsolutePath(), cueLabel);
                        Toast.makeText(activity, "CUE imported with files from CUE folder", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }

                int bulkCopied = callbacks.importAllCueCompanionFilesFromConfiguredCdroms(cueUri, cd0Dir);
                if (bulkCopied > 0) {
                    callbacks.fixCueTrackFilenameCase(cueDest);
                    if (!callbacks.cueHasMissingTracks(cueDest)) {
                        callbacks.applyCdSelectionPath(cueDest.getAbsolutePath(), cueLabel);
                        Toast.makeText(activity, "CUE imported with files from CUE folder", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }

                Toast.makeText(activity, "CUE is missing BIN tracks. Keep CUE and BIN files together.", Toast.LENGTH_LONG).show();
                return;
            }
        }
        callbacks.applyCdSelectionPath(cueDest.getAbsolutePath(), cueLabel);
    }

    private static boolean importMissingCueTracksFromCueFolder(Uri cueUri,
                                                                File cd0Dir,
                                                                List<String> missingTracks,
                                                                Callbacks callbacks) {
        if (cueUri == null || cd0Dir == null || missingTracks == null || missingTracks.isEmpty()) return false;
        boolean copiedAny = false;
        for (String rawTrack : missingTracks) {
            try {
                String relTrack = callbacks.normalizeCueTrackRelativePath(rawTrack);
                if (relTrack == null || relTrack.isEmpty()) continue;

                Uri siblingUri = callbacks.buildSiblingDocumentUriFromCue(cueUri, relTrack);
                File dest = new File(cd0Dir, relTrack);
                callbacks.ensureDir(dest.getParentFile());
                boolean copied = false;
                if (siblingUri != null) {
                    copied = callbacks.importToFile(siblingUri, dest);
                }
                if (!copied) {
                    copied = callbacks.importCueTrackFromFilesystemSibling(cueUri, relTrack, dest);
                }
                if (!copied) {
                    copied = callbacks.importCueTrackFromConfiguredCdroms(cueUri, relTrack, dest);
                }
                if (copied) {
                    copiedAny = true;
                }
            } catch (Throwable t) {
                callbacks.logInfo("Skipping sibling CUE track import for '" + rawTrack + "': " + t);
            }
        }
        return copiedAny;
    }
}