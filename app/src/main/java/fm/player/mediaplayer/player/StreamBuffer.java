package fm.player.mediaplayer.player;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.TimeoutException;

import fm.player.utils.Alog;

/**
 * This class help to buffer stream by holding it in file buffer
 */
public class StreamBuffer {

    private static final String TAG = StreamBuffer.class.getSimpleName();

    private static final int BUFFER_SIZE = 1024 * 1024 * 15; //15mb

    private static final int BUFFER_READ_TIMEOUT = 15000;//15s, read timeout in case we are reading 0 bytes for long time

    /**
     * File used as stream buffer
     */
    private File bufferFile = null;
    /**
     * File used as stream buffer
     */
    private RandomAccessFile streamBufferFile = null;

    /**
     * Start position of current buffer
     */
    private long startPosition = 0;
    /**
     * Current read position
     */
    private long readPosition = 0;
    /**
     * Current write position(stream read position)
     */
    private long writePosition = 0;

    /**
     * size of stream
     */
    private long streamSize = 0;

    private boolean endReached = false;

    private long readFromBufferZeroFirstOccurrence = 0L;


    /**
     * Fot thread safe buffer operations
     */
    private final Object streamBufferLock = new Object();


    public interface StreamBufferReconnect {
        void tryReconnect();
    }

    public void setStreamSize(long streamSize) {
//        Alog.v(TAG, "setStreamSize: " + streamSize);
        this.streamSize = streamSize;
    }

    /**
     * @param context
     * @param size    length of stream from content length header
     */
    public void init(Context context, long size) {

        Alog.v(TAG, "init: streamSize: " + streamSize);

        readFromBufferZeroFirstOccurrence = 0L;

        streamSize = size;
        //Buffer file
        bufferFile = new File(context.getFilesDir(), "streambuffer");
        if (bufferFile.exists()) {
            bufferFile.delete();
        }
        try {
            boolean created = bufferFile.createNewFile();
            streamBufferFile = new RandomAccessFile(bufferFile, "rw");
            if (!created) {
                Alog.e(TAG, "Can not create stream buffer file at " + context.getFilesDir(), new Exception("Can not create stream buffer file at " + context.getFilesDir()), true);
            }

        } catch (Exception e) {
            Alog.e(TAG, "Can not create stream buffer file at " + context.getFilesDir() + " exception " + e.getMessage(), e, true);
            e.printStackTrace();
        }
    }

    /**
     * Return true if we can use buffer - if buffer file was created
     *
     * @return true if we can use buffer otherwise false
     */
    public boolean isBufferAvailable() {
        return streamBufferFile != null;
    }

    public void setEndReached(boolean endReached) {
        this.endReached = endReached;
    }

    /**
     * Write some data into buffer
     *
     * @param remoteInputStream
     * @param streamBuffer
     * @param callback
     * @return true if some data were added to buffer otherwise false
     * @throws IOException
     */
    public boolean writeToBuffer(InputStream remoteInputStream, byte streamBuffer[],
                                 StreamBufferReconnect callback) throws IOException {

        if (endReached) {
//            Alog.v(TAG, "writeToBuffer: endReached");
            return false;//we reached end of file
        }

        {

            int bufferBytesRead = 0;

            //read stream only if there is buffered less than
            // streamBufferForwardSize(10mb)
//            Alog.v(TAG, "writeToBuffer: writePosition - readPosition < BUFFER_SIZE: " + (writePosition - readPosition < BUFFER_SIZE));
            if (writePosition - readPosition < BUFFER_SIZE) {
                try {
                    bufferBytesRead = remoteInputStream.read(streamBuffer);
                } catch (IOException e) {
                    //catch exeption because we still read from buffer file
                    //need to reconnect hereAlog.e(TAG, "writeToBuffer: ", e);
                    callback.tryReconnect();
                }
                //if writePosition < streamSize it means that it's not end of stream.
                if (bufferBytesRead < 0 && writePosition < streamSize) {
//                    Alog.v(TAG, "writeToBuffer streamSize: " + streamSize + "" +
//                            " writePosition " +
//                            writePosition);
                    callback.tryReconnect();
                } else if (bufferBytesRead == -1) {
                    endReached = true;
                }
            }

//            Alog.v(TAG, "writeToBuffer: writePosition: " + writePosition + " bufferBytesRead: " + bufferBytesRead + " human size: " + FileUtils.humanReadableByteCount(writePosition));

            //write into buffer file
            if (bufferBytesRead > 0) {
                synchronized (streamBufferLock) {
                    if (streamBufferFile.getFilePointer() != writePosition) {
                        streamBufferFile.seek(writePosition);
                    }
                    streamBufferFile.write(streamBuffer, 0, bufferBytesRead);
                    writePosition += bufferBytesRead;
                }
                return true;
            }
            return false;
        }
    }

    /**
     * Read data from buffer
     *
     * @return data or
     * @throws IOException
     */

    public int readFromBuffer(byte rawsamples[]) throws IOException, TimeoutException {

//        if(endReached==false)return 0;

        int rawbytesRead = 0;

//        Alog.v(TAG, "readFromBuffer: writePosition: " + FileUtils.humanReadableByteCount(writePosition) + " readPosition: " + FileUtils.humanReadableByteCount(readPosition)
//                + " streamSize: " + streamSize + " rawsamples.length: " + rawsamples.length);

        //read stream only if there is buffered less than
        // streamBufferForwardSize(10mb)
        if (writePosition <= readPosition && readPosition < streamSize) {
//            Alog.v(TAG, "readFromBuffer writePosition <= readPosition; readPosition: " + readPosition + "" +
//                            " writePosition " +
//                            writePosition
//            );
            checkReadTimeout(0);
            return 0;
        }

        if (writePosition <= readPosition && streamSize == -1) {
            checkReadTimeout(0);
            return 0;
        }

        synchronized (streamBufferLock) {
            if (streamBufferFile.getFilePointer() != readPosition) {
                streamBufferFile.seek(readPosition);
            }
//            Alog.v(TAG, "readFromBuffer: streamBufferFile.read ");
            rawbytesRead = streamBufferFile.read(rawsamples);
            readPosition += rawbytesRead;
        }
        checkReadTimeout(rawbytesRead);
        return rawbytesRead;
    }

    /**
     * timeout read from this buffer if reading 0 bytes for long time which indicates some error
     * save first time of 0 read occurrence and if we read some bytes, reset this time
     *
     * @param rawbytesRead how much bytes we read
     * @throws TimeoutException if 0 bytes read for BUFFER_READ_TIMEOUT time
     */
    private void checkReadTimeout(int rawbytesRead) throws TimeoutException {
        if (rawbytesRead > 0 && readFromBufferZeroFirstOccurrence != 0L) {
            readFromBufferZeroFirstOccurrence = 0L;//reset
            return;
        } else {
            if (readFromBufferZeroFirstOccurrence == 0L) {
                readFromBufferZeroFirstOccurrence = System.currentTimeMillis();//first occurence
            }
        }

        //check if timed-out
        if (readFromBufferZeroFirstOccurrence > 0L &&
                (System.currentTimeMillis() - readFromBufferZeroFirstOccurrence) > BUFFER_READ_TIMEOUT) {
            throw new TimeoutException("StreamBuffer read timeout");
        }
    }


//    @Deprecated
//    public int read(InputStream remoteInputStream, byte streamBuffer[], byte rawsamples[],
//                    StreamBufferReconnect callback) throws IOException {
//
//        int rawbytesRead = 0;
//        int bufferBytesRead = 0;
//
//        //read stream only if there is buffered less than
//        // streamBufferForwardSize(10mb)
//        if (writePosition - readPosition < BUFFER_SIZE) {
//            try {
//                bufferBytesRead = remoteInputStream.read(streamBuffer, 0, streamBuffer.length);
//            } catch (IOException e) {
//                //catch exeption because we still read from buffer file
//                //need to reconnect here
//                callback.tryReconnect();
//            }
//            //if writePosition < streamSize it means that it's not end of stream.
//            if (bufferBytesRead < 0 && writePosition < streamSize) {
//                Alog.v(TAG, "streamSize: " + streamSize + "" +
//                        " writePosition " +
//                        writePosition);
//                callback.tryReconnect();
//            }
//        }
//
//        //write into buffer file
//        if (bufferBytesRead > 0) {
//            streamBufferFile.seek(writePosition);
//            streamBufferFile.write(streamBuffer);
//            writePosition += bufferBytesRead;
//        }
//        streamBufferFile.seek(readPosition);
//        rawbytesRead = streamBufferFile.read(rawsamples, 0, rawsamples.length);
//        readPosition += rawbytesRead;
//
//
//        Alog.v(TAG, "readPosition: " + readPosition + "" +
//                        " writePosition " +
//                        writePosition + " bufferBytesRead: " + bufferBytesRead
//        );
//
//        return rawbytesRead;
//    }


    public long getWritePosition() {
        return writePosition;
    }

    public long getReadPosition() {
        return readPosition;
    }

    public void setWritePosition(long writePosition) {
        synchronized (streamBufferLock) {
            Alog.v(TAG, "setWritePosition: " + writePosition);
            this.writePosition = writePosition;
        }
    }

    public void setReadPosition(long readPosition) {
        synchronized (streamBufferLock) {
            this.readPosition = readPosition;
        }
    }

    public long getStartPosition() {
        return startPosition;
    }

    public void setStartPosition(long startPosition) {
        synchronized (streamBufferLock) {
            this.startPosition = startPosition;
        }
    }

    /**
     * Close and delete file
     */
    public void closeAndCleanup() {
        Alog.v(TAG, "closeAndCleanup: ");
        if (streamBufferFile != null) {
            try {
                streamBufferFile.close();
            } catch (IOException e) {
                Alog.e(TAG, "closeAndCleanup: ", e);
                e.printStackTrace();
            }
            streamBufferFile = null;
        }

        if (bufferFile != null) {
            bufferFile.delete();
            bufferFile = null;
        }

    }
}
