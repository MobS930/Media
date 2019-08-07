package fm.player.downloads.spacesaver;

import android.content.Context;
import android.os.StatFs;
import android.support.annotation.NonNull;

import java.io.File;

import de.greenrobot.event.EventBus;
import fm.player.downloads.DownloadSettings;
import fm.player.downloads.DownloadUtils;
import fm.player.eventsbus.Events;
import fm.player.utils.Alog;

/**
 * Created by Marian_Vandzura on 21.3.2017.
 */
public class SpaceSaver {

    private static final String TAG = "SpaceSaver.AudioCompressor";

    private static final float COMPRESSION_OFF = -1f;
    private static final float COMPRESSION_LITTLE = 0.4f;
    private static final float COMPRESSION_LOT = -0.1f;

    public SpaceSaver() {
        // default
    }

    /**
     * compress file
     *
     * @param filePath path of file to compress
     */
    public void compressFile(final Context context, final String episodeId, final String filePath, boolean test) throws Exception {

        int savedCompressionValue = DownloadSettings.DownloadCompression.QUALITY_0_4_LITTLE;

        final float compressionValue = getPreferredAudioCompressionValue(context, savedCompressionValue);

        if (compressionValue > COMPRESSION_OFF) {

            File file = new File(filePath);
            if (file.exists() && file.length() > 0) {

                AudioCompressor audioCompressor = new AudioCompressor();
                Alog.addLogMessage(TAG, "compress: with quality: " + compressionValue + "; file: " + filePath);

                //check if compression in progress because of doze mode it is sometimes triggered multiple times
                if (audioCompressor.compressionDoneOrInProgress(context, filePath, savedCompressionValue)) {
                    Alog.v(TAG, "compressFile: ALREADY IN PROGRESS: " + filePath);
                    return;
                }

                try {
                    String downloadsPath = DownloadUtils.prepareDownloadsFolder(context);
                    long availableBytes = getAvailableBytesInFileSystemAtGivenRoot(new File(downloadsPath));
                } catch (Exception e) {
                    Alog.e(TAG, "compressFile: getAvailableBytesInFileSystemAtGivenRoot exception", e, true);
                }
                Alog.saveLogs(context);

                File compressedFile = audioCompressor.compress(context, filePath, compressionValue, savedCompressionValue);
                if (compressedFile != null) {
                    final long uncompressedAudioSize = new File(filePath).length();
                    final long compressedAudioSize = compressedFile.length();
                    Alog.addLogMessage(TAG, "compressFile: compressed file, compressedAudioSize: " + compressedAudioSize + " uncompressedAudioSize: " + uncompressedAudioSize);
                    Alog.saveLogs(context);
                    //todo majovv
                    if (compressedAudioSize > 0) {
//                    if (compressedAudioSize > 0 && compressedAudioSize < uncompressedAudioSize) {
                        EventBus.getDefault().post(new Events.EpisodeCompressed(episodeId, compressedFile.getPath()));
                        updateEpisode(context, episodeId, filePath,
                                compressedFile.getAbsolutePath(), uncompressedAudioSize, compressedAudioSize, savedCompressionValue);
                    } else {
                        updateEpisodeFailed(context, episodeId, savedCompressionValue);
                        //delete compressed file
                        boolean deleted = compressedFile.delete();

                        Alog.addLogMessage(TAG, "compressFile: delete compressed file success: " + deleted);

                    }

                } else {
                    Alog.addLogMessage(TAG, "compressFile: compressed file is NULL");
                }
            }
        } else {
            Alog.addLogMessage(TAG, "compressFile: COMPRESSION SETTING - OFF compression");
        }

        Alog.saveLogs(context);
    }

    /* get compression value based on settings */
    private float getPreferredAudioCompressionValue(Context context, int savedCompressionValue) {
        float compressionValue;
        switch (savedCompressionValue) {
            case DownloadSettings.DownloadCompression.OFF:
                compressionValue = COMPRESSION_OFF;
                break;
            case DownloadSettings.DownloadCompression.QUALITY_0_4_LITTLE:
                compressionValue = COMPRESSION_LITTLE;
                break;
            case DownloadSettings.DownloadCompression.QUALITY_0_0_LOT:
                compressionValue = COMPRESSION_LOT;
                break;
            case DownloadSettings.DownloadCompression.BITRATE_48:
                compressionValue = 48000;
                break;
            case DownloadSettings.DownloadCompression.BITRATE_64:
                compressionValue = 64000;
                break;
            case DownloadSettings.DownloadCompression.BITRATE_128:
                compressionValue = 128000;
                break;
            default:
                compressionValue = COMPRESSION_OFF;
                break;
        }
        return compressionValue;
    }

    private void updateEpisode(@NonNull Context context, @NonNull String episodeId, @NonNull String originalFilePath,
                               @NonNull String compressedFilePath, long originalSize, long compressedSize, int savedCompressionValue) {
//        // update episode
//        ContentValues cv = new ContentValues();
//        Alog.addLogMessage(TAG, "updateEpisode: set episode compressed path");
//        cv.put(EpisodesTable.COMPRESSED, true);
//        cv.put(EpisodesTable.COMPRESSION_VALUE, savedCompressionValue);
//        cv.put(EpisodesTable.ORIGINAL_FILE_SIZE, originalSize);
//        cv.put(EpisodesTable.LOCAL_URL, compressedFilePath);
//        cv.put(EpisodesTable.LOCAL_FILE_SIZE, compressedSize);
//
//        context.getContentResolver().update(ApiContract.Episodes.getEpisodesUri(), cv,
//                EpisodesTable.ID + " = ?", new String[]{episodeId});
//        context.getContentResolver().notifyChange(ApiContract.Episodes.getEpisodesUri(), null);
//        context.getContentResolver().notifyChange(ApiContract.Selections.getSelectionsUri(), null);
//
//        // delete uncompressed file
//        boolean deleted = new File(originalFilePath).delete();
//        Alog.addLogMessage(TAG, "updateEpisode: delete original file success: " + deleted);
//        Alog.saveLogs(context);
    }

    private void updateEpisodeFailed(@NonNull Context context, @NonNull String episodeId, int savedCompressionValue) {
//        // update episode
//        ContentValues cv = new ContentValues();
//        Alog.addLogMessage(TAG, "updateEpisodeFailed: set COMPRESSION_VALUE: " + savedCompressionValue);
//        cv.put(EpisodesTable.COMPRESSED, false);
//        cv.put(EpisodesTable.COMPRESSION_VALUE, savedCompressionValue);
//
//        context.getContentResolver().update(ApiContract.Episodes.getEpisodesUri(), cv,
//                EpisodesTable.ID + " = ?", new String[]{episodeId});
//        Alog.saveLogs(context);
    }

    private long getAvailableBytesInFileSystemAtGivenRoot(File root) {
        StatFs stat = new StatFs(root.getPath());
        // put a bit of margin (in case creating the file grows the system by a
        // few blocks)
        long availableBlocks = (long) stat.getAvailableBlocks() - 4;
        long size = stat.getBlockSize() * availableBlocks;
//        if (Constants.LOGV) {
//            Log.i(Constants.TAG, "available space (in bytes) in filesystem rooted at: " + root.getPath() + " is: " + size);
//        }
        return size;
    }
}
