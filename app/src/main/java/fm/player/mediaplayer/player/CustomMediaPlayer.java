package fm.player.mediaplayer.player;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Handler;
import android.os.Process;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.OkUrlFactory;

import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;
import net.sourceforge.jaad.adts.ADTSDemultiplexer;
import net.sourceforge.jaad.mp4.MP4Container;
import net.sourceforge.jaad.mp4.api.Frame;
import net.sourceforge.jaad.mp4.api.Movie;
import net.sourceforge.jaad.mp4.api.Track;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.concurrent.TimeoutException;

import fm.player.mediaplayer.utils.Mp3Reader;
import fm.player.mediaplayer.utils.RandomAccessFileStream;
import fm.player.utils.Alog;
import fm.player.utils.Constants;
import fm.player.utils.OkHttpClientWrapper;
import fm.player.utils.ReportExceptionHandler;

public class CustomMediaPlayer implements CustomMediaPlayerInterface {

    //this was moved to prepare thread because it was blocking main thread when player was first started
    //    static {
    //        System.loadLibrary("jni-mpg123-sonic");
    //    }

    private static final String TAG = CustomMediaPlayer.class.getSimpleName();
    /**
     * url is passed from activity for streaming in this thread
     */
    private String remote_url = "";
    /**
     * audio player class
     */
    private AudioTrack audioTrack;
    /**
     * interface of native class to decode the stream
     */
    private Mpg123Decoder decoder;
    /**
     * interface of native class to encode the stream
     */
    private Sonic sonic;
    /**
     * this point the remote file for reading stream
     */
    private InputStream remoteInputStream = null;
    /**
     * local file for reading stream
     */
    private RandomAccessFile localFile = null;
    /**
     * File used as stream buffer
     */
    private StreamBuffer streamBuffer = null;


    /**
     * Stream of local file
     */
    private InputStream contentStream = null;

    /**
     * flag of the current thread
     */
    private volatile int status = PlayerStatus.Idle;
    /**
     *
     */
    private volatile long offTimer = 0;
    private volatile int readSamplesTotal = 0;

    /**
     * seeking indicator. > 0 is seeking forward, <0 is seeking back. The value
     * is sent from activity and is checked on a streaming loop to detect when
     * is needed to be seeked.
     */
    private volatile long seekToByte = 0;
    private volatile long seekToMilliseconds = -1;

    /**
     * Indicate that seek is in progress
     */
    private volatile boolean seekToInProgress;

    /**
     * Current position in milliseconds
     */
    private volatile int currentPositionMs;

    /**
     * Initial position in milliseconds. 0 if we are playing from beginning
     */
    private volatile int initPositionMs;

    /**
     * buffer of the reading and writing size. If the buffer is large, it will
     * take more time to read and encode/decode, so sometime we feel the lagging
     * in the stream. smaller buffer size would make it smoother but require
     * more number of reading times.
     */
    private static final int BUFFERSIZE = 1024 * 2; //2kB

    /**
     * the size of the output buffer from the decode. the decode would increase
     * the size of the output buffer, so it often larger than the input buffer.
     */
    private static final int OUTBUFFERSIZE = 2097152; //2MB

    /**
     * connection timeout in milliseconds
     */
    private static final int CONNECTION_TIMEOUT = 15000;//previously 5s

    /**
     * the current location of the local file. used for seeking purpose. this is
     * measure by Bytes. The seek is calculated from bytes -> mseconds or from
     * mseconds -> bytes
     */
    private long totalReadSamples;
    private String mUserAgent = Constants.USER_AGENT_2;
    private OkHttpClient httpclient = OkHttpClientWrapper.getUniqueOkHttpClientNonControledServerInstance();
    private HttpURLConnection mHttpURLConnection;
    private long size = 0;
    private volatile int duration = 0;
    private String range;
    private String fileType;
    private float speed = 1f;
    private CustomMediaPlayerListener listener;
    private Handler handler = new Handler();
    private Thread thPrep;
    private Thread thPlay;

    private boolean mNativeLoaded;

    private enum FileFormat {
        NONE,
        MP3,
        OGG,
        FLAC,
        MP4_AAC,
        ADTS_AAC;

        public int value() {
            switch (this) {
                // Do not change these values, they're passed to JNI
                case MP3:
                    return 1;
                case OGG:
                    return 2;
                case FLAC:
                    return 3;
                case MP4_AAC:
                    return 4;
                case ADTS_AAC:
                    return 5;
                default:
                    return 0;
            }
        }
    }

    private FileFormat fileFormat = FileFormat.NONE;

    // AAC
    File mp4PlaceHolder;
    RandomAccessFileStream randomAccessFileStream = null;
    MP4Container containerMP4AAC = null;
    Track trackAAC = null;

    ADTSDemultiplexer adtsDemultiplexer = null;

    Decoder decoderAAC = null;
    double aacDuration = 0.0;
    int aacSampleRate = -1;
    int aacChannels = -1;

    /**
     * Message which will be sent with handled exception. It should contain details about episode which failed
     */
    private String handledExceptionMessage;

    /**
     * Callback for uncaught exceptions. In case of this exception we need to set state to error
     */
    private ReportExceptionHandler.ReportExceptionHandlerCallback mExceptionHandlerCallback = new ReportExceptionHandler.ReportExceptionHandlerCallback() {
        @Override
        public void onUncaughtException() {
            postError(null, 10011);
        }
    };

    public void setUserAgent(String userAgent) {
        mUserAgent = userAgent;
    }

    public void setHandledExceptionMessage(String handledExceptionMessage) {
        this.handledExceptionMessage = handledExceptionMessage;
    }

    private Long GetSize() {
        return size;
    }

    private void SetSourceType(String fileType) {
        this.fileType = fileType;
    }

    private long milliSecondsToByte(long msec) {
        if (duration == 0)
            return 0;
        Alog.v(TAG, "milliSecondsToByte: " + msec + " size " + size + " offTimer " + offTimer + " duration " + duration + " result: " + ((long) (msec * (size - offTimer) / duration) + offTimer));
        //size - offtimer = actual audio part size, +offtimer is to skip non audio part of file
        return (long) (msec * (size - offTimer) / duration) + offTimer;
    }


    // private void seek(final long offn) {
    // new Thread(new Runnable() {
    //
    // @Override
    // public void run() {
    // synchronized (lock) {
    // offB += milliSecondsToByte(offn);
    // }
    // }
    // }).start();
    // }
    public void setSpeed(float speed) {
        if (speed < 0.1f) return;

        this.speed = speed;
    }

    /**
     * If url is not valid uri - contains some invalid char, we try to fix it
     *
     * @param url
     */
    private static URL sanitateUrl(URL url) {
        /*
         * Example url which need to be sanitized
         * "http://serve.castfire.com/audio/2546948/2546948_2015-09-14-121448.64k.mp3?ad_params=zones%3DPreroll%2CPreroll2%2CMidroll%2CMidroll2%2CMidroll3%2CMidroll4%2CPostroll%2CPostroll2%7Cstation_id%3"
         */
        try {
            URI uri = url.toURI();
            //if uri is not valid it will throw exception
        } catch (URISyntaxException e) {
            e.printStackTrace();
            Alog.addLogMessageError(TAG, "invalid url: " + url.toString() + " message: " + e.getMessage());

            String path = url.getPath();
            String host = url.getHost();
            String protocol = url.getProtocol();
            String query = url.getQuery();
            int port = url.getPort();
            String userInfo = url.getUserInfo();
            String fragment = url.getRef();

            URI uri;
            try {
                uri = new URI(protocol, userInfo, host, port, path, query, fragment);
                url = uri.toURL();
            } catch (MalformedURLException | URISyntaxException e1) {
                e1.printStackTrace();
            }
        }
        return url;
    }

    /**
     * Return true if http response is OK, otherwise false
     *
     * @param url
     * @return
     */
    private void GetRedirectedURL(String url) {

        HttpURLConnection urlConnection = null;
        String location = null;

        Alog.v(TAG, "GetRedirectedURL: from: " + url);

        try {
            //TODO check whether this works ok
            URL fileUrl = new URL(url);
            fileUrl = sanitateUrl(fileUrl);

            urlConnection = new OkUrlFactory(httpclient).open(fileUrl);
            urlConnection.setInstanceFollowRedirects(false);
            urlConnection.addRequestProperty("User-Agent", Constants.USER_AGENT_2);
            // Defeat transparent gzip compression, since it doesn't allow us to
            // easily resume partial downloads.
            urlConnection.setRequestProperty("Accept-Encoding", "identity");
            // Defeat connection reuse, since otherwise servers may continue
            // streaming large downloads after cancelled.
            urlConnection.setRequestProperty("Connection", "close");

//            urlConnection.getResponseCode();

            location = urlConnection.getHeaderField("Location");

        } catch (IOException e) {
            Alog.e(TAG, "GetRedirectedURL: exception", e);
            e.printStackTrace();
        } finally {
            if (urlConnection != null) {
                try {
                    urlConnection.getInputStream().close();
                    urlConnection.disconnect();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        if (!TextUtils.isEmpty(location)) {
            Alog.v(TAG, "GetRedirectedURL " + location);
            remote_url = location;
            GetRedirectedURL(remote_url);
        } else {
            try {
                remote_url = sanitateUrl(new URL(remote_url)).toString();
            } catch (MalformedURLException e) {
                Alog.e(TAG, "GetRedirectedURL: MalformedURLException", e);
                e.printStackTrace();
            }
        }
    }

    // private static void copyFileUsingStream(File source, File dest)
    // throws IOException {
    // dest.deleteOnExit();
    // InputStream is = null;
    // OutputStream os = null;
    // try {
    // is = new FileInputStream(source);
    // os = new FileOutputStream(dest);
    // byte[] buffer = new byte[1024];
    // int length;
    // while ((length = is.read(buffer)) > 0) {
    // os.write(buffer, 0, length);
    // }
    // } finally {
    // is.close();
    // os.close();
    // }
    // }

    private void SetStatus(int st) {
        Alog.v(TAG, "Status changed: " + st);
        this.status = st;
    }

    private void CloseFiles() {
        offTimer = 0;
        try {
            if (audioTrack != null) {
                audioTrack.stop();
            }
        } catch (Exception ex) {
            Alog.v(TAG, "audioTrack is closed");
        } finally {
            if (audioTrack != null) {
                audioTrack.release();
                audioTrack = null;
            }
        }
        if (remoteInputStream != null) {
            try {
                remoteInputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (mHttpURLConnection != null) {
                mHttpURLConnection.disconnect();
            }
            remoteInputStream = null;
        }
        if (localFile != null) {
            try {
                localFile.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            localFile = null;
        }
        if (contentStream != null) {
            try {
                contentStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            contentStream = null;
        }

        if (sonic != null) {
            sonic.close();
            sonic = null;
        }
        Alog.v(TAG, "CloseFiles: Decoder !=null: " + (decoder != null));
        if (decoder != null) {
            Alog.v(TAG, "CloseFiles: Decoder 1");
            decoder.closeFile(decoder.buffer);
            decoder = null;
        }
        if (containerMP4AAC != null || adtsDemultiplexer != null) {
            containerMP4AAC = null;
            adtsDemultiplexer = null;
            trackAAC = null;
            decoderAAC = null;
        }
        if (randomAccessFileStream != null) {
            try {
                randomAccessFileStream.close();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            randomAccessFileStream = null;
        }
        if (mp4PlaceHolder != null) {
            mp4PlaceHolder.delete();
            mp4PlaceHolder = null;
        }

        if (streamBuffer != null) {
            streamBuffer.closeAndCleanup();
            streamBuffer = null;
        }

    }

    private int GetRate() {
        if (fileFormat == FileFormat.MP4_AAC || fileFormat == FileFormat.ADTS_AAC)
            return aacSampleRate;
        else if (decoder != null)
            return decoder.getRate(decoder.buffer);
        else
            return -1;
    }

    private int GetNumChannels() {
        if (fileFormat == FileFormat.MP4_AAC || fileFormat == FileFormat.ADTS_AAC)
            return aacChannels;
        else if (decoder != null)
            return decoder.getNumChannels(decoder.buffer);
        else
            return -1;
    }

    private int GetEncoding() {
        // return 16;
        if (fileFormat == FileFormat.MP4_AAC || fileFormat == FileFormat.ADTS_AAC)
            return 208;
        else if (decoder != null && decoder.getEncoding(decoder.buffer) != -1)
            return decoder.getEncoding(decoder.buffer);
        else
            return -1;
    }

    private long GetCurrentBytePoint() {
        return totalReadSamples;
    }

    private long GetOffTimer(long secToByte) {

        //offtimer is sometimes too big and causing wrong current position(time is slower),
        // if we dont use offtimer its also causing wrong current position(time dont start from 0:00)
        // we need to make compromis and modify offtimer. Closer to beginning is bigger offtimer, closer to end is smaller offtimer
        // problem is caused because decoder need some data before it starts play.
        // data size is different for every mp3 and if its a lot of data offtimer is big and causing position problems

        //how much % from size offtimer is
//        long offtimerPercentage = offTimer * 100 / size;
//        long bytesAheadOfftimer = secToByte - offTimer;
//
//        if (bytesAheadOfftimer < offTimer * 2) {
//            return (100 - ((bytesAheadOfftimer) * 100 / size)) * offTimer / 100;
//        }
//        Alog.v(TAG, "offTimer: " + offTimer);

//        if (offTimer > 1024 * 8) {
//            //it doesnt completly solve problem but it makes it nicer
//            long calculatedOfftime = size > 0 ? (100 - (secToByte * 100 / size)) * offTimer / 100 : 0;
//            if (calculatedOfftime > 1024 * 8) {
//                return calculatedOfftime;
//            } else {
//                return 2048;
//            }
//        }

        return offTimer;
    }

//    private void SeekToByte(long toByte, long milliseconds) {
//
//        if (duration != 0) {
//            seekToByte = toByte;
//            seekToMilliseconds = milliseconds;
//        }
//
//        Alog.v(TAG, "currentPositionMs seekToByte: " + seekToByte + " toByte: " + toByte + " duration: " + duration + " size: " + size);
//
//        // (long) (offbyte - (GetCurrentBytePoint()
//        // - GetOffTimer()) / ((float) (size /
//        // duration)));
//    }

    @Override
    public float getSpeed() {
        return speed;
    }

    private boolean mVolumeBoost;
    private boolean mSkipSilence;
    private boolean mReduceNoise;


    @Override
    public void setVolumeBoost(boolean enable) {
        mVolumeBoost = enable;
        if (mNativeLoaded)
            setVolumeBoostNative(enable);
    }

    @Override
    public boolean isVolumeBoost() {
        if (!mNativeLoaded) return mVolumeBoost;
        return isVolumeBoostNative();
    }

    @Override
    public void setSilenceSkip(boolean enable) {
        mSkipSilence = enable;
        if (mNativeLoaded)
            setSilenceSkipNative(enable);
    }

    @Override
    public boolean isSilenceSkip() {
        if (!mNativeLoaded) return mSkipSilence;
        return isSilenceSkipNative();
    }

    @Override
    public void setReduceNoise(boolean enable) {
        mReduceNoise = enable;
        if (mNativeLoaded)
            setReduceNoiseNative(enable);
    }

    @Override
    public boolean isReduceNoise() {
        if (!mNativeLoaded) return mReduceNoise;
        return isReduceNoiseNative();
    }

    private static boolean exists(String URLName) {
        boolean exists = false;
        HttpURLConnection con = null;
        Alog.v(TAG, "exists check: " + URLName);
        try {
            HttpURLConnection.setFollowRedirects(false);
            con = new OkUrlFactory(OkHttpClientWrapper.getUniqueOkHttpClientNonControledServerInstance()).open(new URL(URLName));
//            con = (HttpURLConnection) new URL(URLName).openConnection();
            con.setRequestMethod("HEAD");
            exists = con.getResponseCode() == HttpURLConnection.HTTP_OK;
            Alog.v(TAG, "exists response code: " + con.getResponseCode());
        } catch (Exception e) {
            e.printStackTrace();
            exists = false;
        } finally {
            if (con != null) {
                try {
                    con.getInputStream().close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        Alog.v(TAG, "exists result: " + exists);
        return exists;
    }

    @Override
    public void prepareAsync(final Context context) throws IllegalStateException {
        if (status == PlayerStatus.Idle || status == PlayerStatus.Started || status == PlayerStatus.Prepared
                || status == PlayerStatus.Paused || status == PlayerStatus.PlaybackCompleted || status == PlayerStatus.Error) {
            throw new IllegalStateException(
                    "speedPlayer prepareAsync() called in wrong state. state: " + status

            );
        }
        if (status != PlayerStatus.Initialized && status != PlayerStatus.Stopped) {
            return;
        }

        SetStatus(PlayerStatus.Preparing);

        thPrep = new Thread(new Runnable() {

            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

                System.loadLibrary("ogg");
                System.loadLibrary("vorbis");
                System.loadLibrary("flac");
                System.loadLibrary("jni-mpg123-sonic");

                mNativeLoaded = true;
                setVolumeBoostNative(mVolumeBoost);
                setSilenceSkipNative(mSkipSilence);
                setReduceNoiseNative(mReduceNoise);

                if (fileType.equals("remote")) {
                    GetRedirectedURL(remote_url);

                    if (exists(remote_url) == false) {
                        postError(null, 10010);
                        return;
                    }
                }


                String mimeType = null;
                Mp3Reader.MetaData metaData = null;
                if ("content".equals(fileType)) {
                    metaData = Mp3Reader.getMetaData(context, mContentUri);
                } else {
                    metaData = Mp3Reader.getMetaData(remote_url);
                }

                duration = metaData.duration;
                mimeType = metaData.mimeType;

                if (duration <= 0) {
                    //error
                    postError(new Exception("episode duration is 0"), 50001);
                    return;
                }
                if (mimeType != null && mimeType.contains("video")) {
                    postError(new Exception("episode is video"), 30015);
                    return;
                }

                if (fileFormat == FileFormat.NONE) {
                    //figure file format
                    fileFormat = formatFromMimeType(mimeType);
                }


                Alog.d(TAG, "duration = " + duration);
                if (fileFormat == FileFormat.ADTS_AAC) {
                    aacDuration = (float) duration / 1000.0f;
                }

                if (status == PlayerStatus.Stopped || status == PlayerStatus.End) {
                    return;
                }


                if (status == PlayerStatus.Started) {
                    while (true) {
                        Alog.v(TAG, "waiting for closing audio");
                        stop();
                        //todo majovv - add if || status == PlayerStatus.Error
                        if (status == PlayerStatus.Stopped || status == PlayerStatus.End) {
                            break;
                        } else {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                SetStatus(PlayerStatus.Error);
                                e.printStackTrace();
                            }
                        }
                    }
                }
                CloseFiles();

                //                //wait until another thread will finish files because it sometimes close decoder!
                //                //
                //                try {
                //                    Thread.sleep(300);
                //                } catch (InterruptedException e) {
                //                    e.printStackTrace();
                //                }

                totalReadSamples = 0;

                Alog.v(TAG, "file: " + remote_url);
                if (fileType.equals("content")) {

                    //todo majovv - cursor not closed
                    Cursor returnCursor =
                            context.getContentResolver().query(mContentUri, null, null, null, null);
    /*
     * Get the column indexes of the data in the Cursor,
     * move to the first row in the Cursor, get the data,
     * and display it.
     */

                    String name = null;
                    if (returnCursor != null && returnCursor.moveToFirst()) {
                        int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        int sizeIndex = returnCursor.getColumnIndex(OpenableColumns.SIZE);
                        name = returnCursor.getString(nameIndex);
                        size = returnCursor.getLong(sizeIndex);
                    } else {
                        try {
                            AssetFileDescriptor fd = context.getContentResolver().openAssetFileDescriptor(mContentUri, "r");
                            size = fd.getLength();
                            fd.close();
                            name = mContentUri.toString();

                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                    if (returnCursor != null) {
                        returnCursor.close();
                    }

                    if (fileFormat == FileFormat.NONE) {
                        fileFormat = formatFromPath(name);
                        Alog.v(TAG, "File format: " + fileFormat);
                    }

                    // if failed to get type from path too, try to resolve from uri
                    // happens when picked file with StorageAccessFramework and select 'Audio' is side menu
                    if (fileFormat == FileFormat.NONE) {
                        //Check uri format to avoid null
                        final String scheme = mContentUri.getScheme();
                        if (scheme != null && scheme.equals(ContentResolver.SCHEME_CONTENT)) {
                            //If scheme is a content
                            final MimeTypeMap mime = MimeTypeMap.getSingleton();
                            String type = context.getContentResolver().getType(mContentUri);
                            String extension = mime.getExtensionFromMimeType(type);
                            fileFormat = formatFromPath(name + "." + extension);
                            Alog.v(TAG, "File format from uri: " + fileFormat);
                        }
                    }

                    try {
                        contentStream = context.getContentResolver().openInputStream(mContentUri);
                        if (fileFormat == FileFormat.ADTS_AAC) {
                            adtsDemultiplexer = new ADTSDemultiplexer(contentStream);
                            decoderAAC = new Decoder(adtsDemultiplexer.getDecoderSpecificInfo());
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                        postError(e, 10999);
                        return;
                    }

                    if (status == PlayerStatus.Stopped || status == PlayerStatus.End) {
                        return;
                    }

                } else if (fileType.equals("local")) {
                    try {
                        if (fileFormat == FileFormat.ADTS_AAC) {
                            try {
                                adtsDemultiplexer = new ADTSDemultiplexer(new FileInputStream(remote_url));
                                decoderAAC = new Decoder(adtsDemultiplexer.getDecoderSpecificInfo());
                            } catch (Exception ex) {
                                ex.printStackTrace();
                                postError(ex, 10016);
                                return;
                            }
                        }

                        localFile = new RandomAccessFile(remote_url, "r");
                        try {
                            size = localFile.length();
                        } catch (IOException e) {
                            e.printStackTrace();
                            postError(e, 10003);
                            return;
                        }
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                        postError(e, 10004);
                        return;
                    }
                } else {
                    if (fileFormat == FileFormat.MP4_AAC) {
                        try {
                            String placeholderFile = context.getApplicationInfo().dataDir + "/temp.txt";
                            mp4PlaceHolder = new File(placeholderFile);
                            FileOutputStream fileOut = new FileOutputStream(mp4PlaceHolder);
                            fileOut.write(0);
                            fileOut.flush();
                            fileOut.close();

                            randomAccessFileStream = new RandomAccessFileStream(placeholderFile, "r");
                            size = randomAccessFileStream.init(remote_url, mUserAgent);
                        } catch (Exception ex) {
                            postError(ex, 20014);
                            return;
                        }
                    } else {
                        try {
                            if (status == PlayerStatus.Stopped || status == PlayerStatus.End) {
                                return;
                            }

                            mHttpURLConnection = new OkUrlFactory(httpclient).open(new URL(remote_url));
                            mHttpURLConnection.setRequestProperty("User-Agent", mUserAgent);
                            mHttpURLConnection.setRequestProperty("Range",
                                    "bytes=" + totalReadSamples + "-");    //continue!!!
                            // Defeat transparent gzip compression, since it doesn't allow us to
                            // easily resume partial downloads.
                            mHttpURLConnection.setRequestProperty("Accept-Encoding", "identity");
                            // Defeat connection reuse, since otherwise servers may continue
                            // streaming large downloads after cancelled.
                            mHttpURLConnection.setRequestProperty("Connection", "close");
                            mHttpURLConnection.setConnectTimeout(CONNECTION_TIMEOUT);
                            mHttpURLConnection.setReadTimeout(CONNECTION_TIMEOUT);
                            //todo majovv
                            remoteInputStream = mHttpURLConnection.getInputStream();
                            size = getContentLengthLong(mHttpURLConnection);

                            if (fileFormat == FileFormat.ADTS_AAC) {
                                adtsDemultiplexer = new ADTSDemultiplexer(remoteInputStream);
                                decoderAAC = new Decoder(adtsDemultiplexer.getDecoderSpecificInfo());
                            } else {
                                //setup buffer
                                streamBuffer = new StreamBuffer();
                                streamBuffer.init(context, size);
                            }

                            if (status == PlayerStatus.Stopped || status == PlayerStatus.End) {
                                return;
                            }

                        } catch (IOException e2) {
                            e2.printStackTrace();
                            continueBufferingInputStream(context, totalReadSamples, "thPrep IOException e2");
                        }
                    }
                }

                decoder = new Mpg123Decoder(fileFormat.value());
                Alog.v(TAG, "run: newDecoder");


                if (fileFormat == FileFormat.MP4_AAC) {
                    Alog.v(TAG, "Opening MP4Container");

                    try {
                        if (fileType.equals("content")) {
                            containerMP4AAC = new MP4Container(contentStream);
                        } else if (fileType.equals("local")) {
                            containerMP4AAC = new MP4Container(localFile);
                        } else {
                            containerMP4AAC = new MP4Container(randomAccessFileStream);
                        }
                        Alog.v(TAG, "  containerMP4AAC created");

                        Movie movie = containerMP4AAC.getMovie();
                        Alog.v(TAG, "  got Movie");

                        List<Track> tracks = movie.getTracks(net.sourceforge.jaad.mp4.api.Type.VIDEO);
                        Alog.v(TAG, "  got " + tracks.size() + " movie tracks");

                        aacDuration = movie.getDuration();
                        duration = (int) (1000.0 * aacDuration);
                        Alog.d(TAG, "duration = " + duration);
                        tracks = movie.getTracks(net.sourceforge.jaad.mp4.api.AudioTrack.AudioCodec.AAC);
                        Alog.v(TAG, "  got " + tracks.size() + " AAC tracks");
                        if (tracks.size() > 0) {
                            Alog.v(TAG, "  Creating AAC decoder  duration: " + aacDuration);
                            trackAAC = tracks.get(0);
                            decoderAAC = new Decoder(trackAAC.getDecoderSpecificInfo());
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        postError(ex, 30014);
                        return;
                    }
                } else if (fileFormat == FileFormat.ADTS_AAC) {
                }

                handler.post(new Runnable() {

                    @Override
                    public void run() {
                        if (listener != null) {
                            if (status == PlayerStatus.Stopped || status == PlayerStatus.End || status == PlayerStatus.Error) {
                                return;
                            }
                            SetStatus(PlayerStatus.Prepared);
                            listener.onPrepared(CustomMediaPlayer.this);
                        }
                    }
                });


//                int bufferSize = 1024 * 16; //16kB seems to be optimal buffer size
                byte buffer[] = new byte[4096];
                if (!fileType.equals("content") && !fileType.equals("local") && CustomMediaPlayer.this.streamBuffer != null && CustomMediaPlayer.this.streamBuffer.isBufferAvailable()) { //if its paused but not local we can still read some data
                    while (status == PlayerStatus.Preparing || status == PlayerStatus.Prepared
                            || status == PlayerStatus.Started || status == PlayerStatus.Paused) {
                        boolean dataAddedToBuffer = false;
                        try {
                            dataAddedToBuffer = CustomMediaPlayer.this.streamBuffer
                                    .writeToBuffer(remoteInputStream, buffer,
                                            new StreamBuffer.StreamBufferReconnect() {
                                                @Override
                                                public void tryReconnect() {
                                                    continueBufferingInputStream(context,
                                                            CustomMediaPlayer.this.streamBuffer
                                                                    .getWritePosition(), false, "thPrep buffer tryReconnect"
                                                    );
                                                }
                                            }
                                    );
                            if (dataAddedToBuffer) {
                                postBufferUpdate(
                                        CustomMediaPlayer.this.streamBuffer.getWritePosition());
                            }
                        } catch (IOException e) {
                            //todo majovv
                            Alog.e(TAG, "writeToBuffer exception ", e);
                            //if we write stream into buffer we reopen stream at write position
                            continueBufferingInputStream(context,
                                    CustomMediaPlayer.this.streamBuffer.getWritePosition(), "thPrep buffer IOException");
                        }
                        if (!dataAddedToBuffer) {
                            try {
                                Thread.sleep(300);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        });
        thPrep.setName("CustomMediaPlayer.PrepareThread");
        thPrep.setUncaughtExceptionHandler(
                new ReportExceptionHandler(context, "prepareAsync() " + handledExceptionMessage,
                        mExceptionHandlerCallback, false)
        );
        thPrep.start();
    }

//    @Override
//    public int getCurrentPosition() {
////     old code - based on byte position
////        int result = 0;
////        if (seekToByte > 0) {
////            result = (int) (((seekToByte - GetOffTimer(seekToByte))) / (((float) size / (float) duration)));
////        } else {
////            long first = (((GetCurrentBytePoint() - GetOffTimer(totalReadSamples))));
////            float sec = ((float) size / (float) duration);
////            result = (int) (first / sec);
////        }
////
////        return result < 0 ? 0 : result;
//
//
//        //
//        //There is no reason for getCurrentPosition to return 0 if player is in wrong state.
//        //It should return last valid. getCurrentPosition returning 0 caused some random bugs so this should be more stable
//        //
//        if (duration != 0 && size != 0) {
//
//            if (audioTrack != null) {
//
//                int positionInTrack = seekToInProgress ? 0 : (audioTrack.getPlaybackHeadPosition() / audioTrack.getSampleRate()) * 1000;
//
//                currentPositionMs = initPositionMs + positionInTrack;
//
//                Alog.v(TAG, "currentPositionMs = " + initPositionMs + " + " + positionInTrack);
//
//            }
//
//        }
//        Alog.v(TAG, "currentPositionMs " + currentPositionMs);
//        return currentPositionMs;
//    }


    @Override
    public int getCurrentPosition() {
        //
        //There is no reason for getCurrentPosition to return 0 if player is in wrong state.
        //It should return last valid. getCurrentPosition returning 0 caused some random bugs so this should be more stable
        //
        if (duration != 0 && size != 0) {
            //to return correct current position when onSeekComplete but it's not playing(so totalReadSamples is still 0)

//            if ((status == PlayerStatus.Prepared) && seekToByte > 0) {

            int result = 0;
            if (seekToInProgress) {
                result = (int) seekToMilliseconds;
//                    result = (int) (((seekToByte - GetOffTimer(seekToByte))) / ((((float) size - GetOffTimer(seekToByte)) / (float) duration)));
//                }
            } else {
                long first = (((GetCurrentBytePoint() - GetOffTimer(totalReadSamples))));
                float sec = (((float) size - GetOffTimer(seekToByte)) / (float) duration);
                result = (int) (first / sec);
            }

            return result < 0 ? 0 : result;

        }

        return 0;
    }

    @Override
    public int getDuration() {

        if (status == PlayerStatus.Idle || status == PlayerStatus.Initialized || status == PlayerStatus.Error) {
            SetStatus(PlayerStatus.Error);
            return 0;
        }
        if (status != PlayerStatus.Prepared && status != PlayerStatus.Started && status != PlayerStatus.Paused && status != PlayerStatus.Stopped && status != PlayerStatus.PlaybackCompleted) {
            return 0;
        }

        if (status == PlayerStatus.Idle || status == PlayerStatus.Initialized || status == PlayerStatus.Error) {
            SetStatus(PlayerStatus.Error);
            return -1;
        }
        return duration;
    }

    @Override
    public boolean isPlaying() throws IllegalStateException {
        if (status == PlayerStatus.Error) {
            return false;
        }

        if (status != PlayerStatus.Idle && status != PlayerStatus.Initialized && status != PlayerStatus.Prepared && status != PlayerStatus.Started && status != PlayerStatus.Paused && status != PlayerStatus.Stopped && status != PlayerStatus.PlaybackCompleted) {
            return false;
        }

        if (status == PlayerStatus.Started) {
            return true;
        }

        return false;
    }

    @Override
    public void pause() throws IllegalStateException {
        if (status == PlayerStatus.Idle || status == PlayerStatus.Initialized || status == PlayerStatus.Prepared || status == PlayerStatus.Stopped || status == PlayerStatus.Error) {
            SetStatus(PlayerStatus.Error);
            return;
        }
        if (status != PlayerStatus.Started && status != PlayerStatus.Paused && status != PlayerStatus.PlaybackCompleted) {
            return;
        }
        SetStatus(PlayerStatus.Paused);
    }

    @Override
    public void release() {
        /**
         * The function is not implemented. Because of complicated process of
         * the feed streaming, closing stream and release are handled at a same
         * time.
         *
         * There are two things needed to terminate to release the session. data
         * streaming and encoder streaming. data streaming is httpclient object
         * in this class, it is ended when CloseFiles function is called. the
         * same for encoder streaming is also called when CloseFiles is called.
         *
         * So release is exactly the same as CloseFile. But the function is not
         * threadsafe, in order to terminate it as threadSafe, call stop
         * function instead to make sure that the current stream data flushed
         * before the stream is terminated.
         *
         * the threadsafe callback of "stop" is onCompletion
         */
        //set listener to null. We don't expect callbacks to be called after release
        listener = null;
        stop();
        SetStatus(PlayerStatus.End);
    }

    @Override
    public void reset() {

        if (status != PlayerStatus.Idle && status != PlayerStatus.Initialized && status != PlayerStatus.Prepared
                && status != PlayerStatus.Started && status != PlayerStatus.Paused && status != PlayerStatus.Stopped
                && status != PlayerStatus.PlaybackCompleted && status != PlayerStatus.Error) {
            return;
        }

        size = 0;
        duration = 0;
        remote_url = "";
        stop();
        //this was called on main thread so disabling it for now. Keeping it here just as info and it can be deleted later
//        if (thPrep != null) {
//            while (thPrep.isAlive() == true) {
//                try {
//                    Thread.sleep(100);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
//            }
//        }
//        if (thPlay != null) {
//            while (thPlay.isAlive() == true) {
//                try {
//                    Thread.sleep(100);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
//            }
//        }
        SetStatus(PlayerStatus.Idle);
    }

    @Override
    public void seekTo(long msec) throws IllegalStateException {
        if (status == PlayerStatus.Idle || status == PlayerStatus.Initialized || status == PlayerStatus.Stopped || status == PlayerStatus.Error) {
            SetStatus(PlayerStatus.Error);
            return;
        }
        if (status != PlayerStatus.Prepared && status != PlayerStatus.Started && status != PlayerStatus.Paused && status != PlayerStatus.PlaybackCompleted) {
            return;
        }

        if (msec < 0) {
            msec = 0;
        }

        currentPositionMs = initPositionMs = (int) msec;
        Alog.v(TAG, "seekTo currentPositionMs = initPositionMs = msec = " + initPositionMs);

        seekToInProgress = true;

//        long secToByte = milliSecondsToByte(msec);
//        Alog.v(TAG, "seekTo from " + GetCurrentBytePoint() + " to secToByte: " + secToByte + " getOffTimer: " + GetOffTimer(secToByte));
        if (duration != 0) {
            seekToMilliseconds = msec;
        }

        Alog.v(TAG, "currentPositionMs seekToByte: " + seekToByte + " duration: " + duration + " size: " + size);
        //TODO seek to shouln't call callback immediately

        handler.post(new Runnable() {

            @Override
            public void run() {
                if (listener != null)
                    listener.onSeekComplete(CustomMediaPlayer.this);
            }
        });
    }

    private Uri mContentUri;

    public void setDataSource(Context context, Uri contentUri) {

        if (status == PlayerStatus.Initialized || status == PlayerStatus.Prepared || status == PlayerStatus.Started || status == PlayerStatus.Paused || status == PlayerStatus.Stopped || status == PlayerStatus.PlaybackCompleted || status == PlayerStatus.Error) {
            SetStatus(PlayerStatus.Error);
            return;
        }
        if (status != PlayerStatus.Idle) {
            return;
        }

        String scheme = contentUri.getScheme();
        if (scheme == null || scheme.equals("file")) {
            setDataSource(contentUri.getPath(), true);
            return;
        }

        if (!"content".equals(contentUri.getScheme())) {
            throw new IllegalArgumentException("Scheme is not CONTENT, uri: " + contentUri);
        }

        mContentUri = contentUri;


        SetStatus(PlayerStatus.Initialized);
        SetSourceType("content");
    }

    @Override
    public void setDataSource(String path, boolean local) throws IllegalStateException {
        if (status == PlayerStatus.Initialized || status == PlayerStatus.Prepared || status == PlayerStatus.Started || status == PlayerStatus.Paused || status == PlayerStatus.Stopped || status == PlayerStatus.PlaybackCompleted || status == PlayerStatus.Error) {
            SetStatus(PlayerStatus.Error);
            return;
        }
        if (status != PlayerStatus.Idle) {
            return;
        }

        fileFormat = formatFromPath(path);
        Alog.v(TAG, "File format: " + fileFormat);

        SetStatus(PlayerStatus.Initialized);
        remote_url = path;
        if (local) {
            SetSourceType("local");
        } else {
            SetSourceType("remote");
        }
    }

    @Override
    public void start(final Context context) throws IllegalStateException {
        if (status == PlayerStatus.Initialized || status == PlayerStatus.Idle || status == PlayerStatus.Stopped || status == PlayerStatus.Error) {
            String statusStr = " " + status;

            Exception ex = new IllegalStateException(
                    "speedPlayer start() called in wrong state. state: " + statusStr);
            ReportExceptionHandler.reportHandledException("speed player start called in wrong state: " + statusStr, ex);
//            throw new IllegalStateException(
//                    "speedPlayer start() called in wrong state. state: " + statusStr);
            postError(ex, 10050);
            return;
        }
        if (status != PlayerStatus.Prepared && status != PlayerStatus.Started && status != PlayerStatus.Paused && status != PlayerStatus.PlaybackCompleted) {
            return;
        }

        if (status == PlayerStatus.Paused) {
            SetStatus(PlayerStatus.Started);
            return;
        }
        if (status == PlayerStatus.Started) {
            return;
        }

        SetStatus(PlayerStatus.Started);
        thPlay = new Thread(new Runnable() {

            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

                byte rawsamples[] = new byte[BUFFERSIZE];
                int rawbytesRead = 0;
                totalReadSamples = 0;
                int readSamples = 0;
                byte modifiedSamples[] = new byte[1];
                byte[] samples = new byte[OUTBUFFERSIZE];
                int available = 0;

                while (true) {
                    // TODO when I add || status == PlayerStatus.PlaybackCompleted
                    // it will not loop forever on offline file but it will drop last 0.5second
                    if (status == PlayerStatus.Stopped || status == PlayerStatus.End || status == PlayerStatus.Error) {
                        break;
                    }
                    if (status == PlayerStatus.Paused) {
                        try {
                            if (audioTrack != null && audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PAUSED) {
                                audioTrack.pause();
                            }
                            Thread.sleep(300);
                        } catch (InterruptedException e) {
                            SetStatus(PlayerStatus.Error);
                            e.printStackTrace();
                        }
                        continue;
                    } else if (status == PlayerStatus.Started) {
                        if (audioTrack != null && audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                            audioTrack.play();
                        }
                    }

                    // Sonic buffer can empty out when skipping large areas of silence (or silence right at the start of the episode)
                    // So keep reading data from source (and through mpg123 and sonic) until buffer is re-filled
                    // 27648 was chosen because it's the smallest typical value when player is running normally
                    available = 0;
                    // If we fail to read new data out of sonic (available == lastAvailable)
                    // don't re-enter the loop, so that we never loop infinitely
                    int lastAvailable = -1;
                    // Variables to break or continue when we exit the "while (available < 27648"
                    // they should affect the "while (true)" instead
                    boolean continueAfterWhileLoop = false;
                    boolean breakAfterWhileLoop = false;
                    while (available < 27648 && available != lastAvailable) {

                        lastAvailable = available;

                        if (fileFormat == FileFormat.MP4_AAC || fileFormat == FileFormat.ADTS_AAC) {
                            try {
                                Alog.v(TAG, "readNextFrame");
                                byte[] frameAAC = null;
                                if (fileFormat == FileFormat.MP4_AAC) {
                                    Frame frame = trackAAC.readNextFrame();
                                    if (frame == null) {
                                        decoder.setFileEnd(decoder.buffer);
                                        readSamples = decoder.navOutputSamples(samples, decoder.buffer);
                                        if (readSamples > 0) {
                                            sonic.putBytes(samples, readSamples);
                                        }
                                        available = sonic.availableBytes();

                                        SetStatus(PlayerStatus.PlaybackCompleted);
                                        Alog.v(TAG, "frame == null");
                                        breakAfterWhileLoop = true;
                                        break;
                                    }

                                    frameAAC = frame.getData();
                                } else {
                                    try {
                                        frameAAC = adtsDemultiplexer.readNextFrame();
                                    } catch (Exception ex) {
                                        frameAAC = null;
                                    }
                                }

                                if (frameAAC == null) {
                                    decoder.setFileEnd(decoder.buffer);
                                    readSamples = decoder.navOutputSamples(samples, decoder.buffer);
                                    if (readSamples > 0) {
                                        sonic.putBytes(samples, readSamples);
                                    }
                                    available = sonic.availableBytes();

                                    SetStatus(PlayerStatus.PlaybackCompleted);
                                    Alog.v(TAG, "frameAAC == null");
                                    breakAfterWhileLoop = true;
                                    break;
                                }

                                Alog.v(TAG, "  decodeFrame");
                                totalReadSamples += frameAAC.length;

                                SampleBuffer sampleBuffer = new SampleBuffer();
                                decoderAAC.decodeFrame(frameAAC, sampleBuffer);

                                aacSampleRate = sampleBuffer.getSampleRate();
                                aacChannels = sampleBuffer.getChannels();
                                sampleBuffer.setBigEndian(false);

                                rawsamples = sampleBuffer.getData();
                                rawbytesRead = rawsamples.length;
                                if (rawbytesRead > 0) {
                                    decoder.navFeedSamples(rawsamples, rawbytesRead, decoder.buffer);
                                    readSamples = decoder.navOutputSamples(samples, decoder.buffer);
                                }

                                // Alog.v(TAG, "  Read: " + readSamples + " SampleRate: " + aacSampleRate + " Channels: " + aacChannels + " BitsPerSample: " + sampleBuffer.getBitsPerSample();
                            } catch (Exception ex) {
                                postError(ex, 30001);
                                breakAfterWhileLoop = true;
                                continue;
                            }
                        } else {
                            if (fileType.equals("content")) {
                                try {
                                    rawbytesRead = contentStream.read(rawsamples, 0, BUFFERSIZE);
                                    totalReadSamples += rawbytesRead;
                                    postBufferUpdate(size);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            } else if (fileType.equals("local")) {
                                try {
//                                    Alog.v(TAG, "currentPositionMs before read localFile pointer: " + localFile.getFilePointer());
                                    rawbytesRead = localFile.read(rawsamples, 0, BUFFERSIZE);
//                                    Alog.v(TAG, "currentPositionMs localFile pointer: " + localFile.getFilePointer());
                                    totalReadSamples += rawbytesRead;
                                    postBufferUpdate(localFile.length());
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }

                            } else {

                                //read into buffer file
                                if (CustomMediaPlayer.this.streamBuffer != null && CustomMediaPlayer.this.streamBuffer.isBufferAvailable()) {
                                    try {
                                        rawbytesRead = streamBuffer.readFromBuffer(rawsamples);
                                        //todo majovv
//                                        Alog.v(TAG, "streamBuffer.readFromBuffer rawbytesRead: " + rawbytesRead);
                                        if (rawbytesRead == 0) {
                                            //no data in buffer
//                                            Alog.v(TAG, "readFromBuffer rawbytesread: " + rawbytesRead + " available: " + available);

                                            // to avoid infinite loop, there is read timeout for streamBuffer
                                            // handled in catch block below
                                            continueAfterWhileLoop = true;
                                            break;
                                        } else {
                                            totalReadSamples += rawbytesRead;
                                        }

                                    } catch (IOException e) {
                                        Alog.e(TAG, "run: streamBuffer.readFromBuffer exception", e);
                                        //if we write stream into buffer we reopen stream at write position
                                        continueBufferingInputStream(context,
                                                CustomMediaPlayer.this.streamBuffer.getWritePosition(),
                                                "thPlay isBufferAvailable IOException");
                                    } catch (TimeoutException e2) {
                                        // streamBuffer contains read timeout to avoid infinite loop
                                        // connection lost while buffering caused infinite loop in try block above
                                        Alog.addLogMessage(TAG, "streamBuffer.readFromBuffer ERROR TIMEOUT");
                                        postError(e2, 30001);
                                        continueAfterWhileLoop = false;
                                        breakAfterWhileLoop = true;
                                        break;
                                    }
                                } else {
                                    try {
                                        //buffer files doesn't exist so stream directly
                                        rawbytesRead = remoteInputStream.read(rawsamples, 0, BUFFERSIZE);
                                        totalReadSamples += rawbytesRead;
                                        postBufferUpdate(totalReadSamples);

                                    } catch (IOException e) {
                                        continueBufferingInputStream(context, totalReadSamples, "thPlay NOT isBufferAvailable IOException");
                                    }
                                }
                            }


                            if (rawbytesRead < 0) {
                                SetStatus(PlayerStatus.PlaybackCompleted);
                                Alog.v(TAG, "Completed rawbytesread: " + rawbytesRead);
                                decoder.setFileEnd(decoder.buffer);
                                breakAfterWhileLoop = true;
                                // NOTE: Don't break here because anymore
                                //       external/internal buffers of FLAC or Vorbis decoders might still have data in them
                                // break;
                            } else {
                                decoder.navFeedSamples(rawsamples, rawbytesRead, decoder.buffer);
                            }

                            readSamples = decoder.navOutputSamples(samples, decoder.buffer);
//                            Alog.v(TAG, "run: readSamples value: " + readSamples);
                        }

                        if (status == PlayerStatus.Stopped || status == PlayerStatus.End || status == PlayerStatus.Error) {
                            breakAfterWhileLoop = true;
                            break;
                        }

                        if (sonic == null && decoder != null && GetRate() > 0 && GetNumChannels() > 0) {
                            sonic = new Sonic(GetRate(), GetNumChannels());
                            sonic.setSpeed(1);
                            sonic.setPitch(1);
                            sonic.setRate(1);

                            offTimer = totalReadSamples > 2048 ? totalReadSamples - 2048 : totalReadSamples;

                            Alog.v(TAG, "create sonic, set offtimer: " + offTimer + " totalReadSamples: " + totalReadSamples);
                        }
                        if (sonic != null) {
                            sonic.setSpeed(speed);
                        }
                        if (sonic != null && readSamples > 0) {
                            sonic.putBytes(samples, readSamples);

                            readSamplesTotal += readSamples;
                        }
                        available = 0;
                        if (sonic != null) {
                            available = sonic.availableBytes();
                        }
                    }

                    if (available > 0) {
                        if (modifiedSamples.length < available) {
                            modifiedSamples = new byte[available * 2];
                        }
                        sonic.receiveBytes(modifiedSamples, available);
                        if (audioTrack == null) {
                            int encode = -1;
                            if (GetEncoding() == 208) {
                                encode = AudioFormat.ENCODING_PCM_16BIT;
                            } else if (GetEncoding() != -1) {
                                encode = AudioFormat.ENCODING_DEFAULT;
                            }
                            if (encode != -1) {
//                                if (offTimer > 1024 * 1024) { //1mb
//                                    //
//                                    postError(new Exception("offTimer position is too big (" + offTimer + " bytes), can't get accurate current positon"), 30002);
//                                    break;
//                                }
                                audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, GetRate(),
                                        GetNumChannels() == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO,
                                        encode, available, AudioTrack.MODE_STREAM);
                                audioTrack.play();
                            }
                        }
                        if (audioTrack != null) {
                            //prevent writing if we want seek(seek is other than 0)
                            if (seekToMilliseconds == -1) {
                                if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                                    audioTrack.play();
                                }
                                audioTrack.write(modifiedSamples, 0, available);
                            }
                        }
                        if (audioTrack != null) {
                            //                            audioTrack.flush();
                        }

                    }

                    if (continueAfterWhileLoop) {
                        continue;
                    }
                    if (breakAfterWhileLoop) {
                        break;
                    }

                    if (status == PlayerStatus.Stopped || status == PlayerStatus.End || status == PlayerStatus.Error) {
                        break;
                    }


                    if (seekToMilliseconds != -1 && sonic != null) { //after we know offtimer
                        Alog.v(TAG, "run: seekToMilliseconds to byte, offtimer: " + offTimer);
                        seekToByte = milliSecondsToByte(seekToMilliseconds);
                    }

                    long seekToByteLocal = seekToByte;
                    if (seekToMilliseconds == -1) {
                        seekToInProgress = false;
                    } else if (seekToByteLocal != 0 && decoder != null && GetNumChannels() != 0 && offTimer != 0) {
                        long currentPos = totalReadSamples;
                        totalReadSamples = seekToByteLocal;// + GetOffTimer(0);// milliSecondsToByte(offB);//

                        Alog.v(TAG, "currentPositionMs seekToByteLocal: " + seekToByteLocal);

                        // ,
                        // GetRate(),

                        if (totalReadSamples <= offTimer) {
                            Alog.w(TAG, "totalReadSamples <= offTimer totalReadSamples: " + totalReadSamples + " offTimer: " + offTimer);
                            totalReadSamples = Math.max(0, offTimer - 16384);//seeking to 0 or decrease offtimer by 16 * 1024
                            // previously totalReadSamples = offtimer was causing skip of some sound
                        }
                        if (totalReadSamples >= size && size > -1) {
                            totalReadSamples = size;
                        }

                        if (fileFormat == FileFormat.MP4_AAC) {
                            double percent = (double) totalReadSamples / (double) size;
                            trackAAC.seek(percent * aacDuration);
                        } else if (fileFormat == FileFormat.ADTS_AAC) {
                            try {
                                if (fileType.equals("content")) {
                                    adtsDemultiplexer = new ADTSDemultiplexer(contentStream);
                                } else if (fileType.equals("local")) {
                                    adtsDemultiplexer = new ADTSDemultiplexer(new FileInputStream(remote_url));
                                } else {
                                    try {
                                        remoteInputStream.close();
                                    } catch (IllegalStateException e) {
                                        Alog.e(TAG, e.getMessage());
                                    }
                                    mHttpURLConnection.disconnect();
                                    mHttpURLConnection = new OkUrlFactory(httpclient).open(new URL(remote_url));
                                    mHttpURLConnection.setRequestProperty("User-Agent", mUserAgent);
                                    range = "bytes=" + totalReadSamples + "-" + (size > 0 ? size : "");
                                    mHttpURLConnection.setRequestProperty("Range", range);
                                    // Defeat transparent gzip compression, since it doesn't allow us to
                                    // easily resume partial downloads.
                                    mHttpURLConnection.setRequestProperty("Accept-Encoding", "identity");
                                    // Defeat connection reuse, since otherwise servers may continue
                                    // streaming large downloads after cancelled.
                                    mHttpURLConnection.setRequestProperty("Connection", "close");

                                    //newly added
                                    mHttpURLConnection.setConnectTimeout(CONNECTION_TIMEOUT);
                                    mHttpURLConnection.setReadTimeout(CONNECTION_TIMEOUT);
                                    remoteInputStream = mHttpURLConnection.getInputStream();
                                    adtsDemultiplexer = new ADTSDemultiplexer(remoteInputStream);
                                }

                                long position = 0;
                                while (position < totalReadSamples) {
                                    byte[] frameAAC = adtsDemultiplexer.readNextFrame();
                                    position += frameAAC.length;
                                }
                                totalReadSamples = position;
                            } catch (Exception ex) {
                                ex.printStackTrace();
                                postError(ex, 10017);
                                return;
                            }
                        } else {
                            if (fileType.equals("content")) {
                                long position = 0;
                                if (currentPos <= totalReadSamples) {
                                    //seek forward, so just read bytes
                                    position = currentPos;
                                } else {
                                    //seek backward, so start from beginning
                                    if (contentStream != null) {
                                        try {
                                            contentStream.close();
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                    try {
                                        contentStream = context.getContentResolver().openInputStream(mContentUri);
                                    } catch (FileNotFoundException e) {
                                        e.printStackTrace();
                                    }
                                }
                                while (position < totalReadSamples) {
                                    try {
                                        position += contentStream.read(rawsamples, 0, BUFFERSIZE);
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                                totalReadSamples = position;

                            } else if (fileType.equals("local")) {
                                try {
                                    Alog.v(TAG, "currentPositionMs localFile.seek totalReadSamples: " + totalReadSamples);
                                    localFile.seek(totalReadSamples);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            } else {
                                if (CustomMediaPlayer.this.streamBuffer != null && CustomMediaPlayer.this.streamBuffer.isBufferAvailable()) {

                                    if (totalReadSamples > CustomMediaPlayer.this.streamBuffer
                                            .getStartPosition() && totalReadSamples < CustomMediaPlayer.this.streamBuffer
                                            .getWritePosition()) {
                                        //if seek into buffered part
                                        //continue buffering
                                        CustomMediaPlayer.this.streamBuffer
                                                .setReadPosition(totalReadSamples);

                                    } else {

                                        Alog.v(TAG, "run: StreamBuffer reconnect totalReadSamples: "
                                                + totalReadSamples + " getStartPosition " + CustomMediaPlayer.this.streamBuffer
                                                .getStartPosition() + " writePosition: " + CustomMediaPlayer.this.streamBuffer
                                                .getWritePosition());

                                        CustomMediaPlayer.this.streamBuffer
                                                .setStartPosition(totalReadSamples);
                                        CustomMediaPlayer.this.streamBuffer
                                                .setReadPosition(totalReadSamples);
                                        CustomMediaPlayer.this.streamBuffer
                                                .setWritePosition(totalReadSamples);

                                        try {
                                            try {
                                                remoteInputStream.close();
                                            } catch (IllegalStateException e) {
                                                Alog.e(TAG, e.getMessage());
                                            }
                                            mHttpURLConnection.disconnect();
                                            mHttpURLConnection = new OkUrlFactory(httpclient).open(new URL(remote_url));
                                            mHttpURLConnection.setRequestProperty("User-Agent", mUserAgent);
                                            range = "bytes=" + totalReadSamples + "-" + (size > 0 ? size : "");
                                            mHttpURLConnection.setRequestProperty("Range", range);
                                            // Defeat transparent gzip compression, since it doesn't allow us to
                                            // easily resume partial downloads.
                                            mHttpURLConnection.setRequestProperty("Accept-Encoding", "identity");
                                            // Defeat connection reuse, since otherwise servers may continue
                                            // streaming large downloads after cancelled.
                                            mHttpURLConnection.setRequestProperty("Connection", "close");
                                            //newly added
                                            mHttpURLConnection.setConnectTimeout(CONNECTION_TIMEOUT);
                                            mHttpURLConnection.setReadTimeout(CONNECTION_TIMEOUT);
                                            remoteInputStream = mHttpURLConnection.getInputStream();
                                            streamBuffer.setEndReached(false);
                                        } catch (IOException e) {
                                            //                                            continueBufferingInputStream(totalReadSamples, "thPlay seek isBufferAvailable IOException");
                                            Alog.e(TAG, e.getMessage());
                                        }
                                    }
                                } else {
                                    try {
                                        try {
                                            remoteInputStream.close();
                                        } catch (IllegalStateException e) {
                                            Alog.e(TAG, e.getMessage());
                                        }
                                        mHttpURLConnection.disconnect();
                                        mHttpURLConnection = new OkUrlFactory(httpclient).open(new URL(remote_url));
                                        mHttpURLConnection.setRequestProperty("User-Agent", mUserAgent);
                                        range = "bytes=" + totalReadSamples + "-" + (size > 0 ? size : "");
                                        mHttpURLConnection.setRequestProperty("Range", range);
                                        // Defeat transparent gzip compression, since it doesn't allow us to
                                        // easily resume partial downloads.
                                        mHttpURLConnection.setRequestProperty("Accept-Encoding", "identity");
                                        // Defeat connection reuse, since otherwise servers may continue
                                        // streaming large downloads after cancelled.
                                        mHttpURLConnection.setRequestProperty("Connection", "close");
                                        //newly added
                                        mHttpURLConnection.setConnectTimeout(CONNECTION_TIMEOUT);
                                        mHttpURLConnection.setReadTimeout(CONNECTION_TIMEOUT);
                                        remoteInputStream = mHttpURLConnection.getInputStream();
                                    } catch (IOException e) {
                                        Alog.e(TAG, e.getMessage());
                                        //                                        continueBufferingInputStream(totalReadSamples, "thPlay seek NOT isBufferAvailable IOException");
                                    }
                                }
                            }
                        }

                        if (fileFormat == FileFormat.MP3) {
                            Alog.v(TAG, "CloseFiles: Decoder 2");
                            decoder.closeFile(decoder.buffer);
                            decoder.openFile(decoder.buffer, fileFormat.value());
                            if (audioTrack != null) {
                                audioTrack.flush();
                            }
                        } else if (fileFormat == FileFormat.OGG) {
                            decoder.flush(decoder.buffer);
                            if (audioTrack != null) {
                                audioTrack.flush();
                                audioTrack.release();
                                audioTrack = null;
                            }
                        } else if (fileFormat == FileFormat.FLAC) {
                            decoder.flush(decoder.buffer);
                            if (audioTrack != null) {
                                audioTrack.flush();
                                audioTrack.release();
                                audioTrack = null;
                            }
                        }

                        handler.post(new Runnable() {

                            @Override
                            public void run() {
                                if (listener != null)
                                    listener.onPlaying(CustomMediaPlayer.this,
                                            totalReadSamples);
                            }
                        });

                        if (seekToByteLocal == seekToByte) {
                            //set seek to byte to 0 only if it was not changed while seeking was executing
                            seekToByte = 0;
                            seekToMilliseconds = -1;
                            seekToInProgress = false;
                        }
                        //                            handler.post(new Runnable() {
                        //
                        //                                @Override
                        //                                public void run() {
                        //                                    if (listener != null)
                        //                                        listener.onSeekComplete(CustomMediaPlayer.this);
                        //
                        //                                }
                        //                            });
                    }

                    handler.post(new Runnable() {

                        @Override
                        public void run() {
                            if (listener != null) {
                                listener.onPlaying(CustomMediaPlayer.this, totalReadSamples);
                            }
                        }
                    });
                }

                if (status == PlayerStatus.PlaybackCompleted) {

                    //to prevent flushing unplayed data with CloseFiles() method when playback is completed, we wait until it's finished
                    //to check whether it's finished we compare playbackhead positon and if it's same, we know that everything was played

                    int prevPlaybackHeadPosition = -1;
                    try {
                        while (audioTrack != null && audioTrack.getPlaybackHeadPosition() != prevPlaybackHeadPosition) {
                            prevPlaybackHeadPosition = audioTrack.getPlaybackHeadPosition();
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    } catch (Exception e) {
                        Alog.e(TAG, "Ending playback exception", e);
                    }
                }

                CloseFiles();

                if (status == PlayerStatus.PlaybackCompleted) {
                    handler.post(new Runnable() {

                        @Override
                        public void run() {
                            if (listener != null)
                                listener.onCompletion(CustomMediaPlayer.this);
                        }
                    });
                }

            }
        });
        thPrep.setName("CustomMediaPlayer.PlayThread");
        thPlay.setUncaughtExceptionHandler(
                new ReportExceptionHandler(context, "start() " + handledExceptionMessage,
                        mExceptionHandlerCallback, true)
        );
        thPlay.start();
    }

    @Override
    public void stop() throws IllegalStateException {
        if (status == PlayerStatus.Initialized || status == PlayerStatus.Idle || status == PlayerStatus.Error) {
            SetStatus(PlayerStatus.Error);
            return;
        }

        if (status != PlayerStatus.Prepared && status != PlayerStatus.Started
                && status != PlayerStatus.Stopped && status != PlayerStatus.Paused
                && status != PlayerStatus.PlaybackCompleted) {
            return;
        }

        if (status == PlayerStatus.Stopped || status == PlayerStatus.End || status == PlayerStatus.PlaybackCompleted) {
            SetStatus(PlayerStatus.Stopped);
            return;
        }
        if (status == PlayerStatus.Started) {
            SetStatus(PlayerStatus.Stopped);
        }
    }

    @Override
    public void setSpeedMediaPlayerListener(CustomMediaPlayerListener listener) {
        this.listener = listener;
    }

    /**
     * Checks system architecture and return true if it's arm, x86 is not
     * supported
     *
     * @return true if arm architecture otherwise false
     */
    public static boolean isCustomPlayerImplementedOnThisArchitecture() {
        return true;
    }

    public static boolean isFileTypeSupported(String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }

        String lowercasePath = path.toLowerCase().trim();

        boolean isFileSupported = lowercasePath.endsWith(".mp3") || lowercasePath.endsWith(".ogg")
                || lowercasePath.endsWith(".flac") || lowercasePath.endsWith(".mp4")
                || lowercasePath.endsWith(".aac") || lowercasePath.endsWith(".m4a");//disable m4a as there is some issue
        if (!isFileSupported)
            isFileSupported = lowercasePath.contains(".mp3?") || lowercasePath.contains(".ogg?")
                    || lowercasePath.contains(".flac?") || lowercasePath.contains(".mp4?")
                    || lowercasePath.contains(".aac?") || lowercasePath.contains(".m4a?"); //

        if (isFileSupported) {
            //if it's stream and mp4_aac disable it because of issue with RandomAccessFileStream
            if (lowercasePath.startsWith("http") && formatFromPath(lowercasePath) == FileFormat.MP4_AAC) {
                isFileSupported = false;
            }
        }

        Alog.v(TAG, "isFileTypeSupported: " + isFileSupported + " path: " + path);

        return isFileSupported;
    }

    public static boolean isFileMP4_AAC(String path) {
        if (TextUtils.isEmpty(path))
            return false;

        String lowercasePath = path.toLowerCase().trim();
        if (formatFromPath(lowercasePath) == FileFormat.MP4_AAC) {
            return true;
        }

        //special case, mp4 is supported offline
        return false;
    }

    private static FileFormat formatFromPath(String path) {
        String lowercasePath = path.toLowerCase();

        if (lowercasePath.endsWith(".mp3") || lowercasePath.contains(".mp3?"))
            return FileFormat.MP3;
        if (lowercasePath.endsWith(".ogg") || lowercasePath.contains(".ogg?"))
            return FileFormat.OGG;
        if (lowercasePath.endsWith(".flac") || lowercasePath.contains(".flac?"))
            return FileFormat.FLAC;
        if (lowercasePath.endsWith(".mp4") || lowercasePath.contains(".mp4?"))
            return FileFormat.MP4_AAC;
        if (lowercasePath.endsWith(".m4a") || lowercasePath.contains(".m4a?"))
            return FileFormat.MP4_AAC;
        if (lowercasePath.endsWith(".aac") || lowercasePath.contains(".aac?"))
            return FileFormat.ADTS_AAC;
        return FileFormat.NONE;
    }

    private static FileFormat formatFromMimeType(String mimeType) {
        if (mimeType == null) return FileFormat.NONE;

        String lowercaseMimeType = mimeType.toLowerCase();

        if (lowercaseMimeType.contains("mp3"))
            return FileFormat.MP3;
        if (lowercaseMimeType.contains("ogg"))
            return FileFormat.OGG;
        if (lowercaseMimeType.contains("flac"))
            return FileFormat.FLAC;
        if (lowercaseMimeType.contains("mp4"))
            return FileFormat.MP4_AAC;
        if (lowercaseMimeType.contains("m4a"))
            return FileFormat.MP4_AAC;
        if (lowercaseMimeType.contains("aac"))
            return FileFormat.ADTS_AAC;
        return FileFormat.NONE;
    }

    private void continueBufferingInputStream(Context context, long bytePosition, boolean postError, String method) {
        Alog.v(TAG, "continueBufferingInputStream " + method);
        if (status == PlayerStatus.Error || status == PlayerStatus.Stopped
                || status == PlayerStatus.PlaybackCompleted || status == PlayerStatus.Idle) {
            return;
        }

        try {
            mHttpURLConnection = new OkUrlFactory(httpclient).open(new URL(remote_url));
            mHttpURLConnection.setRequestProperty("User-Agent", mUserAgent);
            mHttpURLConnection.setRequestProperty("Range", "bytes=" + bytePosition + "-");   //continue!!!
            // Defeat transparent gzip compression, since it doesn't allow us to
            // easily resume partial downloads.
            mHttpURLConnection.setRequestProperty("Accept-Encoding", "identity");
            // Defeat connection reuse, since otherwise servers may continue
            // streaming large downloads after cancelled.
            mHttpURLConnection.setRequestProperty("Connection", "close");
            //newly added
            mHttpURLConnection.setConnectTimeout(CONNECTION_TIMEOUT);
            mHttpURLConnection.setReadTimeout(CONNECTION_TIMEOUT);
            //todo majovv
            remoteInputStream = mHttpURLConnection.getInputStream();
            if (size <= 0) {
                size = getContentLengthLong(mHttpURLConnection);
            }
            if (streamBuffer == null) {
                //setup buffer
                streamBuffer = new StreamBuffer();
                streamBuffer.init(context, size);
            } else {
                streamBuffer.setStreamSize(size);
            }
            streamBuffer.setEndReached(false);
        } catch (IOException e) {
            if (postError) {
                Alog.v(TAG, "continueBufferingInputStream IOException method: " + method);
                postError(e, 10007);
            }
        }
    }

    private void continueBufferingInputStream(Context context, long bytePosition, String method) {
        continueBufferingInputStream(context, bytePosition, true, method);
    }

    /**
     * @param e
     * @param what error above 30000 will indicate that it's not network related so we don't need to switch user agent
     */
    private void postError(Exception e, final int what) {
        //error above 30000 will indicate that it's not network related so we don't need to switch user agent
        Alog.addLogMessageError(TAG, "Speed player error. Error code: " + what);
        Alog.e(TAG, "Speed player error. Error code: " + what, e);

        SetStatus(PlayerStatus.Error);
        CloseFiles();

        handler.post(new Runnable() {

            @Override
            public void run() {
                if (listener != null) {
                    listener.onError(CustomMediaPlayer.this, what, 0);
                }
            }
        });
    }

    private void postBufferUpdate(final long readBytes) {
        handler.post(new Runnable() {

            @Override
            public void run() {
                if (listener != null) {
                    listener.onBufferingUpdate(CustomMediaPlayer.this, size != 0 ? (int) (readBytes * 100 / size) : 0);
                }
            }
        });
    }

    private static long getContentLengthLong(URLConnection conn) {
        return getHeaderFieldLong(conn, "Content-Length", -1);
    }

    private static long getHeaderFieldLong(URLConnection conn, String field, long defaultValue) {
        try {
            return Long.parseLong(conn.getHeaderField(field));
        } catch (NumberFormatException e) {
            Alog.e(TAG, "getHeaderFieldLong: exception, ", e);
            return defaultValue;
        }
    }

    protected native void setVolumeBoostNative(boolean enable);

    protected native boolean isVolumeBoostNative();

    protected native void setSilenceSkipNative(boolean enable);

    protected native boolean isSilenceSkipNative();

    protected native void setReduceNoiseNative(boolean enable);

    protected native boolean isReduceNoiseNative();
}
