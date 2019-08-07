package fm.player.playeraudio;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnCheckedChanged;
import butterknife.OnClick;
import fm.player.playerandroidaudio.R;
import fm.player.mediaplayer.player.CustomMediaPlayer;
import fm.player.playback.MediaPlayerWrapper;
import fm.player.playback.MediaPlayerWrapperListener;
import fm.player.utils.Alog;
import fm.player.utils.Utils;


public class PlayerActivity extends AppCompatActivity {
    private static final String TAG = "PlayerActivity";


    private static final Episode[] LOCAL_FILES = new Episode[]{new Episode("silence/low volume",
            "ListenToTheOldPeopleShalimarladyAmy.mp3"),
            new Episode("noise reduction", "VS_40-01-19_-_Gumpox_vs_Donahue.mp3")
    };

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

    private static final Episode[] REMOTE_URLS = new Episode[]{
            new Episode("FLAC", "https://archive.org/download/E100Week7BibleStoriesForAdults/week7.flac"),
            new Episode("OGG", "https://archive.org/download/FrogcastNumber1/Holiday.ogg"),
            new Episode("m4a not working", "http://www.itsall.love/podcast/djmarvilousitsalllove002.m4a"),
            new Episode("m4a strange sound", "http://traffic.libsyn.com/psicologiateologica/Project19Nov2015_-_11_19_15_3.49_AM.m4a"),
            new Episode("m4a reduce noise", "https://archive.org/download/7.9.2005.m4a_2/7.9.2005.m4a"),
            new Episode("m4a skip silence", "https://archive.org/download/JerryHarringtonAacTest/test.m4a"),
            new Episode("mp4", "http://feeds.aljazeera.net/~r/podcasts/thestreamaudio/~5/jRpyKcvNZIY/864352181001_4433361126001_Full-Show-1915.mp4"),
            new Episode("low volume", "http://www.blogtalkradio.com/thirdphaseofmoon-ufo-sightings/2015/04/04/alien-abduction-ufo-sightings-live-thirdphaseofmoon-radio.mp3"),
            new Episode("low volume/mixed volume", "http://feedproxy.google.com/~r/heywereback/~5/zmNAYMyMOis/hwb41.mp3"),
            new Episode("mixed volume", "http://traffic.libsyn.com/rrrfm/Breakfasters_-_20152703.mp3"),
            new Episode("mixed volume", "http://traffic.libsyn.com/rrrfm/Platos-Cave-20150330.mp3"),
            new Episode("silence", "http://feeds.soundcloud.com/stream/193423486-wrestlingandy-omp99.mp3"),
            new Episode("silence", "http://www.craftbeerradio.com/cbr/CBR-327-20150404.mp3"),
            new Episode("noise reduction", "http://feedproxy.google.com/~r/2GaGaaG/~5/tWvySFDJL9Y/2GAGAAG_S2E12.mp3"),
            new Episode("noise reduction", "http://media.wizards.com/2015/podcasts/magic/drivetowork215_traveling.mp3"),
            new Episode("OGG", "http://www.podtrac.com/pts/redirect.ogg/traffic.libsyn.com/jnite/lup-0099.ogg")
    };
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

    @Bind(R.id.files_spinner)
    Spinner mFilesSpinner;

    @Bind(R.id.audio_url)
    EditText mAudioUrl;

    @Bind(R.id.speed)
    SeekBar mSpeedBar;

    @Bind(R.id.speed_value)
    TextView mSpeedValue;


    @Bind(R.id.start_at)
    EditText mStartAt;

    @Bind(R.id.boost_volume)
    CheckBox mBoostVolume;

    @Bind(R.id.skip_silence)
    CheckBox mSkipSilence;

    @Bind(R.id.reduce_noise)
    CheckBox mReduceNoise;

    @Bind(R.id.use_android_media_player)
    CheckBox mUseAndroidMediaPlayer;

    @Bind(R.id.local_folder_path)
    TextView mLocalFolderPath;

    @Bind(R.id.controls_container)
    View mControlsContainer;

    @Bind(R.id.playing_episode)
    TextView mPlayingEpisode;

    @Bind(R.id.progress)
    SeekBar mProgress;

    @Bind(R.id.current_time)
    TextView mCurrentTime;

    @Bind(R.id.duration)
    TextView mDuration;

    @Bind(R.id.test)
    TextView mTest;

    /**
     * Playback controls
     */
    @Bind(R.id.play)
    Button mPlay;
    @Bind(R.id.pause)
    Button mPause;

    private int mSelectedPosition;

    private float mPlayingSpeed = 1f;

    private boolean mIsSeekingManually;

    private MediaPlayerWrapper mMediaPlayer;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        ButterKnife.bind(this);

        //have to copy files from assets to storage so they looks like downloaded
        copyAssets();

        mAudioUrls.addAll(Arrays.asList(REMOTE_URLS));
        for (Episode episode : LOCAL_FILES) {
            episode.url = getExternalFilesDir(null) + File.separator + episode.url;
            mAudioUrls.add(0, episode);
        }

        File externalStorage = Environment.getExternalStorageDirectory();
        if (externalStorage != null) {
            File test = new File(externalStorage, "PlayerAudioTest");
            test.mkdirs();
            mLocalFolderPath.setText(test.getAbsolutePath());

            File[] files = test.listFiles();
            if (files != null) {
                for (File f : files) {
                    mAudioUrls.add(new Episode("unknown", f.getAbsolutePath()));
                }
            }
        }

        setupViews();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mControlsContainer.setVisibility(View.INVISIBLE);
    }

    @Override
    protected void onPause() {
        super.onPause();

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

        EpisodesAdapter adapter = new EpisodesAdapter(this, android.R.layout.simple_list_item_1, mAudioUrls);

        mFilesSpinner.setAdapter(adapter);

        //progress is between MIN_SPEED(0.5) and MAX_SPEED(4) and step is SPEED_STEP(0.1)
        mSpeedBar.setMax((int) ((MAX_SPEED - MIN_SPEED) / SPEED_STEP));
        mSpeedBar.setOnSeekBarChangeListener(mSpeedChangeListener);
        //set progress to 1x
        mSpeedBar.setProgress((int) ((1f - MIN_SPEED) / SPEED_STEP));

        mProgress.setOnSeekBarChangeListener(mProgressBarListener);

        mTest.setText("TEST: custom player package: " + CustomMediaPlayer.class.getPackage());
    }

    @OnCheckedChanged(R.id.boost_volume)
    void boostVolume(boolean checked) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setVolumeBoost(checked);
        }
    }

    @OnCheckedChanged(R.id.skip_silence)
    void skipSilence(boolean checked) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setSilenceSkip(checked);
        }
    }

    @OnCheckedChanged(R.id.reduce_noise)
    void reduceNoise(boolean checked) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setReduceNoise(checked);
        }
    }


    private void startPlayback(String url) {
        mControlsContainer.setVisibility(View.VISIBLE);

        if (mMediaPlayer != null) {
            mMediaPlayer.release();
            mMediaPlayer = null;
        }

        mMediaPlayer = new MediaPlayerWrapper(this, mMediaPlayerListener, !mUseAndroidMediaPlayer.isChecked());
        mMediaPlayer.setSpeed(mPlayingSpeed);
        mMediaPlayer.setVolumeBoost(mBoostVolume.isChecked());
        mMediaPlayer.setSilenceSkip(mSkipSilence.isChecked());
        mMediaPlayer.setReduceNoise(mReduceNoise.isChecked());

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
        mSpeedBar.setProgress(progress);
        mSpeedValue.setText(Utils.speedToString(speed));
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
            if (mStartAt.getText() != null) {
                String text = mStartAt.getText().toString();
                if (!TextUtils.isEmpty(text)) {
                    startAtMilliseconds = Integer.valueOf(text).intValue() * 1000;
                }
            }

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


    @OnClick(R.id.select_episode)
    void selectEpisode() {
        startActivityForResult(new Intent(this, SelectPlayerFMEpisode.class), 0);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 0 && resultCode == RESULT_OK) {
            String url = data.getStringExtra("url");
            mAudioUrl.setText(url);
        }
    }

    @OnClick(R.id.start_selected)
    void startSelected() {
        //seek to seconds
        if (mAudioUrl.getText().toString().isEmpty()) {
            mSelectedPosition = mFilesSpinner.getSelectedItemPosition();
            startFromPosition(mSelectedPosition);
        } else {
            startPlayback(mAudioUrl.getText().toString());
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

        mControlsContainer.setVisibility(View.INVISIBLE);
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
        AssetManager assetManager = getAssets();
        for (Episode episode : LOCAL_FILES) {
            InputStream in = null;
            OutputStream out = null;
            try {
                in = assetManager.open(episode.url);
                File outFile = new File(getExternalFilesDir(null), episode.url);
                out = new FileOutputStream(outFile);
                copyFile(in, out);
            } catch (IOException e) {
                e.printStackTrace();
                Alog.e(TAG, "Failed to copy asset file: " + episode.url);
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
    }

    private void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

}
