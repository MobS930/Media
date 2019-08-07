package fm.player.downloads.spacesaver;

/**
 * Created by mac on 25/10/2017.
 * <p>
 * buffer for decoded data used for compression
 */

public class PcmDataBuffer {

    private static final String TAG = "PcmDataBuffer";


    private byte[] pcmDataBuffer; //buffer
    private int currentReadPosition = 0; //current position in buffer

    public PcmDataBuffer() {
        //default
    }

    /**
     * @return true if there are some data in buffer
     */
    public boolean hasDataInBuffer() {
        if (pcmDataBuffer == null) {
            return false;
        }

        boolean result;
        int bufferSize = pcmDataBuffer.length;
        int availableBytesInBuffer = bufferSize - currentReadPosition;

        result = availableBytesInBuffer > 0;
        return result;
    }

    /**
     * @return buffer size
     */
    public int size() {
        return pcmDataBuffer != null ? pcmDataBuffer.length : 0;
    }

    /**
     * read from this buffer to {@param buffer}
     *
     * @param length num of bytes to read
     * @return how many bytes were read
     */
    public int read(byte buffer[], int length) {
        int bufferSize = pcmDataBuffer.length;
        int availableUnreadBytesInBuffer = bufferSize - currentReadPosition;
        //what is smaller, buffer which we need to fill or unread data in current pcmDataBuffer
        int bytesToWriteCount = Math.min(length, availableUnreadBytesInBuffer);

        //read data from this buffer
        for (int i = 0; i < bytesToWriteCount; i++) {
            buffer[i] = pcmDataBuffer[currentReadPosition];
            //move forward in buffer
            currentReadPosition++;
        }

        return bytesToWriteCount;
    }

    /**
     * write new data to buffer
     *
     * @param buffer new data
     * @param length length of data
     */
    public void write(byte buffer[], int length) {
        //write to buffer
        //this should be safe but just comment in case something is wrong - need to check
        // that all was read before creating new pcmDataBuffer so we don't override data which were not read yet

        pcmDataBuffer = new byte[length];

//        for (int i = 0; i < length; i++) {
//            pcmDataBuffer[i] = buffer[i];
//        }
        System.arraycopy(buffer, 0, pcmDataBuffer, 0, length);

        //reset position
        currentReadPosition = 0;
    }

}
