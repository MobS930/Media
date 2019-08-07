package fm.player.downloads;

import android.content.Context;

import java.io.File;

/**
 * Created by mac on 25/11/2013.
 */
public class DownloadUtils {

    private static final String TAG = DownloadUtils.class.getSimpleName();
    private static final boolean DEBUG = false;


    /**
     * Prepare downloads folder. Create it if it does not exist, check whether storage is available etc..
     *
     * @param context
     * @return
     */
    public static String prepareDownloadsFolder(Context context) {
        File externalStorage = context.getExternalFilesDir(null);
        String downloadFolderPath = null;
        if (externalStorage != null) {
//            File dir = new File(externalStorage, "PlayerAudioTest");
//            if (!dir.exists()) {
//                dir.mkdirs();
//            }
//            downloadFolderPath = dir.getAbsolutePath();
            downloadFolderPath = externalStorage.getAbsolutePath();
        }
        return downloadFolderPath;
    }

}
