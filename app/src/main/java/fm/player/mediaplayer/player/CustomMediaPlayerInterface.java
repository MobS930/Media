package fm.player.mediaplayer.player;
import android.content.Context;

/**
 * Created by mac on 13/11/2013.
 */
public interface CustomMediaPlayerInterface {

    /**
     * Set playback speed in range 0.5 - 4.0
     *
     * @param speed
     */
     void setSpeed(float speed);

    /**
     * Get current playback speed
     *
     * @return speed
     */
     float getSpeed();

    /**
     * Enable or disable volume boost
     * @param enable
     */
     void setVolumeBoost(boolean enable);

     boolean isVolumeBoost();

    /**
     * Enable or disable silence skipping
     * @param enable
     */
     void setSilenceSkip(boolean enable);

     boolean isSilenceSkip();

    /**
     * Enable or disable noise reduction
     * @param enable
     */
     void setReduceNoise(boolean enable);

     boolean isReduceNoise();

    /**
     * Prepares the player for playback, asynchronously. After setting the datasource prepareAsync().
     * Method returns immediately, rather than blocking until enough data has been buffered.
     *
     * @throws IllegalStateException if it is called in an invalid state
     */
     void prepareAsync(Context context) throws IllegalStateException;

    /**
     * Gets the current playback position.
     *
     * @return the current position in milliseconds
     */
     int getCurrentPosition();

    /**
     * Gets the duration of the file.
     *
     * @return the duration in milliseconds, if no duration is available (for example, if streaming live content), -1 is returned.
     */
     int getDuration();

    /**
     * Checks whether the MediaPlayer is playing.
     *
     * @return true if currently playing, false otherwise
     * @throws IllegalStateException if the internal player engine has not been initialized or has been released.
     */
     boolean isPlaying() throws IllegalStateException;

    /**
     * Pauses playback. Call start() to resume.
     *
     * @throws IllegalStateException if the internal player engine has not been initialized.
     */
     void pause() throws IllegalStateException;

    /**
     * Releases resources associated with this MediaPlayer object.
     */
     void release();

    /**
     * Resets the MediaPlayer to its uninitialized state. After calling this method, you will have to initialize it again by setting the data source and calling prepareAsync().
     */
     void reset();

    /**
     * Seeks to specified time position.
     *
     * @param msec the offset in milliseconds from the start to seek to
     * @throws IllegalStateException if the internal player engine has not been initialized
     */
     void seekTo(long msec) throws IllegalStateException;

    /**
     * Sets the data source (file-path or http/rtsp URL) to use.
     *
     * @param path  the path of the file, or the http/rtsp URL of the stream you want to play
     * @param local true if we use local file
     * @throws IllegalStateException if it is called in an invalid state
     */
     void setDataSource(String path, boolean local) throws IllegalStateException;

    /**
     * Starts or resumes playback. If playback had previously been paused, playback will continue from where it was paused. If playback had been stopped, or never updateHeader before, playback will start at the beginning.
     *
     * @throws IllegalStateException if it is called in an invalid state
     */
     void start(Context context) throws IllegalStateException;

    /**
     * Stops playback after playback has been stopped or paused.
     *
     * @throws IllegalStateException if the internal player engine has not been initialized.
     */
     void stop() throws IllegalStateException;


    /**
     * Register a callback to be invoked when media player state changes. This is single listener replacement for multiple listeners commented out bellow
     *
     * @param listener the callback that will be run
     */
     void setSpeedMediaPlayerListener(CustomMediaPlayerListener listener);

}
