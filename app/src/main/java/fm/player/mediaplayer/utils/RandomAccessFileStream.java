package fm.player.mediaplayer.utils;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.OkUrlFactory;

import java.io.DataInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

import fm.player.utils.Alog;
import fm.player.utils.OkHttpClientWrapper;

/**
 * This is buggy because of  mData = new byte[(int) mDataSize]; and out of memorry
 */
@Deprecated
public class RandomAccessFileStream extends RandomAccessFile {

    private static final String TAG = RandomAccessFileStream.class.getSimpleName();
    private static final int PAGE_SIZE = 32 * 1024;

    private String mRemoteURL = null;
    private long mPosition = 0;

    private OkHttpClient mHttpclient = OkHttpClientWrapper.getUniqueOkHttpClientNonControledServerInstance();
    private HttpURLConnection mHttpURLConnection;
    private HttpURLConnection mHttpURLConnectionBackground;

    private Thread mThread = null;
    private static int backgroundPage = -1;

    private byte[] mData = null;
    private long mDataSize = 0;
    private boolean[] mPagesLoaded = null;
    private int mPages = 0;
    private String mUserAgent;

    public RandomAccessFileStream(String file, String mode) throws FileNotFoundException {
        super(file, mode);
    }

    public long init(String remote_url, String userAgent) {
        Alog.v(TAG, "init(" + remote_url + ")");
        mRemoteURL = remote_url;
        mUserAgent = userAgent;

        DataInputStream inputStream = executeGet(0, "", false);
        try {
            inputStream.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        //Out of memorry here because dataSize can be very big
        mData = new byte[(int) mDataSize];
        mPages = ((int) mDataSize / PAGE_SIZE) + 1;
        mPagesLoaded = new boolean[mPages];
        for (int i = 0; i < mPages; ++i)
            mPagesLoaded[i] = false;

        // Alog.d(TAG, "  mDataSize: " + mDataSize + " mPages: " + mPages);
        loadPage(0);
        loadPage(1);

        return mDataSize;
    }

    @Override
    public long length() {
        // Alog.v(TAG, "length() " + mDataSize);
        return mDataSize;
    }

    @Override
    public int read() throws IOException {
        // Alog.v(TAG, "read() at " + mPosition);
        return readData();
    }

    @Override
    public int read(byte[] b) throws IOException {
        // Alog.v(TAG, "read([] " + b.length + ") at " + mPosition);
        return readData(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        // Alog.v(TAG, "read([], " + off + ", " + len + ") at " + mPosition);
        return readData(b, off, len);
    }

    @Override
    public long getFilePointer() throws IOException {
        // Alog.v(TAG, "getFilePointer() " + mPosition);
        return mPosition;
    }

    @Override
    public void seek(long pos) throws IOException {
        if (pos == mPosition)
            return;

        // Alog.v(TAG, "seek(" + pos + ") " + mPosition + " -> " + pos + " (" + (pos - mPosition) + ")");
        mPosition = pos;
    }

    @Override
    public int skipBytes(int n) throws IOException {
        if (n <= 0)
            return 0;

        // Alog.v(TAG, "skipBytes(" + n + ")");
        mPosition += n;

        return n;
    }

    @Override
    public void close() throws IOException {
        Alog.v(TAG, "close()");

        if (mHttpURLConnection != null)
            mHttpURLConnection.disconnect();
        if (mHttpURLConnectionBackground != null)
            mHttpURLConnectionBackground.disconnect();
        if (mThread != null) {
            try {
                mThread.join();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        backgroundPage = -1;

        super.close();
    }

    private int readData() {
        checkLoadPages((int) mPosition, (int) mPosition + 1);
        return mData[(int) (mPosition++)] & 0xFF;
    }

    private int readData(byte[] b, int off, int len) {
        checkLoadPages((int) mPosition, (int) mPosition + len);
        System.arraycopy(mData, (int) mPosition, b, off, len);
        mPosition += len;
        return len;
    }

    private void checkLoadPages(int start, int end) {
        int position = start;
        do {
            int page = position / PAGE_SIZE;
            if (mPagesLoaded[page] == false) {
                if (page == backgroundPage) {
                    // Alog.w(TAG, "  Waiting for background page to load (need now)");
                    try {
                        mThread.join();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                } else {
                    loadPage(page);
                }
            }

            position += PAGE_SIZE;
        }
        while (position <= end);

        int wantedBackgroundPage = position / PAGE_SIZE;
        if (wantedBackgroundPage >= mPages || mPagesLoaded[wantedBackgroundPage] == true || backgroundPage != -1)
            return;

        backgroundPage = wantedBackgroundPage;
        // Alog.v(TAG, "  backgroundPage = " + backgroundPage);
        mThread = new Thread
                (
                        new Runnable() {
                            public void run() {
                                loadPage(backgroundPage);
                                backgroundPage = -1;
                                // Alog.v(TAG, "  backgroundPage = -1");
                            }
                        }
                );
        mThread.start();

        // Yield to other thread so it can begin
        try {
            Thread.sleep(1);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void loadPage(int page) {
        // Alog.d(TAG, "loadPage(" + page + ")");
        if (page == -1)
            return;

        int position = page * PAGE_SIZE;
        int size = page == mPages - 1 ? (int) mDataSize - page * PAGE_SIZE : PAGE_SIZE;
        // Alog.d(TAG, "    position: " + position + " size: " + size);

        DataInputStream inputStream = executeGet(position, "" + size, page == backgroundPage);
        try {
            int totalRead = 0;
            while (totalRead < size) {
                int toRead = size - totalRead;
                inputStream.readFully(mData, position + totalRead, toRead);
                totalRead += toRead;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        try {
            inputStream.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        mPagesLoaded[page] = true;
    }

    private DataInputStream executeGet(int position, String size, boolean background) {
        DataInputStream inputStream = null;
        // Alog.d(TAG, "  executeGet(" + position + ", " + size + ", " + background + ")");
        try {
            if (!background) {
                if (mHttpURLConnection != null) mHttpURLConnection.disconnect();
                mHttpURLConnection = new OkUrlFactory(mHttpclient).open(new URL(mRemoteURL));
            } else if (background) {
                if (mHttpURLConnectionBackground != null) mHttpURLConnectionBackground.disconnect();
                mHttpURLConnectionBackground = new OkUrlFactory(mHttpclient).open(new URL(mRemoteURL));
            }
            HttpURLConnection httpUrlConnection = background ? mHttpURLConnectionBackground : mHttpURLConnection;

            if (background)
                mHttpURLConnectionBackground = httpUrlConnection;
            else
                mHttpURLConnection = httpUrlConnection;

            httpUrlConnection.setRequestProperty("User-Agent", mUserAgent);
            if (size.equals(""))
                httpUrlConnection.setRequestProperty("Range", "bytes=" + position + "-");
            else
                httpUrlConnection.setRequestProperty("Range", "bytes=" + position + "-" + (position + Integer.parseInt(size)));
            inputStream = new DataInputStream(httpUrlConnection.getInputStream());

            if (mDataSize == 0)
                mDataSize = httpUrlConnection.getContentLength();
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return inputStream;
    }
}
