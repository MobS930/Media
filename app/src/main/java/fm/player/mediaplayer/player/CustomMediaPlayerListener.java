package fm.player.mediaplayer.player;

/**
 * Created by mac on 13/11/2013.
 */
 public interface CustomMediaPlayerListener {

    /**
     * Called to update status in buffering a media stream received through progressive HTTP download. The received buffering percentage indicates how much of the content has been buffered or played. For example a buffering update of 80 percent when half the content has already been played indicates that the next 30 percent of the content to play has been buffered.
     *
     * @param mp      the MediaPlayer the update pertains to
     * @param percent the percentage (0-100) of the content that has been buffered or played thus far
     */
     void onBufferingUpdate(CustomMediaPlayer mp, int percent);


    /**
     * Called when the end of a media source is reached during playback.
     *
     * @param mp the MediaPlayer that reached the end of the file
     */
     void onCompletion(CustomMediaPlayer mp);


    /**
     * Called to indicate an error
     *
     * @param mp    the MediaPlayer the error pertains to
     * @param what  the type of error that has occurred:
     * @param extra an extra code, specific to the error. Typically implementation dependent
     * @return boolean True if the method handled the error, false if it didn't. Returning false, or not having an OnErrorListener at all, will cause the OnCompletionListener to be called.
     */
     boolean onError(CustomMediaPlayer mp, int what, int extra);


    /**
     * Called when the media file is ready for playback.
     *
     * @param mp the MediaPlayer that is ready for playback
     */
     void onPrepared(CustomMediaPlayer mp);


    /**
     * Called to indicate the completion of a seek operation.
     *
     * @param mp the MediaPlayer that issued the seek operation
     */
     void onSeekComplete(CustomMediaPlayer mp);

    /**
     * Update back to main thread current playing byte point
     * @param customMediaPlayer sender
     * @param getCurrentBytePoint current byte offset 
     */
	 void onPlaying(CustomMediaPlayer customMediaPlayer,
                    long getCurrentBytePoint);

}
