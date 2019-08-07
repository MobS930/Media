package fm.player.mediaplayer.player;

import java.nio.ByteBuffer;

public class Mpg123Decoder {

    private static final String TAG = "Mpg123Decoder";

    public ByteBuffer buffer;            //will be used in each native function to differentiate between instances

    private native ByteBuffer init(boolean disableFilters);    //must be called before using each instance of this class

    public native long openFile(ByteBuffer handle, int format);

    public native void navFeedSamples(byte[] rawsamples, int rawbytesRead, ByteBuffer handle);

    public native int navOutputSamples(byte[] samples, ByteBuffer handle);

    public native int getNumChannels(ByteBuffer handle);

    public native int getRate(ByteBuffer handle);

    public native int getEncoding(ByteBuffer handle);

    public native void closeFile(ByteBuffer handle);

    public native void flush(ByteBuffer handle);

    public native void setFileEnd(ByteBuffer handle);

    public Mpg123Decoder(int format, boolean disableFilters) {
        buffer = init(disableFilters);
        openFile(buffer, format);
    }

    public Mpg123Decoder(int format) {
        buffer = init(false);
        openFile(buffer, format);
    }
}
