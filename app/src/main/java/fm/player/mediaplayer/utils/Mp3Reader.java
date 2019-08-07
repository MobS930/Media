package fm.player.mediaplayer.utils;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import java.io.FileInputStream;
import java.util.HashMap;

import fm.player.utils.Alog;
import fm.player.utils.Constants;

public class Mp3Reader {

    private static final String TAG = "Mp3Reader";

    public static MetaData getMetaData(String path) {
        Alog.v(TAG, "getMetaData path: " + path);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        String durS = null;
        String mimeType = null;
        try {

            if (path.startsWith("http") || path.startsWith("https")) {

                HashMap<String, String> headers = new HashMap<>();
                headers.put("User-Agent", Constants.USER_AGENT_2);
                // Defeat transparent gzip compression, since it doesn't allow us to
                // easily resume partial downloads.
                headers.put("Accept-Encoding", "identity");
                // Defeat connection reuse, since otherwise servers may continue
                // streaming large downloads after cancelled.
                headers.put("Connection", "close");

                retriever.setDataSource(path, headers);
            } else {
                try {
                    retriever.setDataSource(path);
                } catch (Exception ex) {
                    //empty headers should resolve IllegalArgumentException
                    //src: https://issuetracker.google.com/issues/36952379#comment3
                    try {
                        Alog.addLogMessage(TAG, "get file duration failed: re-try 1 with empty headers");
                        retriever.setDataSource(path, new HashMap<String, String>());
                    } catch (Exception ex2) {
                        //try different approach
                        //https://stackoverflow.com/a/22070054/4585659
                        Alog.addLogMessage(TAG, "get file duration failed: re-try 2 with FileInputStream");
                        FileInputStream inputStream = new FileInputStream(path);
                        retriever.setDataSource(inputStream.getFD());
                        inputStream.close();
                    }
                }
            }

            durS = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            Alog.v(TAG, "getMetaData val: " + (durS == null ? 0 : Integer.parseInt(durS)));
            mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);

        } catch (Exception e) {
            Alog.e(TAG, "get file duration failed, path: " + path, e);
        } finally {
            retriever.release();
        }

        int duration = durS == null ? 0 : Integer.parseInt(durS);

        if (duration <= 0 && Build.VERSION.SDK_INT >= 16) {
            MediaExtractor extractor = new MediaExtractor();

            try {
                MediaFormat format = null;
                int i;

                if (path.startsWith("http") || path.startsWith("https")) {
                    extractor.setDataSource(path, new HashMap<String, String>());
                } else {
                    try {
                        extractor.setDataSource(path);
                    } catch (Exception ex) {
                        //empty headers should resolve IllegalArgumentException
                        //src: https://issuetracker.google.com/issues/36952379#comment3
                        try {
                            Alog.addLogMessage(TAG, "get MediaFormat failed: re-try 1 with empty headers");
                            retriever.setDataSource(path, new HashMap<String, String>());
                        } catch (Exception ex2) {
                            //try different approach
                            //https://stackoverflow.com/a/22070054/4585659
                            Alog.addLogMessage(TAG, "get MediaFormat failed: re-try with FileInputStream");
                            FileInputStream inputStream = new FileInputStream(path);
                            retriever.setDataSource(inputStream.getFD());
                            inputStream.close();
                        }
                    }
                }

                int numTracks = extractor.getTrackCount();
                // find and select the first audio track present in the file.
                for (i = 0; i < numTracks; i++) {
                    format = extractor.getTrackFormat(i);
                    if (format.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                        extractor.selectTrack(i);
                        break;
                    }
                }

                if (format != null) {
                    duration = (int) (format.getLong(MediaFormat.KEY_DURATION) / 1000.f);
                }

                Alog.v(TAG, "run: SoundFile duration");
            } catch (Exception e) {
                Alog.e(TAG, "get MediaFormat failed, path: " + path, e);
                e.printStackTrace();
            } finally {
                extractor.release();
            }
        }

        return new MetaData(duration, mimeType);
    }

    public static MetaData getMetaData(Context context, Uri contentUri) {
        Alog.v(TAG, "getMetaData");
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        String durS = null;
        String mimeType = null;
        String fileSize = null;
        try {

            retriever.setDataSource(context, contentUri);

            durS = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            Alog.v(TAG, "getMetaData val: " + (durS == null ? 0 : Integer.parseInt(durS)));

            mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);

        } catch (Exception e) {
            Alog.e(TAG, "get file duration failed, path: " + contentUri, e);
        } finally {
            retriever.release();
        }

        int duration = durS == null ? 0 : Integer.parseInt(durS);

        if (duration <= 0 && Build.VERSION.SDK_INT >= 16) {
            MediaExtractor extractor = new MediaExtractor();
            try {


                MediaFormat format = null;
                int i;

                extractor.setDataSource(context, contentUri, new HashMap<String, String>());

                int numTracks = extractor.getTrackCount();
                // find and select the first audio track present in the file.
                for (i = 0; i < numTracks; i++) {
                    format = extractor.getTrackFormat(i);
                    if (format.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                        extractor.selectTrack(i);
                        break;
                    }
                }

                if (format != null) {
                    duration = (int) (format.getLong(MediaFormat.KEY_DURATION) / 1000.f);
                }


                Alog.v(TAG, "run: SoundFile duration");
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                extractor.release();
            }
        }

        return new MetaData(duration, mimeType);
    }


    public static class MetaData {

        public int duration;
        public String mimeType;

        public MetaData(int duration, String mimeType) {
            this.duration = duration;
            this.mimeType = mimeType;
        }

    }
}
