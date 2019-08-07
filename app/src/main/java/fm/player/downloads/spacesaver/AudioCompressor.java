package fm.player.downloads.spacesaver;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;
import net.sourceforge.jaad.adts.ADTSDemultiplexer;
import net.sourceforge.jaad.mp4.MP4Container;
import net.sourceforge.jaad.mp4.api.Frame;
import net.sourceforge.jaad.mp4.api.Movie;
import net.sourceforge.jaad.mp4.api.Track;

import org.xiph.vorbis.encoder.EncodeFeed;
import org.xiph.vorbis.encoder.VorbisEncoder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.List;

import de.greenrobot.event.EventBus;
import fm.player.downloads.DownloadUtils;
import fm.player.eventsbus.Events;
import fm.player.mediaplayer.player.Mpg123Decoder;
import fm.player.mediaplayer.player.StreamBuffer;
import fm.player.mediaplayer.utils.Mp3Reader;
import fm.player.mediaplayer.utils.RandomAccessFileStream;
import fm.player.utils.Alog;
import fm.player.utils.ProgressUtils;

/**
 * Created by Marian_Vandzura on 22.3.2017.
 * Audio files compression - compress to .ogg format
 */
public class AudioCompressor {

    private static final String TAG = "AudioCompressor";

    private static final String COMPRESSED_AUDIO_SUFFIX = ".compressed.ogg";
    private static final String COMPRESSED_AUDIO_DIR = "/compressed";

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
    private static final String FILE_TYPE_LOCAL = "local";
    private static final String FILE_TYPE_CONTENT = "content";

    /**
     * interface of native class to decode the stream
     */
    private Mpg123Decoder decoder;
    private AudioCompressor.FileFormat fileFormat = AudioCompressor.FileFormat.NONE;

    /**
     * url is passed from activity for streaming in this thread
     */
    private String path = "";
    private long totalReadSamples;
    private long size = 0;
    private volatile int duration = 0;
    private String mimeType = null;
    private String fileType;

    private Uri mContentUri;
    /**
     * Stream of local file
     */
    private InputStream contentStream = null;
    /**
     * local file for reading stream
     */
    private RandomAccessFile localFile = null;

    /**
     * File used as stream buffer
     */
    private StreamBuffer streamBuffer = null;

    // AAC
    private File mp4PlaceHolder;
    private RandomAccessFileStream randomAccessFileStream = null;
    private MP4Container containerMP4AAC = null;
    private Track trackAAC = null;

    private ADTSDemultiplexer adtsDemultiplexer = null;

    private Decoder decoderAAC = null;
    private double aacDuration = 0.0;
    private int aacSampleRate = -1;
    private int aacChannels = -1;

    private float mCompressionQuality = 0.1f;
    private long mUncompressedAudioSize = 0;
    private long mCompressedAudioSize = 0;

    private long inputReadCount = 0;
    private boolean encodedEnd = false;

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

    /* ****************************************************** */
    /*                       MAIN
    /* ****************************************************** */
    public AudioCompressor() {
        //default
    }

    /**
     * compress file
     *
     * @param contentUri            file to be compressed
     * @param quality               compression quality, value gave to be from interval <0.1f, 1.0f> (0.1f biggest compression)
     * @param savedCompressionValue preferred compression - LOT/LITTLE
     * @return compressed file
     * @throws Exception
     */
//    public File compress(Context context, Uri contentUri, float quality, int savedCompressionValue) throws Exception {
//        mCompressionQuality = quality;
//        setDataSource(context, contentUri);
//        return compress(context, savedCompressionValue);
//    }

    /**
     * @param path    path of file to compress
     * @param quality compression quality, value gave to be from interval <0.1f, 1.0f> (0.1f biggest compression)
     */
    public File compress(Context context, String path, float quality, int savedCompressionValue) throws Exception {
        Alog.addLogMessage(TAG, "compress: path: " + path + " quality: " + quality);
        Alog.saveLogs(context);
        mCompressionQuality = quality;
        setDataSource(path);
        File compressed = compress(context, savedCompressionValue);
        Alog.saveLogs(context);
        return compressed;
    }

    private File compress(final Context context, int savedCompressionValue) throws Exception {
        Alog.addLogMessage(TAG, "compress: DECODE ASYNC START: >> ");
        Alog.saveLogs(context);
        //load library
        System.loadLibrary("ogg");
        System.loadLibrary("vorbis");
        System.loadLibrary("flac");
        System.loadLibrary("jni-mpg123-sonic");

        /* decoder init */
        initDecoder(context);

        if (mimeType != null && mimeType.contains("video")) {
            Alog.addLogMessage(TAG, "VIDEO not supported, end");
            Alog.saveLogs(context);
            return null;
        }

        /* encoder params (params of input audio file)*/
        int channels = 0;
        int rate = 0;

        // downloads path
        String downloadsPath = DownloadUtils.prepareDownloadsFolder(context);
        File compressedEpisodesDir = new File(new File(downloadsPath), COMPRESSED_AUDIO_DIR);
        if (!compressedEpisodesDir.exists()) {
            boolean created = compressedEpisodesDir.mkdirs();
            Alog.addLogMessage(TAG, "compress: compressed audio dir doesn't exists. Create new success: " + created);
            Alog.saveLogs(context);
        }

        String compressedEpisodesDirPath = compressedEpisodesDir.getAbsolutePath() + "/";
        Alog.saveLogs(context);

        Alog.addLogMessage(TAG, "compress file path: " + path);
        // uncompressed file name
        String uncompressedFileName = new File(path).getName();

        // get uncompressed audio size
        mUncompressedAudioSize = new File(path).length();

        Alog.addLogMessage(TAG, "compress: uncompressed audio size: " + mUncompressedAudioSize);

        boolean isWavFile = false;
        if (fileFormat == FileFormat.NONE) {
            isWavFile = path != null && path.toLowerCase().endsWith(".wav");
            Alog.addLogMessage(TAG, "compress: is Wav file: " + path);
        }
        Alog.saveLogs(context);

        File decodedFile;
        String compressedFilePath = compressedEpisodesDirPath + uncompressedFileName + getCompressedAudioSuffix(context, savedCompressionValue);

        if (isWavFile) {
            // .wav is already decoded format, so we can proceed with encoding immediately
            Alog.addLogMessage(TAG, "compress: WAV FILE");
            decodedFile = new File(path);
            Alog.saveLogs(context);
            Alog.addLogMessage(TAG, "encode start");

            return encodeRawFileToOgg(context, decodedFile, rate, channels, compressedFilePath);
        } else {
            // other formats have to be decoded to raw format and then can be encoded
            return encodeToOgg(context, compressedFilePath);
        }
// test
//        mCompressionQuality = 0.4f;
//        compressedFilePath = compressedEpisodesDirPath + uncompressedFileName + ".compressed-" + String.valueOf(mCompressionQuality) + ".ogg";
//        encodeRawFileToOgg(context, decodedFile, rate, channels, compressedFilePath);
//        mCompressionQuality = 0.000f;
//        compressedFilePath = compressedEpisodesDirPath + uncompressedFileName + ".compressed-" + String.valueOf(mCompressionQuality) + ".ogg";
//        encodeRawFileToOgg(context, decodedFile, rate, channels, compressedFilePath);
//
//
//        mCompressionQuality = 128000;
//        compressedFilePath = compressedEpisodesDirPath + uncompressedFileName + ".compressed-" + String.valueOf(mCompressionQuality) + ".ogg";
//        encodeRawFileToOgg(context, decodedFile, rate, channels, compressedFilePath);
//
//        mCompressionQuality = 64000;
//        compressedFilePath = compressedEpisodesDirPath + uncompressedFileName + ".compressed-" + String.valueOf(mCompressionQuality) + ".ogg";
//        encodeRawFileToOgg(context, decodedFile, rate, channels, compressedFilePath);
//
//        mCompressionQuality = 48000;
//        compressedFilePath = compressedEpisodesDirPath + uncompressedFileName + ".compressed-" + String.valueOf(mCompressionQuality) + ".ogg";
//        encodeRawFileToOgg(context, decodedFile, rate, channels, compressedFilePath);
//        decodedFile.delete();
    }


    /* ****************************************************** */
    /*                       ENCODER
    /* ****************************************************** */

    private long percentageEncoded = -1;

    /**
     * compress raw audio file (e.g .wav format)
     *
     * @param decodedFile        raw audio file
     * @param rate               audio file rate
     * @param channels           num of audio file channels
     * @param compressedFilePath output file
     * @return compressed ogg audio file
     */
    private File encodeRawFileToOgg(final Context context, final File decodedFile, int rate, int channels,
                                    final String compressedFilePath) {
        Alog.addLogMessage(TAG, "encodeRawFileToOgg: PARAMS: " + decodedFile.getAbsolutePath()
                + "; rate: " + rate + "; channels: " + channels);
        Alog.saveLogs(context);
        if (rate <= 0) {
            rate = 44100; //default value
        }
        if (channels <= 0) {
            channels = 2; //default value
        }

        // ENCODE to .ogg
        encodedEnd = false;
        File encodedFile = null;
        try {
            encodedFile = new File(compressedFilePath);
            encodedFile.delete();
            encodedFile = new File(compressedFilePath);
            Alog.addLogMessage(TAG, "encodeRawFileToOgg: encodedFile: " + encodedFile.getAbsolutePath());
            final long decodedSize = decodedFile.length();
            Alog.addLogMessage(TAG, "encodeRawFileToOgg: decoded file size: " + decodedSize);

            Alog.saveLogs(context);

            final FileInputStream decodedFileInputStream = new FileInputStream(decodedFile);
            final FileOutputStream encodedFileOutputStream = new FileOutputStream(encodedFile);

            final long startTime = System.currentTimeMillis();
            final File finalEncodedFile = encodedFile;

            Alog.addLogMessage(TAG, "encodeRawFileToOgg: quality: " + mCompressionQuality);
            Alog.logUsedMemorySize();
            Alog.saveLogs(context);

            EncodeFeed encodeFeed = new EncodeFeed() {
                @Override
                public long readPCMData(byte[] pcmDataBuffer, int amountToWrite) {
                    try {
                        // new data for encoding requested, read from file
                        int read = decodedFileInputStream.read(pcmDataBuffer, 0, amountToWrite);
                        inputReadCount += read;
                        long currentPercentage = ProgressUtils.getPercentage(inputReadCount, decodedSize);
                        if (percentageEncoded != currentPercentage) {
                            percentageEncoded = currentPercentage;
                            Alog.logUsedMemorySize();
                            Alog.addLogMessage(TAG, "readPCMData: encoded: " + percentageEncoded + "%");
                            Alog.saveLogs(context);
                        }

                        //end of file
                        if (read == -1) {
                            Alog.addLogMessage(TAG, "readPCMData: encoded end");
                            Alog.saveLogs(context);
                            encodedEnd = true;
                            return 0;
                        }
                        return read;
                    } catch (IOException e) {
                        Alog.e(TAG, "readPCMData: IOException: " + e.getMessage(), e, true);
                        Alog.saveLogs(context);
                        decodedFile.delete();
                        finalEncodedFile.delete();
                    }
                    return 0;
                }

                @Override
                public int writeVorbisData(byte[] vorbisData, int amountToRead) {
                    if (!encodedEnd) {
                        Alog.v(TAG, "writeVorbisData: " + amountToRead);
                        try {
                            encodedFileOutputStream.write(vorbisData, 0, amountToRead);
                            return amountToRead;
                        } catch (IOException e) {
                            Alog.e(TAG, "writeVorbisData: IOException: " + e.getMessage(), e, true);
                            Alog.saveLogs(context);
                            decodedFile.delete();
                            finalEncodedFile.delete();
                        }
                    }
                    return 0;
                }

                @Override
                public void stop() {
                    encodedEnd = true;

                    try {
                        encodedFileOutputStream.flush();
                        encodedFileOutputStream.close();
                    } catch (IOException e) {
                        Alog.e(TAG, "stop: IOException: " + e.getMessage(), e, true);
                        Alog.saveLogs(context);
                        finalEncodedFile.delete();
                    }

                    try {
                        decodedFileInputStream.close();
                    } catch (IOException e) {
                        Alog.e(TAG, "stop: IOException: " + e.getMessage(), e, true);
                        Alog.saveLogs(context);
                        decodedFile.delete();
                    }

                    mCompressedAudioSize = finalEncodedFile.length();
                    Alog.addLogMessage(TAG, "Encoded compressed FILE: >>>> " + finalEncodedFile.getAbsolutePath() + " size: " + mCompressedAudioSize);

                    long dur = System.currentTimeMillis() - startTime;
                    Alog.addLogMessage(TAG, "encoding time: " + dur + "ms" + " encoding: " + mCompressionQuality + " size: " + mCompressedAudioSize + " path: " + compressedFilePath);
                    Alog.saveLogs(context);
                }

                @Override
                public void stopEncoding() {
                    Alog.addLogMessage(TAG, "stopEncoding: ");
                    Alog.saveLogs(context);
                }

                @Override
                public void start() {
                    Alog.addLogMessage(TAG, "start Encoding: ");
                    Alog.saveLogs(context);
                }
            };

            //start encoding raw file
            if (mCompressionQuality < 10) {
                VorbisEncoder.startEncodingWithQuality(rate, channels, mCompressionQuality, encodeFeed);
            } else {
                VorbisEncoder.startEncodingWithBitrate(rate, channels, (long) mCompressionQuality, encodeFeed);
            }

        } catch (FileNotFoundException e) {
            Alog.e(TAG, "encodeRawFileToOgg: FileNotFoundException: " + e.getMessage(), e, true);
            Alog.saveLogs(context);
            e.printStackTrace();
        } finally {
            // DELETE decoded raw audio file
            boolean deleted = decodedFile.delete();
            Alog.addLogMessage(TAG, "encodeRawFileToOgg: delete raw data file success: " + deleted);
            Alog.saveLogs(context);
        }
        Alog.addLogMessage(TAG, "encodeRawFileToOgg: finish");
        Alog.saveLogs(context);
        return encodedFile;
    }

    /**
     * compress not raw file
     * in loop read part of file -> decode to {@link PcmDataBuffer} -> read from buffer -> encode (using vorbis)
     * -> write to output (compressed) file -> end of loop
     *
     * @param compressedFilePath output file
     * @return compressed ogg audio file
     */
    private File encodeToOgg(final Context context, final String compressedFilePath) {
        Alog.addLogMessage(TAG, "encodeToOgg");
        Alog.saveLogs(context);
        try {
            //init rate and channels params required by encoder
            initChannelsAndRate(context);
            Alog.addLogMessage(TAG, "encodeToOgg: PARAMS: rate: " + mRate + "; channels: " + mChannels);
            Alog.saveLogs(context);
            //restart decoder
            initDecoder(context);
        } catch (Exception e) {
            e.printStackTrace();
        }

        //ensure default values
        initDecoderVariables();

        Alog.saveLogs(context);

        // ENCODE to .ogg
        encodedEnd = false;
        File encodedFile = null;
        try {
            encodedFile = new File(compressedFilePath);
            encodedFile.delete();
            encodedFile = new File(compressedFilePath);
            Alog.addLogMessage(TAG, "encodeRawFileToOgg: encodedFile: " + encodedFile.getAbsolutePath());

            Alog.saveLogs(context);

            final FileOutputStream encodedFileOutputStream = new FileOutputStream(encodedFile);

            final long startTime = System.currentTimeMillis();
            final File finalEncodedFile = encodedFile;

            Alog.addLogMessage(TAG, "encodeRawFileToOgg: quality: " + mCompressionQuality);
            Alog.logUsedMemorySize();
            Alog.saveLogs(context);

            EncodeFeed encodeFeed = new EncodeFeed() {
                @Override
                public long readPCMData(byte[] pcmDataBuffer, int amountToWrite) {
                    try {
                        // read data for encoding - try to load decoded data from buffer, or decode first if empty
                        int read = readDataForEncoding(context, pcmDataBuffer, amountToWrite);
                        Alog.v(TAG, "readPCMData: READ > int: " + read);

                        //end of file
                        if (read == -1) {
                            Alog.addLogMessage(TAG, "readPCMData: encoded end");
                            Alog.saveLogs(context);
                            encodedEnd = true;
                            return 0;
                        }
                        return read;
                    } catch (Exception e) {
                        Alog.e(TAG, "readPCMData: IOException: " + e.getMessage(), e, true);
                        Alog.saveLogs(context);
                        finalEncodedFile.delete();
                    }
                    return 0;
                }

                @Override
                public int writeVorbisData(byte[] vorbisData, int amountToRead) {
                    if (!encodedEnd) {
                        Alog.v(TAG, "writeVorbisData: " + amountToRead);
                        try {
                            encodedFileOutputStream.write(vorbisData, 0, amountToRead);
                            return amountToRead;
                        } catch (IOException e) {
                            Alog.e(TAG, "writeVorbisData: IOException: " + e.getMessage(), e, true);
                            Alog.saveLogs(context);
                            finalEncodedFile.delete();
                        }
                    }
                    return 0;
                }

                @Override
                public void stop() {
                    Alog.v(TAG, "stop: ");
                    encodedEnd = true;

                    try {
                        encodedFileOutputStream.flush();
                        encodedFileOutputStream.close();
                    } catch (IOException e) {
                        Alog.e(TAG, "stop: IOException: " + e.getMessage(), e, true);
                        Alog.saveLogs(context);
                        finalEncodedFile.delete();
                    }

                    mCompressedAudioSize = finalEncodedFile.length();
                    Alog.addLogMessage(TAG, "Encoded compressed FILE: >>>> " + finalEncodedFile.getAbsolutePath() + " size: " + mCompressedAudioSize);

                    long dur = System.currentTimeMillis() - startTime;
                    Alog.addLogMessage(TAG, "encoding time: " + dur + "ms" + " encoding: " + mCompressionQuality + " size: " + mCompressedAudioSize + " path: " + compressedFilePath);
                    Alog.saveLogs(context);
                }

                @Override
                public void stopEncoding() {
                    Alog.addLogMessage(TAG, "stopEncoding: ");
                    Alog.saveLogs(context);
                }

                @Override
                public void start() {
                    Alog.addLogMessage(TAG, "start Encoding: ");
                    Alog.saveLogs(context);
                }
            };


            //start encoding
            if (mCompressionQuality < 10) {
                VorbisEncoder.startEncodingWithQuality(mRate, mChannels, mCompressionQuality, encodeFeed);
            } else {
                VorbisEncoder.startEncodingWithBitrate(mRate, mChannels, (long) mCompressionQuality, encodeFeed);
            }

        } catch (FileNotFoundException e) {
            Alog.e(TAG, "encodeRawFileToOgg: FileNotFoundException: " + e.getMessage(), e, true);
            Alog.saveLogs(context);
            e.printStackTrace();
        }

        CloseFiles();
        Alog.addLogMessage(TAG, "encodeRawFileToOgg: finish");
        Alog.saveLogs(context);
        return encodedFile;
    }

    public boolean compressionDoneOrInProgress(Context context, String episodePath, int savedCompressionValue) {
        String downloadsPath = DownloadUtils.prepareDownloadsFolder(context);
        File compressedEpisodesDir = new File(new File(downloadsPath), COMPRESSED_AUDIO_DIR);
        if (!compressedEpisodesDir.exists()) {
            return false;
        }
        String compressedEpisodesDirPath = compressedEpisodesDir.getAbsolutePath() + "/";
        String uncompressedFileName = new File(episodePath).getName();
        String compressedFilePath = compressedEpisodesDirPath + uncompressedFileName + getCompressedAudioSuffix(context, savedCompressionValue);
        File compressedFile = new File(compressedFilePath);
        //file is created at the beginning of compression, so it exists if in progress or already done
        return compressedFile.exists();
    }

    private String getCompressedAudioSuffix(Context context, int savedCompressionValue) {
//        switch (savedCompressionValue) {
//            case DownloadSettings.DownloadCompression.QUALITY_0_4_LITTLE:
//                return ".little" + COMPRESSED_AUDIO_SUFFIX;
//            case DownloadSettings.DownloadCompression.QUALITY_0_0_LOT:
//                return ".lot" + COMPRESSED_AUDIO_SUFFIX;
//            case DownloadSettings.DownloadCompression.BITRATE_48:
//                return ".48kbps" + COMPRESSED_AUDIO_SUFFIX;
//            case DownloadSettings.DownloadCompression.BITRATE_64:
//                return ".64kbps" + COMPRESSED_AUDIO_SUFFIX;
//            case DownloadSettings.DownloadCompression.BITRATE_128:
//                return ".128kbps" + COMPRESSED_AUDIO_SUFFIX;
//            default:
//                return COMPRESSED_AUDIO_SUFFIX;
//        }
        return ".little" + COMPRESSED_AUDIO_SUFFIX;
    }


    /* ****************************************************** */
    /*                       DECODER
    /* ****************************************************** */

    private int mRate;
    private int mChannels;

    private long percentRead;
    private byte rawsamples[];
    private int rawbytesRead;
    private int readSamples;
    private byte modifiedSamples[];
    private byte[] samples;
    private int totalDecoded;
    private PcmDataBuffer pcmDataBuffer;

    /* decoder initialization */
    private void initDecoder(Context context) throws Exception {
        Alog.addLogMessage(TAG, "initDecoder: start");
        Alog.saveLogs(context);

        Mp3Reader.MetaData metaData = null;
        if (FILE_TYPE_CONTENT.equals(fileType)) {
            metaData = Mp3Reader.getMetaData(context, mContentUri);
        } else {
            metaData = Mp3Reader.getMetaData(path);
        }

        duration = metaData.duration;
        mimeType = metaData.mimeType;

        if (duration <= 0) {
            //error
            Alog.addLogMessageError(TAG, "initDecoder: duration is: " + duration);
            postError(new Exception("episode duration is 0"), 50001, context);
            return;
        }
        if (mimeType != null && mimeType.contains("video")) {
            Alog.addLogMessageError(TAG, "initDecoder: episode is video - NOT SUPPORTED");
            postError(new Exception("episode is video"), 30015, context);
            return;
        }

        if (fileFormat == AudioCompressor.FileFormat.NONE) {
            //figure file format
            fileFormat = formatFromMimeType(mimeType);
            Alog.v(TAG, "initDecoder: fileFormat " + fileFormat + " from mimetype: " + mimeType);
        }

        Alog.d(TAG, "duration = " + duration);
        if (fileFormat == AudioCompressor.FileFormat.ADTS_AAC) {
            aacDuration = (float) duration / 1000.0f;
        }

        Alog.addLogMessage(TAG, "initDecoder: CloseFiles");
        CloseFiles();

        totalReadSamples = 0;
        Alog.addLogMessage(TAG, "initDecoder file: " + path);
        Alog.saveLogs(context);

        switch (fileType) {
            case FILE_TYPE_CONTENT:
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
                    returnCursor.close();
                } else {
                    try {
                        AssetFileDescriptor fd = context.getContentResolver().openAssetFileDescriptor(mContentUri, "r");
                        if (fd != null) {
                            size = fd.getLength();
                            fd.close();
                        }
                        name = mContentUri.toString();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                if (fileFormat == FileFormat.NONE) {
                    fileFormat = formatFromPath(name);
                    Alog.addLogMessage(TAG, "File format: " + fileFormat + " from path: " + path);
                }

                try {
                    contentStream = context.getContentResolver().openInputStream(mContentUri);
                    if (fileFormat == FileFormat.ADTS_AAC) {
                        adtsDemultiplexer = new ADTSDemultiplexer(contentStream);
                        decoderAAC = new Decoder(adtsDemultiplexer.getDecoderSpecificInfo());
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                    postError(e, 10999, context);
                }
                break;
            case FILE_TYPE_LOCAL:
                try {
                    if (fileFormat == FileFormat.ADTS_AAC) {
                        try {
                            adtsDemultiplexer = new ADTSDemultiplexer(new FileInputStream(path));
                            decoderAAC = new Decoder(adtsDemultiplexer.getDecoderSpecificInfo());
                        } catch (Exception ex) {
                            ex.printStackTrace();
                            postError(ex, 10016, context);
                        }
                    }

                    localFile = new RandomAccessFile(path, "r");

                    try {
                        size = localFile.length();
                    } catch (IOException e) {
                        e.printStackTrace();
                        postError(e, 10003, context);
                    }
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                    postError(e, 10004, context);
                }
                break;
            default:
//                //stream format
//                if (fileFormat == FileFormat.MP4_AAC) {
//                    try {
//                        String placeholderFile = context.getApplicationInfo().dataDir + "/temp.txt";
//                        mp4PlaceHolder = new File(placeholderFile);
//                        FileOutputStream fileOut = new FileOutputStream(mp4PlaceHolder);
//                        fileOut.write(0);
//                        fileOut.flush();
//                        fileOut.close();
//
//                        randomAccessFileStream = new RandomAccessFileStream(placeholderFile, "r");
//                        size = randomAccessFileStream.init(path, mUserAgent);
//                    } catch (Exception ex) {
//                        postError(ex, 20014);
//                        ex.printStackTrace();
//                        //TODO probably use continueBufferingInputStream if there was error
//                    }
//                } else {
//                    try {
//                        mHttpURLConnection = new OkUrlFactory(httpclient).open(new URL(path));
//                        mHttpURLConnection.setRequestProperty("User-Agent", mUserAgent);
//                        mHttpURLConnection.setRequestProperty("Range",
//                                "bytes=" + totalReadSamples + "-");    //continue!!!
//                        mHttpURLConnection.setConnectTimeout(5000);
//                        mHttpURLConnection.setReadTimeout(5000);
//                        remoteInputStream = mHttpURLConnection.getInputStream();
//                        size = mHttpURLConnection.getContentLength();
//
//
//                        if (fileFormat == FileFormat.ADTS_AAC) {
//                            adtsDemultiplexer = new ADTSDemultiplexer(remoteInputStream);
//                            decoderAAC = new Decoder(adtsDemultiplexer.getDecoderSpecificInfo());
//                        } else {
//                            //setup buffer
//                            streamBuffer = new StreamBuffer();
//                            streamBuffer.init(context, size);
//                        }
//
//                    } catch (IOException e2) {
//                        e2.printStackTrace();
//                        continueBufferingInputStream(context, totalReadSamples, "thDecode IOException e2");
//                    }
//                }
                break;
        }
        /* END OF SWITCH */

        decoder = new Mpg123Decoder(fileFormat.value(), true);

        if (fileFormat == AudioCompressor.FileFormat.MP4_AAC) {
            Alog.addLogMessage(TAG, "Opening MP4Container");

            try {
                switch (fileType) {
                    case FILE_TYPE_CONTENT:
                        containerMP4AAC = new MP4Container(contentStream);
                        break;
                    case FILE_TYPE_LOCAL:
                        containerMP4AAC = new MP4Container(localFile);
                        break;
                    default:
                        containerMP4AAC = new MP4Container(randomAccessFileStream);
                        break;
                }
                Alog.v(TAG, "  containerMP4AAC created");

                Movie movie = containerMP4AAC.getMovie();
                Alog.v(TAG, "  got Movie");

                List<Track> tracks = movie.getTracks(net.sourceforge.jaad.mp4.api.Type.VIDEO);
                Alog.addLogMessage(TAG, "  got " + tracks.size() + " movie tracks");

                aacDuration = movie.getDuration();
                duration = (int) (1000.0 * aacDuration);
                Alog.addLogMessage(TAG, "duration = " + duration);
                tracks = movie.getTracks(net.sourceforge.jaad.mp4.api.AudioTrack.AudioCodec.AAC);
                Alog.addLogMessage(TAG, "  got " + tracks.size() + " AAC tracks");
                if (tracks.size() > 0) {
                    Alog.addLogMessage(TAG, "  Creating AAC decoder  duration: " + aacDuration);
                    trackAAC = tracks.get(0);
                    decoderAAC = new Decoder(trackAAC.getDecoderSpecificInfo());
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                postError(ex, 30014, context);
                return;
            }
        }

        Alog.addLogMessage(TAG, "initDecoder: end");
        Alog.saveLogs(context);
        /* END OF: if (fileFormat == AudioEncoder.FileFormat.MP4_AAC)*/
    }

    /* Helpers*/

    private void initDecoderVariables() {
        percentRead = -1;

        rawsamples = new byte[BUFFERSIZE];
        rawbytesRead = 0;
        totalReadSamples = 0;
        readSamples = 0;
        modifiedSamples = new byte[1];
        samples = new byte[OUTBUFFERSIZE]; //decoded
        totalDecoded = 0;
        pcmDataBuffer = new PcmDataBuffer();
    }

    private void setDataSource(Context context, Uri contentUri) {
        String scheme = contentUri.getScheme();
        if (scheme == null || scheme.equals("file")) {
            setDataSource(contentUri.getPath());
            return;
        }

        if (!FILE_TYPE_CONTENT.equals(contentUri.getScheme())) {
            throw new IllegalArgumentException("Scheme is not CONTENT, uri: " + contentUri);
        }

        mContentUri = contentUri;
        SetSourceType(FILE_TYPE_CONTENT);
    }

    private void setDataSource(String path) throws IllegalStateException {
        fileFormat = formatFromPath(path);
        Alog.addLogMessage(TAG, "File format: " + fileFormat + " from path: " + path);

        this.path = path;
        SetSourceType(FILE_TYPE_LOCAL);
    }

    private void SetSourceType(String fileType) {
        this.fileType = fileType;
    }

    private static AudioCompressor.FileFormat formatFromPath(String path) {
        Alog.v(TAG, "formatFromPath: path: " + path);

        String lowercasePath = path.toLowerCase();

        if (lowercasePath.endsWith(".mp3") || lowercasePath.contains(".mp3?"))
            return AudioCompressor.FileFormat.MP3;
        if (lowercasePath.endsWith(".ogg") || lowercasePath.contains(".ogg?"))
            return AudioCompressor.FileFormat.OGG;
        if (lowercasePath.endsWith(".flac") || lowercasePath.contains(".flac?"))
            return AudioCompressor.FileFormat.FLAC;
        if (lowercasePath.endsWith(".mp4") || lowercasePath.contains(".mp4?"))
            return AudioCompressor.FileFormat.MP4_AAC;
        if (lowercasePath.endsWith(".m4a") || lowercasePath.contains(".m4a?"))
            return AudioCompressor.FileFormat.MP4_AAC;
        if (lowercasePath.endsWith(".aac") || lowercasePath.contains(".aac?"))
            return AudioCompressor.FileFormat.ADTS_AAC;
        return AudioCompressor.FileFormat.NONE;
    }

    private static AudioCompressor.FileFormat formatFromMimeType(String mimeType) {
        Alog.addLogMessage(TAG, "formatFromMimeType: mimeType: " + mimeType);

        if (mimeType == null) {
            return AudioCompressor.FileFormat.NONE;
        }

        String lowercaseMimeType = mimeType.toLowerCase();

        if (lowercaseMimeType.contains("mp3")) {
            return AudioCompressor.FileFormat.MP3;
        }
        if (lowercaseMimeType.contains("ogg")) {
            return AudioCompressor.FileFormat.OGG;
        }
        if (lowercaseMimeType.contains("flac")) {
            return AudioCompressor.FileFormat.FLAC;
        }
        if (lowercaseMimeType.contains("mp4")) {
            return AudioCompressor.FileFormat.MP4_AAC;
        }
        if (lowercaseMimeType.contains("m4a")) {
            return AudioCompressor.FileFormat.MP4_AAC;
        }
        if (lowercaseMimeType.contains("aac")) {
            return AudioCompressor.FileFormat.ADTS_AAC;
        }
        return AudioCompressor.FileFormat.NONE;
    }

    private void CloseFiles() {
        Alog.addLogMessage(TAG, "CloseFiles: ");
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

        if (decoder != null) {
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
        if (fileFormat == AudioCompressor.FileFormat.MP4_AAC || fileFormat == AudioCompressor.FileFormat.ADTS_AAC)
            return aacSampleRate;
        else if (decoder != null)
            return decoder.getRate(decoder.buffer);
        else
            return -1;
    }

    private int GetNumChannels() {
        if (fileFormat == AudioCompressor.FileFormat.MP4_AAC || fileFormat == AudioCompressor.FileFormat.ADTS_AAC)
            return aacChannels;
        else if (decoder != null)
            return decoder.getNumChannels(decoder.buffer);
        else
            return -1;
    }

    private int GetEncoding() {
        // return 16;
        if (fileFormat == AudioCompressor.FileFormat.MP4_AAC || fileFormat == AudioCompressor.FileFormat.ADTS_AAC)
            return 208;
        else if (decoder != null && decoder.getEncoding(decoder.buffer) != -1)
            return decoder.getEncoding(decoder.buffer);
        else
            return -1;
    }

    /**
     * @param e
     * @param what error above 30000 will indicate that it's not network related so we don't need to switch user agent
     */
    private void postError(Exception e, final int what, Context cotext) throws Exception {
        //error above 30000 will indicate that it's not network related so we don't need to switch user agent
        Alog.addLogMessageError(TAG, "AudioEncoder error. Error code: " + what);
        Alog.e(TAG, "AudioEncoder error. Error code: " + what, e, true);
        Alog.saveLogs(cotext);
        CloseFiles();

        throw e;
    }

    public long getUncompressedAudioSize() {
        return mUncompressedAudioSize;
    }

    public long getCompressedAudioSize() {
        return mCompressedAudioSize;
    }

    /**
     * start decoding file to get numOfChannels and rate
     */
    private void initChannelsAndRate(Context context) throws Exception {
        Alog.v(TAG, "initChannelsAndRate: ");
        /* read stream and decode */
        mChannels = -1;
        mRate = -1;
        byte rawsamples[] = new byte[BUFFERSIZE];
        int rawbytesRead = 0;
        totalReadSamples = 0;
        int readSamples = 0;
        byte[] samples = new byte[OUTBUFFERSIZE]; //decoded

        while (rawbytesRead >= 0) {
            if (fileFormat == FileFormat.MP4_AAC || fileFormat == FileFormat.ADTS_AAC) {
                try {
                    Alog.v(TAG, "readNextFrame");
                    byte[] frameAAC = null;
                    if (fileFormat == FileFormat.MP4_AAC) {
                        Frame frame = trackAAC.readNextFrame();
                        if (frame == null) {
                            decoder.setFileEnd(decoder.buffer);
                            readSamples = decoder.navOutputSamples(samples, decoder.buffer);
                            Alog.v(TAG, "frame == null");
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
                        Alog.v(TAG, "frameAAC == null");
                        break;
                    }

                    Alog.v(TAG, "decodeFrame");
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
                } catch (Exception ex) {
                    postError(ex, 30001, context);
                    break;
                }
            } else {
                //different format than AAC
                switch (fileType) {
                    case FILE_TYPE_CONTENT:
                        try {
                            rawbytesRead = contentStream.read(rawsamples, 0, BUFFERSIZE);
                            totalReadSamples += rawbytesRead;
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        break;
                    case FILE_TYPE_LOCAL:
                        try {
                            // save to rawsamples
                            rawbytesRead = localFile.read(rawsamples, 0, BUFFERSIZE);
                            Alog.v(TAG, "currentPositionMs localFile pointer: " + localFile.getFilePointer());

                            totalReadSamples += rawbytesRead;
                        } catch (IOException e) {
                            Alog.e(TAG, "compress: read error " + e.getMessage(), e, true);
                            e.printStackTrace();
                        }
                        break;
                    default:
                        break;
                }

                if (rawbytesRead < 0) {
                    //THIS will end while loop
                    //end of file
                    Alog.addLogMessage(TAG, "Completed rawbytesread: " + rawbytesRead);
                    decoder.setFileEnd(decoder.buffer);
                    // NOTE: Don't break here because anymore
                    //       external/internal buffers of FLAC or Vorbis decoders might still have data in them
                    // break;
                } else {
                    // feed with raw samples which are not decoded, rawbytesRead - amount
                    decoder.navFeedSamples(rawsamples, rawbytesRead, decoder.buffer);
                }
                // get decoded data which are stored in samples
                readSamples = decoder.navOutputSamples(samples, decoder.buffer);
            }

            if (readSamples > 0) {
                Alog.v(TAG, "rate: " + GetRate() + " encoding: " + GetEncoding());
                if (mChannels <= 0) {
                    mChannels = GetNumChannels();
                    Alog.v(TAG, "initChannelsAndRate: mChannels: " + mChannels);
                }
                if (mRate <= 0) {
                    mRate = GetRate();
                    Alog.v(TAG, "initChannelsAndRate: mRate: " + mRate);
                }
                // channels + rate loaded
                if (mChannels > 0 && mRate > 0) {
                    //go to the beginning
//                    if(localFile != null) {
//                        localFile.seek(0);
//                    }
//                    if(contentStream != null){
//                        contentStream.reset();
//                    }
                    CloseFiles();
                    break;
                }
            }

        }
    }

    /**
     * Read encoded data from buffer if possible, otherwise read new data from file -> decode
     * -> write to buffer -> read from buffer
     *
     * @param buffer the buffer into which the data is read.
     * @param length the maximum number of bytes read.
     * @return decoded (raw) data
     */
    private int readDataForEncoding(Context context, byte buffer[], int length) throws Exception {
        //check if data in buffer, otherwise decode
        if (pcmDataBuffer.hasDataInBuffer()) {
            // read from buffer
            return pcmDataBuffer.read(buffer, length);
        } else {
            // decode new data to buffer
            while (rawbytesRead >= 0) {
                if (fileFormat == FileFormat.MP4_AAC || fileFormat == FileFormat.ADTS_AAC) {
                    try {
                        Alog.v(TAG, "readNextFrame");
                        byte[] frameAAC = null;
                        if (fileFormat == FileFormat.MP4_AAC) {
                            Frame frame = trackAAC.readNextFrame();
                            if (frame == null) {
                                decoder.setFileEnd(decoder.buffer);
                                readSamples = decoder.navOutputSamples(samples, decoder.buffer);

                                Alog.v(TAG, "frame == null");
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

                            Alog.v(TAG, "frameAAC == null");
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
                        postError(ex, 30001, context);
                        break;
                    }
                } else {
                    //different format than AAC
                    switch (fileType) {
                        case FILE_TYPE_CONTENT:
                            try {
                                rawbytesRead = contentStream.read(rawsamples, 0, BUFFERSIZE);
                                totalReadSamples += rawbytesRead;
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            break;
                        case FILE_TYPE_LOCAL:
                            try {
                                // save to rawsamples
                                rawbytesRead = localFile.read(rawsamples, 0, BUFFERSIZE);
                                Alog.v(TAG, "currentPositionMs localFile pointer: " + localFile.getFilePointer());
                                totalReadSamples += rawbytesRead;
                            } catch (IOException e) {
                                Alog.e(TAG, "compress: read error " + e.getMessage(), e, true);
                                e.printStackTrace();
                            }
                            break;
                        default:
                            break;
                    }

                    if (rawbytesRead < 0) {
                        //THIS will end while loop
                        //end of file
                        Alog.addLogMessage(TAG, "Completed rawbytesread: " + rawbytesRead);
                        decoder.setFileEnd(decoder.buffer);
                        // NOTE: Don't break here because anymore
                        //       external/internal buffers of FLAC or Vorbis decoders might still have data in them
                        // break;
                    } else {
                        // feed with raw samples which are not decoded, rawbytesRead - amount
                        decoder.navFeedSamples(rawsamples, rawbytesRead, decoder.buffer);
                    }

                    // get decoded data which are stored in samples
                    readSamples = decoder.navOutputSamples(samples, decoder.buffer);
                }
                totalDecoded += readSamples;

                long percentCurr = ProgressUtils.getPercentage(totalReadSamples, size);
                if (percentCurr != percentRead) {
                    percentRead = percentCurr;
                    EventBus.getDefault().post(new Events.CompressionProgress(percentCurr));
                    Alog.addLogMessage(TAG, "filesize: " + size + " total bytes read: " + totalReadSamples + " total decoded: " + totalDecoded + " progress: " + percentCurr + "%");
                    Alog.logUsedMemorySize();
                    Alog.saveLogs(context);
                }

                if (readSamples > 0) {
                    Alog.v(TAG, "rate: " + GetRate() + " encoding: " + GetEncoding());
                    // decoded sample, add to buffer
                    pcmDataBuffer.write(samples, readSamples);
                    Alog.v(TAG, "output samples: " + readSamples);
                    // decoded enough or we are at the end of file and there is remaining data
                    if (pcmDataBuffer.hasDataInBuffer() || (rawbytesRead < 0 && pcmDataBuffer.size() > 0)) {
                        //enough data for encoder
                        return pcmDataBuffer.read(buffer, length);
                    }
                }
            }
        }


        if (rawbytesRead < 0) {
            //end of file, return -1, this is handled in callback
            Alog.v(TAG, "readDataForEncoding");
            return -1;
        }

        Alog.v(TAG, "unreachable");
        return 0;
    }

}
