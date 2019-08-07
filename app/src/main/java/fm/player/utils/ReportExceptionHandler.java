package fm.player.utils;

import android.content.Context;

import java.io.PrintWriter;
import java.io.StringWriter;


/**
 * Created by mac on 13/02/2014.
 */
public class ReportExceptionHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = ReportExceptionHandler.class.getSimpleName();

    private Context mContext;
    private String mMessage;
    private ReportExceptionHandlerCallback mCallback;
    private boolean mReportToCrashlytics;

    public interface ReportExceptionHandlerCallback {
        public void onUncaughtException();
    }

//    public ReportExceptionHandler(Context context, String message) {
//        mContext = context;
//        mMessage = message;
//    }

    public ReportExceptionHandler(Context context, String message, ReportExceptionHandlerCallback callback, boolean reportToCrashlytics) {
        mContext = context;
        mMessage = message;
        mCallback = callback;
        mReportToCrashlytics = reportToCrashlytics;
    }


    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        if (mCallback != null) {
            mCallback.onUncaughtException();
        }

        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        String exceptionAsString = sw.toString();
        reportHandledException(mContext, mMessage + " " + exceptionAsString, new Exception(mMessage + " " + exceptionAsString, ex), mReportToCrashlytics);

    }

    /**
     * Send report to crashlytics and write it to logs
     *
     * @param context
     * @param message
     * @param ex
     */
    public static void reportHandledException(Context context, String message, Exception ex, boolean reportToCrashlytics) {
        try {
//            if (reportToCrashlytics) {
//                Crashlytics.logException(new Exception(message, ex));
//            }
//
//            ContentValues values = new ContentValues();
//            values.put(LogsTable.CREATED_AT, System.currentTimeMillis());
//            values.put(LogsTable.TAG, "reportException");
//            values.put(LogsTable.MESSAGE, new Exception(message, ex).getMessage());
//            context.getContentResolver().insert(ApiContract.Logs.getLogsUri(), values);
//
            Alog.e(TAG, "handled exception message: " + message, ex);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void reportHandledException(Context context, String message, Exception ex) {
        reportHandledException(context, message, ex, true);
    }

    public static void reportHandledException(String message, Throwable ex) {
        reportHandledException(message, ex, true);
    }

    public static void reportHandledException(String message, Throwable ex, boolean reportToCrashlytics) {
        try {
//            if (reportToCrashlytics) {
//                Crashlytics.logException(new Exception(message, ex));
//            }

            Alog.e(TAG, "handled exception message: " + message, ex);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
