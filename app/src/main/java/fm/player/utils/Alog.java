package fm.player.utils;


import android.content.Context;
import android.os.Looper;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Handles Logs
 */
public class Alog {

    public static void v(String TAG, String msg) {
            if (msg == null) msg = "error message null";
            Log.v(TAG, msg);
    }

    public static void d(String TAG, String msg) {
            if (msg == null) msg = "error message null";
            Log.d(TAG, msg);
    }

    public static void i(String TAG, String msg) {
            if (msg == null) msg = "error message null";
            Log.i(TAG, msg);
    }

    public static void w(String TAG, String msg) {
            if (msg == null) msg = "error message null";
            Log.w(TAG, msg);
    }

    public static void e(String TAG, String msg) {
        if (msg == null) msg = "error message null";

            Log.e(TAG, msg);
        addLogMessage(TAG, msg);
    }

    /**
     * Write error into log and send exception to bug tracking
     *
     * @param TAG
     * @param msg
     * @param ex
     */
    public static void e(String TAG, String msg, Exception ex) {
        if (msg == null) msg = "error message null";
            Log.e(TAG, msg);
        ex.printStackTrace();
        addLogMessage(TAG, msg);
    }

    public static void e(Context context, String TAG, String msg, Exception ex) {
        if (msg == null) msg = "error message null";
            Log.e(TAG, "" + msg);

            try {
                ReportExceptionHandler.reportHandledException(context, msg, ex);
            } catch (Exception e) {
                e.printStackTrace();
            }
        ex.printStackTrace();

        addLogMessage(TAG, msg);
    }

    /**
     * Write error into log and send exception to bug tracking
     *
     * @param TAG
     * @param msg
     * @param ex
     */
    public static void e(String TAG, String msg, Throwable ex) {
        if (msg == null) msg = "error message null";
//        if (BuildConfig.IS_DEBUG_VERSION) {
//            Log.e(TAG, msg);
//        }
        if (ex != null) {
            ex.printStackTrace();
            msg = msg + "\nstacktrace: " + getStacktrace(ex);
        }
        addLogMessageError(TAG, msg);
    }

    public static void e(String TAG, String msg, Throwable ex, boolean report) {
        if (msg == null) msg = "error message null";
        if (ex != null) {
            ex.printStackTrace();
            msg = msg + "\nstacktrace: " + getStacktrace(ex);
        }

        if (report) {
            try {
                ReportExceptionHandler.reportHandledException(msg, ex, true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        addLogMessageError(TAG, msg);
    }

    private static String getStacktrace(Throwable ex) {
        String exceptionAsString = null;
        try {
            StringWriter sw = new StringWriter();
            ex.printStackTrace(new PrintWriter(sw));
            exceptionAsString = sw.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return exceptionAsString;
    }

    public static void threadInfo(String TAG, String message, Looper threadLooper) {
            Log.v(TAG, "thread info message: " + message + " threadName: " + (threadLooper != null ? threadLooper.getThread().getName() : " looper is null ") + " isMainThread: " + (threadLooper == Looper.getMainLooper()));
    }

    public static void threadInfo(String TAG, String message, Thread thread) {
            Log.v(TAG, "thread info message: " + message + " threadName: " + (thread != null ? thread.getName() : " thread is null "));
    }


    /**
     * Add message to logs batch. Batch is inserted into db later
     *
     * @param message
     */
    public static void addLogMessage(String TAG, String message) {
        if (message == null) message = "error message null";
        Alog.v(TAG, message);
    }

    /**
     * Add message to logs batch. Batch is inserted into db later
     *
     * @param message
     */
    public static void addLogMessageError(String TAG, String message) {
        if (message == null) message = "error message null";
        Alog.e(TAG, message);
    }

    /**
     * Save logs after
     */
    public static void saveLogs(Context context) {
    }

    public static void logUsedMemorySize() {

        long toMegaBytes = 1048576L;
        long freeSize = 0L;
        long totalSize = 0L;
        long usedSize = -1L;
        long maxSize = 0L;
        try {
            Runtime info = Runtime.getRuntime();
            freeSize = info.freeMemory();
            totalSize = info.totalMemory();
            usedSize = totalSize - freeSize;
            maxSize = info.maxMemory();
            StringBuilder builder = new StringBuilder();
            builder.append("Max ").append(maxSize / toMegaBytes).append("MB ");
            builder.append("Total: ").append(totalSize / toMegaBytes).append("MB ");
            builder.append("Used ").append(usedSize / toMegaBytes).append("MB ");
            builder.append("Free ").append(freeSize / toMegaBytes).append("MB ");
            addLogMessage("Memory", builder.toString());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
