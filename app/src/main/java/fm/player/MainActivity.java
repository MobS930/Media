package fm.player;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnClick;
import de.greenrobot.event.EventBus;
import fm.player.downloads.DownloadUtils;
import fm.player.downloads.spacesaver.SpaceSaverIntentService;
import fm.player.eventsbus.Events;
import fm.player.playback.MediaPlayerWrapper;
import fm.player.playback.MediaPlayerWrapperListener;
import fm.player.playerandroidaudio.R;
import fm.player.utils.Alog;
import fm.player.utils.Utils;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private enum FileType {
        MP3, M4A, FLAC, OGG
    }

    private static final HashMap<FileType, Episode> LOCAL_EPISODES = new HashMap<>();
    private static final HashMap<FileType, Episode> REMOTE_EPISODES = new HashMap<>();
    private static final ArrayList<Episode> LOCAL_EPISODES_LIST = new ArrayList<>();
    private static final ArrayList<Episode> REMOTE_EPISODES_LIST = new ArrayList<>();

    private static final ArrayList<Episode> WORKING_LOCAL_EPISODES_LIST = new ArrayList<>();
    private static final ArrayList<Episode> WORKING_REMOTE_EPISODES_LIST = new ArrayList<>();

    static {
//        LOCAL_EPISODES.put(FileType.MP3, new Episode("low volume/mixed volume", "hwb41.mp3"));
//        LOCAL_EPISODES.put(FileType.M4A, new Episode("m4a reduce noise", "7.9.2005.m4a"));
//        LOCAL_EPISODES.put(FileType.FLAC, new Episode("FLAC", "week7.flac"));
//        LOCAL_EPISODES.put(FileType.OGG, new Episode("OGG", "Holiday.ogg"));
//
//        REMOTE_EPISODES.put(FileType.MP3, new Episode("low volume/mixed volume", "http://feedproxy.google.com/~r/heywereback/~5/zmNAYMyMOis/hwb41.mp3"));
//        REMOTE_EPISODES.put(FileType.M4A, new Episode("m4a reduce noise", "https://archive.org/download/7.9.2005.m4a_2/7.9.2005.m4a"));
//        REMOTE_EPISODES.put(FileType.FLAC, new Episode("FLAC", "https://archive.org/download/E100Week7BibleStoriesForAdults/week7.flac"));
//        REMOTE_EPISODES.put(FileType.OGG, new Episode("OGG", "https://archive.org/download/FrogcastNumber1/Holiday.ogg"));

        LOCAL_EPISODES_LIST.add(new Episode("m4a reduce noise", "7.9.2005.m4a"));
        LOCAL_EPISODES_LIST.add(new Episode("doesn't work", "bandyman_2011-10-24T15_19_31-07_00.m4a"));
        LOCAL_EPISODES_LIST.add(new Episode("doesn't work", "awareness_2019-04-22T13_32_06-07_00.m4a"));
        LOCAL_EPISODES_LIST.add(new Episode("doesn't work", "AP10_Richard_Cheng.m4a"));
        LOCAL_EPISODES_LIST.add(new Episode("doesn't work", "analoghole_intro_remix.m4a"));

        REMOTE_EPISODES_LIST.add(new Episode("m4a reduce noise",
                "https://archive.org/download/7.9.2005.m4a_2/7.9.2005.m4a"));
        REMOTE_EPISODES_LIST.add(new Episode("doesn't work",
                "https://bandyman.podomatic.com/enclosure/2011-10-24T15_19_31-07_00.m4a"));
        REMOTE_EPISODES_LIST.add(new Episode("doesn't work",
                "https://awareness.podomatic.com/enclosure/2019-04-22T13_32_06-07_00.m4a"));
        REMOTE_EPISODES_LIST.add(new Episode("doesn't work",
                "http://traffic.libsyn.com/agiletoolkit/AP10_Richard_Cheng.m4a?dest-id=14195"));
        REMOTE_EPISODES_LIST.add(new Episode("doesn't work",
                "http://traffic.libsyn.com/analogholegaming/analoghole_intro_remix.m4a?dest-id=13086"));
        REMOTE_EPISODES_LIST.add(new Episode("doesn't work", "http://test.podcast.djhardwell.com/hardwell-on-air-415.m4a"));
        REMOTE_EPISODES_LIST.add(new Episode("m4a not working", "http://www.itsall.love/podcast/djmarvilousitsalllove002.m4a"));
        REMOTE_EPISODES_LIST.add(new Episode("m4a not working", "https://www.advdiaboli.de/podlove/file/333/s/feed/c/aac/hch061-politikunterricht.m4a"));

        WORKING_LOCAL_EPISODES_LIST.add(new Episode("MP3", "hwb41.mp3"));
        WORKING_LOCAL_EPISODES_LIST.add(new Episode("FLAC", "week7.flac"));
        WORKING_LOCAL_EPISODES_LIST.add(new Episode("OGG", "Holiday.ogg"));

        WORKING_REMOTE_EPISODES_LIST.add(new Episode("MP3", "http://feedproxy.google.com/~r/heywereback/~5/zmNAYMyMOis/hwb41.mp3"));
        WORKING_REMOTE_EPISODES_LIST.add(new Episode("FLAC", "https://archive.org/download/E100Week7BibleStoriesForAdults/week7.flac"));
        WORKING_REMOTE_EPISODES_LIST.add(new Episode("OGG", "https://archive.org/download/FrogcastNumber1/Holiday.ogg"));

    }

    private static class Episode {
        public String problem;
        public String url;

        public Episode(String problem, String url) {
            this.problem = problem;
            this.url = url;
        }

        @Override
        public String toString() {
            return problem + "\n" + url;
        }
    }

    /**
     * remote urls listed above and their problem
     * - low volume
     * - low volume/mixed volume
     * - mixed volume
     * - mixed volume
     * - silence
     * - silence
     * - noise reduction
     * - noise reduction
     */

    private List<Episode> mAudioUrls = new ArrayList<Episode>();

    private static final float MIN_SPEED = 0.5f;
    private static final float MAX_SPEED = 4f;
    private static final float SPEED_STEP = 0.1f;
    private static final float ADVANCED_AUDIO_SPPED = 1.5f;

    @Bind(R.id.file_type_mp3)
    RadioButton mFileTypeMp3;

    @Bind(R.id.file_type_m4a)
    RadioButton mFileTypeM4a;

    @Bind(R.id.file_type_flac)
    RadioButton mFileTypeFlac;

    @Bind(R.id.file_type_ogg)
    RadioButton mFileTypeOgg;

    @Bind(R.id.stream_file_container)
    View mStreamFileContainer;

    @Bind(R.id.stream_file_path)
    TextView mStreamFilePath;

    @Bind(R.id.downloaded_file_path)
    TextView mDownloadedFilePath;

    @Bind(R.id.downloaded_file_compress)
    View mDownloadedFileCompress;

    @Bind(R.id.downloaded_file_compress_progress)
    TextView mDownloadedFileCompressProgress;

    @Bind(R.id.compressed_file_container)
    View mCompressedFileContainer;

    @Bind(R.id.compressed_file_path)
    TextView mCompressedFilePath;


    @Bind(R.id.local_episodes_container)
    LinearLayout mLocalEpisodesList;

    @Bind(R.id.remote_episodes_container)
    LinearLayout mRemoteEpisodesList;

    @Bind(R.id.working_formats_episodes_container)
    LinearLayout mWorkingEpisodsList;

//    @Bind(R.id.speed)
//    SeekBar mSpeedBar;
//
//    @Bind(R.id.speed_value)
//    TextView mSpeedValue;


    @Bind(R.id.controls_container)
    View mControlsContainer;

    @Bind(R.id.toggle_audio_effects)
    CheckBox mToggleAudioEffects;

    @Bind(R.id.playing_episode)
    TextView mPlayingEpisode;

    @Bind(R.id.progress)
    SeekBar mProgress;

    @Bind(R.id.current_time)
    TextView mCurrentTime;

    @Bind(R.id.duration)
    TextView mDuration;

    @Bind(R.id.audio_url)
    EditText mAudioUrl;

    /**
     * Playback controls
     */
    @Bind(R.id.play)
    Button mPlay;

    @Bind(R.id.pause)
    Button mPause;

    @Bind(R.id.rewind)
    Button mRewind;

    @Bind(R.id.forward)
    Button mForward;

    @Bind(R.id.previous)
    Button mPrevious;

    @Bind(R.id.next)
    Button mNext;

    @Bind(R.id.stop)
    Button mStop;

    private int mSelectedPosition;

    private float mPlayingSpeed = 1f;

    private boolean mIsSeekingManually;

    private MediaPlayerWrapper mMediaPlayer;

    private FileType mSelectedType;

    private HashMap<FileType, Episode> mCompressedFiles;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        //have to copy files from assets to storage so they looks like downloaded
        copyAssets();

//        mAudioUrls.addAll(REMOTE_EPISODES_LIST);
//        for (Episode episode : LOCAL_EPISODES_LIST) {
//            episode.url = getExternalFilesDir(null) + File.separator + episode.url;
//            mAudioUrls.add(0, episode);
//        }


//        for (Map.Entry<FileType, Episode> item : REMOTE_EPISODES.entrySet()) {
//            Episode episode = item.getValue();
//            mAudioUrls.add(episode);
//        }
//        for (Map.Entry<FileType, Episode> item : LOCAL_EPISODES.entrySet()) {
//            Episode episode = item.getValue();
//            episode.url = getExternalFilesDir(null) + File.separator + episode.url;
//            LOCAL_EPISODES.put(item.getKey(), episode);
//            item.setValue(episode);
////            LOCAL_EPISODES.put(item.getKey(), episode);
//            mAudioUrls.add(0, episode);
//        }

        File externalStorage = Environment.getExternalStorageDirectory();
        if (externalStorage != null) {
            File test = new File(externalStorage, "PlayerAudioTest");
            test.mkdirs();
//            mLocalFolderPath.setText(test.getAbsolutePath());
//
//            File[] files = test.listFiles();
//            if (files != null) {
//                for (File f : files) {
//                    mAudioUrls.add(new Episode("unknown", f.getAbsolutePath()));
//                }
//            }
        }

//        loadCompressedEpisodes();
        setupViews();
    }

    @Override
    protected void onResume() {
        super.onResume();
//        mControlsContainer.setVisibility(View.INVISIBLE);
        enablePlayer(false);
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        EventBus.getDefault().unregister(this);

        if (mMediaPlayer != null) {
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
    }

    static class EpisodesAdapter extends ArrayAdapter<Episode> {

        public EpisodesAdapter(Context context, int resource, List<Episode> objects) {
            super(context, resource, objects);
        }
    }


    private void setupViews() {
        //downloaded
        mLocalEpisodesList.removeAllViews();
        for (Episode episode : LOCAL_EPISODES_LIST) {
            addEpisodeCardView(mLocalEpisodesList, episode);
        }
        //streamed
        mRemoteEpisodesList.removeAllViews();
        for (Episode episode : REMOTE_EPISODES_LIST) {
            addEpisodeCardView(mRemoteEpisodesList, episode);
        }

        mWorkingEpisodsList.removeAllViews();
        for (Episode episode : WORKING_LOCAL_EPISODES_LIST) {
            addEpisodeCardView(mWorkingEpisodsList, episode);
        }
        for (Episode episode : WORKING_REMOTE_EPISODES_LIST) {
            addEpisodeCardView(mWorkingEpisodsList, episode);
        }


//        EpisodesAdapter adapter = new EpisodesAdapter(this, android.R.layout.simple_list_item_1, mAudioUrls);

//        mFilesSpinner.setAdapter(adapter);

//        //progress is between MIN_SPEED(0.5) and MAX_SPEED(4) and step is SPEED_STEP(0.1)
//        mSpeedBar.setMax((int) ((MAX_SPEED - MIN_SPEED) / SPEED_STEP));
//        mSpeedBar.setOnSeekBarChangeListener(mSpeedChangeListener);
//        //set progress to 1x
//        mSpeedBar.setProgress((int) ((1f - MIN_SPEED) / SPEED_STEP));

        mProgress.setOnSeekBarChangeListener(mProgressBarListener);

//        mTest.setText("TEST: custom player package: " + CustomMediaPlayer.class.getPackage());

//        selectFileType(FileType.MP3);
    }

    private void addEpisodeCardView(LinearLayout container, final Episode episode) {
        View view = LayoutInflater.from(this).inflate(R.layout.episode_card_view, null);
        TextView path = view.findViewById(R.id.path);
        path.setText(episode.url);
        view.findViewById(R.id.play).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPlayback(episode.url);
            }
        });

        container.addView(view);
    }

    private void loadCompressedEpisodes() {
        mCompressedFiles = new HashMap<>();
        File externalStorage = new File(DownloadUtils.prepareDownloadsFolder(getApplicationContext()));
        if (externalStorage != null) {
            File compressedDir = new File(externalStorage, "compressed");
            if (compressedDir.exists() && compressedDir.isDirectory()) {
                File[] files = compressedDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String path = f.getAbsolutePath();
                        FileType fileType = FileType.MP3;
                        String suffix = ".little.compressed.ogg";
                        if (path.toLowerCase().endsWith(".mp3" + suffix)) {
                            fileType = FileType.MP3;
                        } else if (path.toLowerCase().endsWith(".m4a" + suffix)) {
                            fileType = FileType.M4A;
                        } else if (path.toLowerCase().endsWith(".flac" + suffix)) {
                            fileType = FileType.FLAC;
                        } else if (path.toLowerCase().endsWith(".ogg" + suffix)) {
                            fileType = FileType.OGG;
                        }

                        mCompressedFiles.put(fileType, new Episode("compressed", path));
                    }
                }
            }
        }
    }

    @OnClick(R.id.file_type_mp3)
    void fileTypeMp3() {
        selectFileType(FileType.MP3);
    }

    @OnClick(R.id.file_type_m4a)
    void fileTypeM4a() {
        selectFileType(FileType.M4A);
    }

    @OnClick(R.id.file_type_flac)
    void fileTypeFlac() {
        selectFileType(FileType.FLAC);
    }

    @OnClick(R.id.file_type_ogg)
    void fileTypeOgg() {
        selectFileType(FileType.OGG);
    }

    private void selectFileType(FileType fileType) {
        mSelectedType = fileType;
        Episode episodeLocal = LOCAL_EPISODES.get(fileType);
        Episode episodeRemote = REMOTE_EPISODES.get(fileType);
        Episode episodeCompressed = mCompressedFiles.get(fileType);
        if (episodeRemote != null) {
            mStreamFilePath.setText(episodeRemote.url);
            if (fileType == FileType.M4A) {
                //speed player for m4a stream not available
                mStreamFileContainer.setVisibility(View.GONE);
            } else {
                mStreamFileContainer.setVisibility(View.VISIBLE);
            }
        }
        if (episodeLocal != null) {
            mDownloadedFilePath.setText(episodeLocal.url);
        }
        if (episodeCompressed != null) {
            mCompressedFilePath.setText(episodeCompressed.url);
            mCompressedFileContainer.setVisibility(View.VISIBLE);
            mDownloadedFileCompress.setVisibility(View.GONE);
        } else {
            mCompressedFileContainer.setVisibility(View.GONE);
            mDownloadedFileCompress.setVisibility(View.VISIBLE);
        }
    }

    @OnClick(R.id.clear_audio_url)
    void clearAudioUrl(){
        mAudioUrl.setText(null);
    }

    @OnClick(R.id.start_audio_url)
    void playAudioUrl() {
        String url = mAudioUrl.getText() != null ? mAudioUrl.getText().toString() : null;
        if (!TextUtils.isEmpty(url)) {
            startPlayback(url);
            mAudioUrl.clearFocus();
        }
    }

    @OnClick(R.id.stream_file_play)
    void playStreamFile() {
        Episode episode = REMOTE_EPISODES.get(mSelectedType);
        if (episode != null) {
            startPlayback(episode.url);
        }
    }

    @OnClick(R.id.downloaded_file_play)
    void playDownloadedFile() {
        Episode episode = LOCAL_EPISODES.get(mSelectedType);
        if (episode != null) {
            startPlayback(episode.url);
        }
    }

    @OnClick(R.id.compressed_file_play)
    void playCompressedFile() {
        Episode episode = mCompressedFiles.get(mSelectedType);
        if (episode != null) {
            startPlayback(episode.url);
        }
    }

    @OnClick(R.id.compressed_file_delete)
    void deleteCompressedFile() {
        Episode episode = mCompressedFiles.get(mSelectedType);
        if (episode != null) {
            File file = new File(episode.url);
            if (file.exists()) {
                boolean deleted = file.delete();
                if (deleted) {
                    loadCompressedEpisodes();
                    selectFileType(mSelectedType);
                }
            }
        }
    }

    @OnClick(R.id.downloaded_file_compress)
    void compressFile() {
        Episode episode = LOCAL_EPISODES.get(mSelectedType);
        if (episode != null) {
            Intent intent = SpaceSaverIntentService.newIntentCompressEpisode(this, episode.url,
                    episode.url, false);
            SpaceSaverIntentService.enqueueWork(getApplicationContext(), intent);
        }
    }

    private void startPlayback(String url) {
//        mControlsContainer.setVisibility(View.VISIBLE);
        enablePlayer(true);

        if (mMediaPlayer != null) {
            mMediaPlayer.release();
            mMediaPlayer = null;
        }

        mMediaPlayer = new MediaPlayerWrapper(this, mMediaPlayerListener, true);
//        mMediaPlayer.setSpeed(mPlayingSpeed);
//        mMediaPlayer.setVolumeBoost(mBoostVolume.isChecked());
//        mMediaPlayer.setSilenceSkip(mSkipSilence.isChecked());
//        mMediaPlayer.setReduceNoise(mReduceNoise.isChecked());

        if (mToggleAudioEffects.isChecked()) {
            mMediaPlayer.setSpeed(ADVANCED_AUDIO_SPPED);
            mMediaPlayer.setVolumeBoost(true);
            mMediaPlayer.setSilenceSkip(true);
            mMediaPlayer.setReduceNoise(true);
        } else {
            mMediaPlayer.setSpeed(1.0f);
            mMediaPlayer.setVolumeBoost(false);
            mMediaPlayer.setSilenceSkip(false);
            mMediaPlayer.setReduceNoise(false);
        }

        mPlayingEpisode.setText(url);
        Uri uri = Uri.parse(url);
        try {
            if (uri.getScheme() == null || uri.getScheme().equals("file")) {
                mMediaPlayer.setDataSourceLocal(url);
            } else {
                mMediaPlayer.setDataSourceRemote(url);
            }
        } catch (IOException e) {
            e.printStackTrace();
            showToast(e.getMessage());
        }

        showToast("prepareAsync");
        mMediaPlayer.prepareAsync();
    }


    private void setSpeedProgress(float speed, int progress) {
//        mSpeedBar.setProgress(progress);
//        mSpeedValue.setText(Utils.speedToString(speed));
    }

    private void changingSpeed(int seekbarProgress) {
        mPlayingSpeed = MIN_SPEED + (SPEED_STEP * seekbarProgress);
        setSpeedProgress(mPlayingSpeed, seekbarProgress);
    }

    SeekBar.OnSeekBarChangeListener mSpeedChangeListener = new SeekBar.OnSeekBarChangeListener() {

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            changingSpeed(seekBar.getProgress());
            showToast("Changing speed to " + String.format("%.1f", mPlayingSpeed));
            if (mMediaPlayer != null)
                mMediaPlayer.setSpeed(mPlayingSpeed);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser)
                changingSpeed(seekBar.getProgress());
        }
    };

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private MediaPlayerWrapperListener mMediaPlayerListener = new MediaPlayerWrapperListener() {
        @Override
        public void onBufferingUpdate(int percent) {

        }

        @Override
        public void onCompletion() {
            showToast("onCompletion");
        }

        @Override
        public void onPrepared() {
            showToast("onPrepared");
            int startAtMilliseconds = 0;
//            if (mStartAt.getText() != null) {
//                String text = mStartAt.getText().toString();
//                if (!TextUtils.isEmpty(text)) {
//                    startAtMilliseconds = Integer.valueOf(text).intValue() * 1000;
//                }
//            }

            //this is because we want to start some other position than beginning. can we commented out if seek is not implemented
            if (mMediaPlayer != null) {
                mMediaPlayer.seekTo(startAtMilliseconds);
            }
        }

        @Override
        public void onSeekComplete() {
            showToast("onSeekComplete");
            if (mMediaPlayer != null) {
                mMediaPlayer.start();
                setPlaying(true);
            }
        }

        @Override
        public void handleError(MediaPlayerWrapper mediaPlayer, int i, int i1, Exception exception) {
            showToast("handleError");
        }

        @Override
        public void onVideoSizeChanged(int width, int height) {
            showToast("onVideoSizeChanged");
        }
    };

    private void setPlaying(boolean isPlaying) {
        mPlay.setVisibility(isPlaying ? View.GONE : View.VISIBLE);
        mPause.setVisibility(isPlaying ? View.VISIBLE : View.GONE);
        if (isPlaying) {
            mUpdateProgressHandler.post(mUpdateProgress);
        }
    }

    private void startFromPosition(int position) {
        int lastPosition = mAudioUrls.size() - 1;
        if (position > lastPosition) {
            position = 0;
        } else if (position < 0) {
            position = lastPosition;
        }
        mSelectedPosition = position;

        String url = mAudioUrls.get(mSelectedPosition).url;
        startPlayback(url);
    }

    @OnClick(R.id.toggle_audio_effects)
    void toggleAudioEffects() {
        if (mMediaPlayer != null) {
            if (mToggleAudioEffects.isChecked()) {
                mMediaPlayer.setSpeed(ADVANCED_AUDIO_SPPED);
                mMediaPlayer.setVolumeBoost(true);
                mMediaPlayer.setSilenceSkip(true);
                mMediaPlayer.setReduceNoise(true);
            } else {
                mMediaPlayer.setSpeed(1.0f);
                mMediaPlayer.setVolumeBoost(false);
                mMediaPlayer.setSilenceSkip(false);
                mMediaPlayer.setReduceNoise(false);
            }
        }
    }

    @OnClick(R.id.rewind)
    void rewind() {
        //seek to seconds
        if (mMediaPlayer != null)
            mMediaPlayer.seekTo(mMediaPlayer.getCurrentPosition() - 10 * 1000);
    }

    @OnClick(R.id.pause)
    void pause() {
        if (mMediaPlayer != null) {
            mMediaPlayer.pause();
            setPlaying(false);
        }
    }

    @OnClick(R.id.play)
    void play() {
        if (mMediaPlayer != null) {
            mMediaPlayer.start();
            setPlaying(true);
        }
    }

    @OnClick(R.id.forward)
    void forward() {
        if (mMediaPlayer != null) {
            mMediaPlayer.seekTo(mMediaPlayer.getCurrentPosition() + 10 * 1000);
        }
    }

    @OnClick(R.id.previous)
    void previous() {
        startFromPosition(mSelectedPosition - 1);
    }

    @OnClick(R.id.next)
    void next() {
        startFromPosition(mSelectedPosition + 1);
    }

    @OnClick(R.id.stop)
    void stop() {
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.release();
            mMediaPlayer = null;
        }

//        mControlsContainer.setVisibility(View.INVISIBLE);
        enablePlayer(false);
    }

    private Handler mUpdateProgressHandler = new Handler();

    private Runnable mUpdateProgress = new Runnable() {
        @Override
        public void run() {
            if (mMediaPlayer != null && !mIsSeekingManually) {
                int currentPosition = mMediaPlayer != null ? mMediaPlayer.getCurrentPosition() : 0;
                int totalDuration = mMediaPlayer != null ? mMediaPlayer.getDuration() : 0;
                int progress = Utils.getProgressPercentage(currentPosition, totalDuration);
                mProgress.setProgress(progress);

                String currentTime = Utils.milliSecondsToTimer(currentPosition);
                String totalTime = Utils.milliSecondsToTimer(totalDuration);
                mCurrentTime.setText(currentTime);
                mDuration.setText(totalTime);

                if (mMediaPlayer.isPlaying()) {
                    mUpdateProgressHandler.removeCallbacks(mUpdateProgress);
                    mUpdateProgressHandler.postDelayed(mUpdateProgress, 1000);
                }
            }
        }
    };

    private void seekTo(int progress) {
        if (mMediaPlayer != null) {
            int progressInMillisec = mMediaPlayer.getDuration() / 100 * progress;
            mMediaPlayer.seekTo(progressInMillisec);
        }
    }


    SeekBar.OnSeekBarChangeListener mProgressBarListener = new SeekBar.OnSeekBarChangeListener() {

        private int mProgressOnStop;
        private boolean hasMoveManualy = false;

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            // Do nothing
            mIsSeekingManually = false;
            seekTo(seekBar.getProgress());
            hasMoveManualy = true;
            mProgressOnStop = seekBar.getProgress();
            Alog.v(TAG, "Seek bar on stop tracking touch : " + seekBar.getProgress());

        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            // Do nothing
            mIsSeekingManually = true;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {

            if (mIsSeekingManually && fromUser) {
                int progressInMillisec = mMediaPlayer.getDuration() / 100 * progress;
                mCurrentTime.setText(Utils.milliSecondsToTimer(progressInMillisec));
            } else {
                if (mProgressOnStop != progress && hasMoveManualy) {
                    Alog.v(TAG, "Seek bar Correction needed detected onStop = " + mProgressOnStop);
                    seekBar.setProgress(mProgressOnStop);
                }
                hasMoveManualy = false;
            }
            Alog.v(TAG, "Seek bar on progress changed" + progress);
        }
    };

    private void copyAssets() {
        final AssetManager assetManager = getAssets();
//        for (Map.Entry<FileType, Episode> item : LOCAL_EPISODES.entrySet()) {
//            Episode episode = item.getValue();
//            InputStream in = null;
//            OutputStream out = null;
//            try {
//                in = assetManager.open(episode.url);
//                File outFile = new File(getExternalFilesDir(null), episode.url);
//                out = new FileOutputStream(outFile);
//                copyFile(in, out);
//            } catch (IOException e) {
//                e.printStackTrace();
//                Alog.e(TAG, "Failed to copy asset file: " + episode.url);
//            } finally {
//                if (in != null) {
//                    try {
//                        in.close();
//                    } catch (IOException e) {
//                        // NOOP
//                    }
//                }
//                if (out != null) {
//                    try {
//                        out.close();
//                    } catch (IOException e) {
//                        // NOOP
//                    }
//                }
//            }
//        }

        new Thread(new Runnable() {
            @Override
            public void run() {


                for (Episode episode : LOCAL_EPISODES_LIST) {
                    InputStream in = null;
                    OutputStream out = null;
                    try {
                        in = assetManager.open(episode.url);
                        File outFile = new File(getExternalFilesDir(null), episode.url);
                        out = new FileOutputStream(outFile);
                        copyFile(in, out);

                        //update path
                        episode.url = getExternalFilesDir(null) + File.separator + episode.url;
                    } catch (IOException e) {
                        e.printStackTrace();
                        Alog.e(TAG, "Failed to copy asset file: " + episode.url + ", E: " + e.getMessage());
                    } finally {
                        if (in != null) {
                            try {
                                in.close();
                            } catch (IOException e) {
                                // NOOP
                            }
                        }
                        if (out != null) {
                            try {
                                out.close();
                            } catch (IOException e) {
                                // NOOP
                            }
                        }
                    }
                }

                for (Episode episode : WORKING_LOCAL_EPISODES_LIST) {
                    InputStream in = null;
                    OutputStream out = null;
                    try {
                        in = assetManager.open(episode.url);
                        File outFile = new File(getExternalFilesDir(null), episode.url);
                        out = new FileOutputStream(outFile);
                        copyFile(in, out);

                        //update path
                        episode.url = getExternalFilesDir(null) + File.separator + episode.url;
                    } catch (IOException e) {
                        e.printStackTrace();
                        Alog.e(TAG, "Failed to copy asset file: " + episode.url + ", E: " + e.getMessage());
                    } finally {
                        if (in != null) {
                            try {
                                in.close();
                            } catch (IOException e) {
                                // NOOP
                            }
                        }
                        if (out != null) {
                            try {
                                out.close();
                            } catch (IOException e) {
                                // NOOP
                            }
                        }
                    }
                }

                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        setupViews();
                    }
                });
            }
        }).start();
    }

    private void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private void enablePlayer(boolean enable) {
        mProgress.setEnabled(enable);
        mPlay.setEnabled(enable);
        mPause.setEnabled(enable);
        mRewind.setEnabled(enable);
        mForward.setEnabled(enable);
        mPrevious.setEnabled(enable);
        mNext.setEnabled(enable);
        mStop.setEnabled(enable);
    }

    public void onEventMainThread(Events.EpisodeCompressed event) {
        mDownloadedFileCompressProgress.setText("");
        loadCompressedEpisodes();
        selectFileType(mSelectedType);
    }

    public void onEventMainThread(Events.CompressionProgress event) {
        if (event.progressPercentage <= 0 || event.progressPercentage >= 100) {
            mDownloadedFileCompressProgress.setText("");
        } else {
            String progressText = event.progressPercentage + "%";
            mDownloadedFileCompressProgress.setText(progressText);
        }
    }
}
