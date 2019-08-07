package fm.player.utils;

import android.net.Uri;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by mac on 19/08/15.
 */
public class FileUtils {

    private static final String TAG = "FileUtils";

    /** Regex used to parse content-disposition headers */
    private static final Pattern CONTENT_DISPOSITION_PATTERN = Pattern
            .compile("attachment;\\s*filename\\s*=\\s*\"([^\"]*)\"");

    public static String chooseFilename(String url, String contentDisposition, String contentLocation) {
        String filename = null;

        // If we couldn't do anything with the hint, move toward the content
        // disposition
        if (filename == null && contentDisposition != null) {
            filename = parseContentDisposition(contentDisposition);
            if (filename != null) {
                Log.v(TAG, "getting filename from content-disposition");
                int index = filename.lastIndexOf('/') + 1;
                if (index > 0) {
                    filename = filename.substring(index);
                }
            }
        }

        // If we still have nothing at this point, try the content location
        if (filename == null && contentLocation != null) {
            String decodedContentLocation = Uri.decode(contentLocation);
            if (decodedContentLocation != null && !decodedContentLocation.endsWith("/")
                    && decodedContentLocation.indexOf('?') < 0) {
                Log.v(TAG, "getting filename from content-location");
                int index = decodedContentLocation.lastIndexOf('/') + 1;
                if (index > 0) {
                    filename = decodedContentLocation.substring(index);
                } else {
                    filename = decodedContentLocation;
                }
            }
        }

        // If all the other http-related approaches failed, use the plain uri
        if (filename == null) {
            String decodedUrl = Uri.decode(url);
            if (decodedUrl != null && !decodedUrl.endsWith("/")) {
                int parametersIndex = decodedUrl.indexOf('?');
                if (parametersIndex > 0) {
                    decodedUrl = decodedUrl.substring(0, parametersIndex);
                }

                int index = decodedUrl.lastIndexOf('/') + 1;
                if (index > 0) {
                    Log.v(TAG, "getting filename from uri");
                    filename = decodedUrl.substring(index);
                }
            }
        }

        // Finally, if couldn't get filename from URI, get a generic filename
        if (filename == null) {
            Log.v(TAG, "using default filename");
            filename = "downloading";
        }

        // The VFAT file system is assumed as target for downloads.
        // Replace invalid characters according to the specifications of VFAT.
        filename = replaceInvalidVfatCharacters(filename);

        //replace invalid java file characters
        filename = filename.replaceAll("[^a-zA-Z0-9.-]", "_");

        // if filename is too long we need to make it shorter
        if (filename.length() > 64) {
            filename = filename.substring(filename.length() - 64);
        }

        return filename;
    }

    /*
 * Parse the Content-Disposition HTTP Header. The format of the header is
 * defined here: http://www.w3.org/Protocols/rfc2616/rfc2616-sec19.html This
 * header provides a filename for content that is going to be downloaded to
 * the file system. We only support the attachment type.
 */
    private static String parseContentDisposition(String contentDisposition) {

        try {
            Matcher m = CONTENT_DISPOSITION_PATTERN.matcher(contentDisposition);
            if (m.find()) {
                return m.group(1);
            }
        } catch (IllegalStateException ex) {
            // This function is defined as returning null when it can't parse
            // the header
            ex.printStackTrace();
        }
        return null;
    }

    /**
     * Replace invalid filename characters according to specifications of the
     * VFAT.
     *
     * @note Package-private due to testing.
     */
    private static String replaceInvalidVfatCharacters(String filename) {
        final char START_CTRLCODE = 0x00;
        final char END_CTRLCODE = 0x1f;
        final char QUOTEDBL = 0x22;
        final char ASTERISK = 0x2A;
        final char SLASH = 0x2F;
        final char COLON = 0x3A;
        final char LESS = 0x3C;
        final char GREATER = 0x3E;
        final char QUESTION = 0x3F;
        final char BACKSLASH = 0x5C;
        final char BAR = 0x7C;
        final char DEL = 0x7F;
        final char UNDERSCORE = 0x5F;

        StringBuffer sb = new StringBuffer();
        char ch;
        boolean isRepetition = false;
        for (int i = 0; i < filename.length(); i++) {
            ch = filename.charAt(i);
            if ((START_CTRLCODE <= ch && ch <= END_CTRLCODE) || ch == QUOTEDBL || ch == ASTERISK || ch == SLASH
                    || ch == COLON || ch == LESS || ch == GREATER || ch == QUESTION || ch == BACKSLASH || ch == BAR
                    || ch == DEL) {
                if (!isRepetition) {
                    sb.append(UNDERSCORE);
                    isRepetition = true;
                }
            } else {
                sb.append(ch);
                isRepetition = false;
            }
        }
        return sb.toString();
    }
}
