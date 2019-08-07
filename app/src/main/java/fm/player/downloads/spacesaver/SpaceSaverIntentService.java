package fm.player.downloads.spacesaver;

import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import fm.player.services.QueueJobIntentService;
import fm.player.utils.Alog;
import fm.player.utils.Constants;

/**
 * Created by Marian_Vandzura on 30.3.2017.
 */

public class SpaceSaverIntentService extends QueueJobIntentService {

    public static final int JOB_ID = Constants.JobIDs.SPACE_SAVER_INTENT_SERVICE_JOB_ID;

    private static final String TAG = "SpaceSaverIntentService.AudioCompressor";

    private static final String ARG_EPISODE_ID = "ARG_EPISODE_ID";
    private static final String ARG_EPISODE_PATH = "ARG_EPISODE_PATH";
    private static final String ARG_CHARGING_REQUIRED = "ARG_CHARGING_REQUIRED";

    private static final String ACTION_COMPRESS_EPISODE = "ACTION_COMPRESS_EPISODE";
    private static final String ACTION_COMPRESS_EPISODE_TEST = "ACTION_COMPRESS_EPISODE_TEST";
    private static final String ACTION_COMPRESS_ALL_EXISTING = "ACTION_COMPRESS_ALL_EXISTING";

    public static void enqueueWork(@NonNull Context context, @NonNull Intent work) {
        enqueueWork(context, SpaceSaverIntentService.class, JOB_ID, work);
    }

    public static Intent newTestIntentCompressEpisode(Context context, String episodeId, String episodePath) {
        Intent intent = new Intent(context, SpaceSaverIntentService.class);
        intent.setAction(ACTION_COMPRESS_EPISODE_TEST);
        intent.putExtra(ARG_EPISODE_ID, episodeId);
        intent.putExtra(ARG_EPISODE_PATH, episodePath);
        return intent;
    }

    public static Intent newIntentCompressEpisode(Context context, String episodeId, String episodePath,
                                                  boolean chargingRequired) {
        Intent intent = new Intent(context, SpaceSaverIntentService.class);
        intent.setAction(ACTION_COMPRESS_EPISODE);
        intent.putExtra(ARG_EPISODE_ID, episodeId);
        intent.putExtra(ARG_EPISODE_PATH, episodePath);
        intent.putExtra(ARG_CHARGING_REQUIRED, chargingRequired);
        return intent;
    }

    public static Intent newIntentCompressExisting(Context context) {
        Intent intent = new Intent(context, SpaceSaverIntentService.class);
        intent.setAction(ACTION_COMPRESS_ALL_EXISTING);
        return intent;
    }

    public SpaceSaverIntentService() {
        super(TAG);
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onHandleWork(@Nullable Intent intent) {
        super.onHandleWork(intent);

        final long start = System.currentTimeMillis();
        long end = -1;
        String action = intent != null ? intent.getAction() : null;
        Alog.addLogMessage(TAG, "compression START: episode: " + intent.getStringExtra(ARG_EPISODE_ID));
        if (ACTION_COMPRESS_EPISODE_TEST.equals(action)) {
            String episodeId = intent.getStringExtra(ARG_EPISODE_ID);
            String episodePath = intent.getStringExtra(ARG_EPISODE_PATH);
            boolean chargingRequired = intent.getBooleanExtra(ARG_CHARGING_REQUIRED, false);
            boolean isCharging = true;

            Alog.addLogMessage(TAG, "compress " + " episodeId: " + episodeId + ", path: " + episodePath
                    + " charging required: " + chargingRequired + " isCharging: " + isCharging);

            if (!chargingRequired || isCharging) {

                if (episodeId != null && episodePath != null) {
                    try {
                        new SpaceSaver().compressFile(getBaseContext(), episodeId, episodePath, true);
                        end = System.currentTimeMillis();
                    } catch (Exception e) {
                        Alog.e(TAG, "compress file failed for episode: " + episodeId + " path: " + episodePath, e, true);
                    }
                }
            }

            Alog.saveLogs(this);

        } else if (ACTION_COMPRESS_EPISODE.equals(action)) {
            String episodeId = intent.getStringExtra(ARG_EPISODE_ID);
            String episodePath = intent.getStringExtra(ARG_EPISODE_PATH);
            boolean chargingRequired = intent.getBooleanExtra(ARG_CHARGING_REQUIRED, false);
            boolean isCharging = true;

            Alog.addLogMessage(TAG, "compress " + " episodeId: " + episodeId + ", path: " + episodePath
                    + " charging required: " + chargingRequired + " isCharging: " + isCharging);

            if (!chargingRequired || isCharging) {
                if (episodeId != null && episodePath != null) {
                    try {
                        new SpaceSaver().compressFile(getBaseContext(), episodeId, episodePath, false);
                        end = System.currentTimeMillis();
                    } catch (Exception e) {
                        Alog.addLogMessage(TAG, "compress file failed for episode: " + episodeId + " path: " + episodePath
                                + ", error: " + e.getMessage());
                        Alog.e(TAG, "compress file failed for episode: " + episodeId + " path: " + episodePath, e, true);
                    }
                }
            }

            Alog.saveLogs(this);

        }

        if (end != -1) {
            long diff = end - start;
            int compressionTimeMinutes = diff > 0 ? (int) (diff / 1000 / 60) : -1;
            Alog.addLogMessage(TAG, "compression END: episode: " + intent.getStringExtra(ARG_EPISODE_ID)
                    + ", compression time: minutes: " + compressionTimeMinutes);
        }

    }
}
