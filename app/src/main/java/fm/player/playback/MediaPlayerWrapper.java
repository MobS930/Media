package fm.player.playback;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import fm.player.mediaplayer.player.CustomMediaPlayer;
import fm.player.mediaplayer.player.CustomMediaPlayerListener;
import fm.player.utils.Alog;

/**
 * Created by mac on 20/11/2013.
 */
public class MediaPlayerWrapper implements MediaPlayer.OnErrorListener, MediaPlayer.OnPreparedListener,
        MediaPlayer.OnSeekCompleteListener, MediaPlayer.OnBufferingUpdateListener,
        MediaPlayer.OnCompletionListener, MediaPlayer.OnVideoSizeChangedListener, CustomMediaPlayerListener {

    private static final String TAG = MediaPlayerWrapper.class.getSimpleName();

    private MediaPlayer mMediaPlayer;

    private CustomMediaPlayer mSpeedMediaPlayer;

    private boolean mIsCustomPlayer = CustomMediaPlayer.isCustomPlayerImplementedOnThisArchitecture();

    private MediaPlayerWrapperListener mMediaPlayerWrapperListener;

    private Context mContext;

    public MediaPlayerWrapper(Context context, MediaPlayerWrapperListener listener, boolean useCustomPlayer) {
        mMediaPlayerWrapperListener = listener;
        mContext = context;

        mIsCustomPlayer = mIsCustomPlayer && useCustomPlayer;//allowing for all users now && (FeaturesConfiguration.isSpeedEnabled(context) || FeaturesConfiguration.isSpeedTestUser(context))

        if (mIsCustomPlayer) {
            mSpeedMediaPlayer = new CustomMediaPlayer();
            mSpeedMediaPlayer.setSpeedMediaPlayerListener(this);
        }
        mMediaPlayer = new MediaPlayer();
        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mMediaPlayer.setOnPreparedListener(this);
        mMediaPlayer.setOnErrorListener(this);
        mMediaPlayer.setOnSeekCompleteListener(this);
        mMediaPlayer.setOnBufferingUpdateListener(this);
        mMediaPlayer.setOnCompletionListener(this);
        mMediaPlayer.setOnVideoSizeChangedListener(this);
    }

    public void setDisplay(Surface vidHolder) {
        if (!mIsCustomPlayer) {
            mMediaPlayer.setSurface(vidHolder);
        }
    }


    public boolean isCustomPlayer() {
        return mIsCustomPlayer;
    }

    public void setDataSourceLocal(String path) throws IllegalStateException, IOException, IllegalArgumentException {
        Alog.v(TAG, "MediaPlayerWrapper setDataSourceLocal: " + path);

        mIsCustomPlayer = mIsCustomPlayer && CustomMediaPlayer.isFileTypeSupported(path);
        if (mIsCustomPlayer && CustomMediaPlayer.isFileTypeSupported(path)) {
            mSpeedMediaPlayer.setDataSource(path, true);
        } else {
            Uri uri = Uri.parse(path);
            Alog.v(TAG, "play local file: " + uri.getPath());
            File f = new File(uri.getPath());
            Alog.v(TAG, "file exists: " + f.exists() + " is readable: " + f.canRead());
            FileInputStream fileInputStream = new FileInputStream(f);
            mMediaPlayer.setDataSource(fileInputStream.getFD());
            fileInputStream.close();
        }
    }

    public void setDataSourceRemote(String path) throws IllegalStateException, IOException, IllegalArgumentException, SecurityException {
        Alog.v(TAG, "MediaPlayerWrapper setDataSourceRemote: " + path);
        mIsCustomPlayer = mIsCustomPlayer && CustomMediaPlayer.isFileTypeSupported(path);
        if (mIsCustomPlayer) {
            mSpeedMediaPlayer.setDataSource(path, false);
        } else {
            //  mMediaPlayer.setDataSource(getApplicationContext(), Uri.parse(episodeHelper.getEpisodeUrl()));
            mMediaPlayer.setDataSource(path);
        }
    }

    public void setSpeed(float speed) {
        if (mIsCustomPlayer && mSpeedMediaPlayer != null) {
            mSpeedMediaPlayer.setSpeed(speed);
        }
    }

    public float getSpeed() {
        if (mIsCustomPlayer && mSpeedMediaPlayer != null) {
            return mSpeedMediaPlayer.getSpeed();
        }
        return 0;
    }

    public void setVolumeBoost(boolean enable) {
        if (mIsCustomPlayer && mSpeedMediaPlayer != null) {
            mSpeedMediaPlayer.setVolumeBoost(enable);
        }
    }

    public void setSilenceSkip(boolean enable) {
        if (mIsCustomPlayer && mSpeedMediaPlayer != null) {
            mSpeedMediaPlayer.setSilenceSkip(enable);
        }
    }

    public void setReduceNoise(boolean enable){
        if (mIsCustomPlayer && mSpeedMediaPlayer != null) {
            mSpeedMediaPlayer.setReduceNoise(enable);
        }
    }

    public void prepareAsync() throws IllegalStateException {
        Alog.v(TAG, "MediaPlayerWrapper prepareAsync");

        if (mIsCustomPlayer) {
            mSpeedMediaPlayer.prepareAsync(mContext);
        } else {
            mMediaPlayer.prepareAsync();
        }
    }

    public int getCurrentPosition() {
        if (mIsCustomPlayer) {
            return mSpeedMediaPlayer.getCurrentPosition();
        } else {
            return mMediaPlayer.getCurrentPosition();
        }
    }

    public int getDuration() {
        if (mIsCustomPlayer) {
            return mSpeedMediaPlayer.getDuration();
        } else {
            return mMediaPlayer.getDuration();
        }
    }

    public boolean isPlaying() throws IllegalStateException {
        if (mIsCustomPlayer) {
            return mSpeedMediaPlayer.isPlaying();
        } else {
            return mMediaPlayer.isPlaying();
        }
    }

    public void pause() throws IllegalStateException {
        Alog.v(TAG, "MediaPlayerWrapper pause");

        Alog.v(TAG, "cast player pause local");
        if (mIsCustomPlayer) {
            if (mSpeedMediaPlayer.isPlaying())
                mSpeedMediaPlayer.pause();
        } else {
            if (mMediaPlayer.isPlaying())
                mMediaPlayer.pause();
        }
    }

    public void release() {
        Alog.v(TAG, "MediaPlayerWrapper release");

        if (mIsCustomPlayer) {
            if (mSpeedMediaPlayer != null)
                mSpeedMediaPlayer.release();
            mSpeedMediaPlayer = null;
        } else {
            if (mMediaPlayer != null)
                mMediaPlayer.release();
            mMediaPlayer = null;
        }
    }

    public void reset() {
        Alog.v(TAG, "MediaPlayerWrapper reset");
        if (mIsCustomPlayer) {
            if (mSpeedMediaPlayer != null)
                mSpeedMediaPlayer.reset();
        } else {
            if (mMediaPlayer != null)
                mMediaPlayer.reset();
        }
    }

    public void seekTo(int msec) throws IllegalStateException {
        Alog.v(TAG, "MediaPlayerWrapper seekTo: " + msec);
        if (mIsCustomPlayer) {
            mSpeedMediaPlayer.seekTo(msec);
        } else {
            mMediaPlayer.seekTo(msec);
        }
    }


    public void start() throws IllegalStateException {
        Alog.v(TAG, "MediaPlayerWrapper start");
        if (mIsCustomPlayer) {
            mSpeedMediaPlayer.start(mContext);
        } else {
            mMediaPlayer.start();
        }
    }

    public void stop() throws IllegalStateException {
        Alog.v(TAG, "MediaPlayerWrapper stop");
        if (mIsCustomPlayer) {
            mSpeedMediaPlayer.stop();
        } else {
            mMediaPlayer.stop();
        }
    }

    @Override
    public void onBufferingUpdate(MediaPlayer mediaPlayer, int i) {
        mMediaPlayerWrapperListener.onBufferingUpdate(i);
    }

    @Override
    public void onCompletion(MediaPlayer mediaPlayer) {
        mMediaPlayerWrapperListener.onCompletion();
    }

    @Override
    public boolean onError(MediaPlayer mediaPlayer, int i, int i2) {
        mMediaPlayerWrapperListener.handleError(this, i, i2, null);
        return false;
    }

    @Override
    public void onPrepared(MediaPlayer mediaPlayer) {
        mMediaPlayerWrapperListener.onPrepared();
    }

    @Override
    public void onSeekComplete(MediaPlayer mediaPlayer) {
        mMediaPlayerWrapperListener.onSeekComplete();
    }

    @Override
    public void onPlaying(CustomMediaPlayer customMediaPlayer, long getCurrentBytePoint) {

    }

    @Override
    public void onBufferingUpdate(CustomMediaPlayer mp, final int percent) {
        mMediaPlayerWrapperListener.onBufferingUpdate(percent);
    }

    @Override
    public void onCompletion(CustomMediaPlayer mp) {
        Alog.v(TAG, "MediaPlayerWrapper onCompletion");
        mMediaPlayerWrapperListener.onCompletion();
    }

    @Override
    public boolean onError(final CustomMediaPlayer mp, final int what, final int extra) {
        Alog.v(TAG, "MediaPlayerWrapper onError");
        mMediaPlayerWrapperListener.handleError(this, what, extra, null);
        return false;
    }

    @Override
    public void onPrepared(CustomMediaPlayer mp) {
        Alog.v(TAG, "MediaPlayerWrapper onPrepared");
        mMediaPlayerWrapperListener.onPrepared();
    }

    @Override
    public void onSeekComplete(CustomMediaPlayer mp) {
        Alog.v(TAG, "MediaPlayerWrapper onSeekComplete");
        mMediaPlayerWrapperListener.onSeekComplete();
    }

    private int mVideoWidth = 0;
    private int mVideoHeight = 0;

    public int getVideoHeight() {
        return mVideoHeight;
    }

    public int getVideoWidth() {
        return mVideoWidth;
    }

    @Override
    public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
        //BUG - on LG G3 it returns 160 x 160 for m4a even if it's not video!!!
        mVideoWidth = width;
        mVideoHeight = height;
        mMediaPlayerWrapperListener.onVideoSizeChanged(width, height);
    }
}