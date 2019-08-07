package fm.player.playback;

/**
 * Created by mac on 20/11/2013.
 */
public interface MediaPlayerWrapperListener {

    public void onBufferingUpdate(int percent);

    public void onCompletion();

    public void onPrepared();

    public void onSeekComplete();

    public void handleError(MediaPlayerWrapper mediaPlayer, int i, int i1, Exception exception);

    public void onVideoSizeChanged(int width, int height);
}
