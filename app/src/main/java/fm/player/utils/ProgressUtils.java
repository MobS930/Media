package fm.player.utils;

/**
 * Handles operations for displaying playing progress - time, percentage...
 *
 * @author Martin Vandzura http://vandzi.com
 */
public class ProgressUtils {
    // Hour in mmilliseconds 1000 * 60 * 60
    private static final int MILLISECONDS_HOUR = 3600000;
    // 60 * 1000
    private static final int MILLISECONDS_MINUTE = 60000;
    // 1000
    private static final int MILLISECONDS_SECOND = 1000;

    /**
     * Creates readable time in format hh:mm:ss from millisecons
     *
     * @param milliseconds
     * @return hh:mm:ss
     */
    public static String milliSecondsToTimer(long milliseconds, float speed) {
        // hh:mm:ss
        if (speed > 0) {
            //calculate how many milliseconds left at current speed
            //time = distance / speed (in this case distance left time is milliseconds)
            milliseconds = (long) (milliseconds / speed);
        }

        // Convert total duration into time
        int hours = (int) (milliseconds / MILLISECONDS_HOUR);
        int minutes = (int) (milliseconds % MILLISECONDS_HOUR) / MILLISECONDS_MINUTE;
        int seconds = (int) ((milliseconds % MILLISECONDS_HOUR) % MILLISECONDS_MINUTE / MILLISECONDS_SECOND);

        StringBuilder builder = new StringBuilder();
        builder.append(hours).append(":");
        // Prepending 0 to seconds if it is one digit
        if (minutes < 10) {
            builder.append("0").append(minutes);
        } else {
            builder.append(minutes);
        }

        builder.append(":");

        if (seconds < 10) {
            builder.append("0").append(seconds);
        } else {
            builder.append(seconds);
        }

        // return timer string
        return builder.toString();
    }

    /**
     * Creates readable time in format hh:mm:ss from millisecons
     *
     * @param milliseconds
     * @return hh:mm:ss
     */
    public static String milliSecondsToTimer(long milliseconds) {
        return milliSecondsToTimer(milliseconds, 0);
    }

    public static int milliSecondsToMinutes(long milliseconds) {
        return milliSecondsToMinutes(milliseconds, 0);
    }

    public static int milliSecondsToMinutes(long milliseconds, float speed) {
        if (speed > 0) {
            //calculate how many milliseconds left at current speed
            //time = distance / speed (in this case distance left time is milliseconds)
            milliseconds = (long) (milliseconds / speed);
        }
        int minutes = (int) (milliseconds) / MILLISECONDS_MINUTE;
        int rest = (int) (milliseconds % MILLISECONDS_MINUTE);
        //round minutes up from 30seconds
        if (rest >= 30 * 1000) {
            minutes += 1;
        }

        return minutes;
    }


    /**
     * Get current percentage
     *
     * @param currentDuration
     * @param totalDuration
     * @return current percentage
     */
    public static int getProgressPercentage(long currentDuration, long totalDuration) {
        double percentage = (double) 0;

        long currentSeconds = (int) (currentDuration / 1000);
        long totalSeconds = (int) (totalDuration / 1000);

        // calculating percentage
        percentage = (((double) currentSeconds) / totalSeconds) * 100;

        //round this to 100 because sometimes 0 remaining time is no 100%
        //for short episodes, round progress up
        if (percentage > 99.5) {
            return 100;
        } else if (percentage >= 99 && totalSeconds < 700) {
            return 100;
        } else if (percentage >= 98 && totalSeconds < 400) {
            return 100;
        } else if (percentage >= 97.5 && totalSeconds < 300) {
            return 100;
        }

        // return percentage
        return (int) percentage;
    }

    /**
     * Change progress to timer(milliseconds)
     *
     * @param progress
     * @param totalDuration
     * @return current duration in milliseconds
     */
    public static int progressToTimer(int progress, int totalDuration) {
        return totalDuration / 100 * progress;
    }

    /**
     * Creates readable time in format hh:mm:ss or mm:ss from millisecons
     *
     * @param milliseconds
     * @return hh:mm:ss or mm:ss
     */
    public static String milliSecondsToTimerShorter(long milliseconds) {
        // hh:mm:ss

        StringBuilder builder = new StringBuilder();

        // Convert total duration into time
        int hours = (int) (milliseconds / MILLISECONDS_HOUR);
        int minutes = (int) (milliseconds % MILLISECONDS_HOUR) / MILLISECONDS_MINUTE;
        int seconds = (int) ((milliseconds % MILLISECONDS_HOUR) % MILLISECONDS_MINUTE / MILLISECONDS_SECOND);
        if (hours > 0) {
            builder.append(hours).append(":");
        }
        // Prepending 0 to seconds if it is one digit
        if (minutes < 10) {
            builder.append("0").append(minutes);
        } else {
            builder.append(minutes);
        }

        builder.append(":");

        if (seconds < 10) {
            builder.append("0").append(seconds);
        } else {
            builder.append(seconds);
        }

        // return timer string
        return builder.toString();
    }

    /**
     * Calculate progressbar width based on available space and episode duration
     *
     * @param availableWidth in pixels
     * @param duration       in seconds
     * @return progressbar width
     */
    public static int calculateProgressBarWidth(int availableWidth, double duration) {
        // set progressbar width. Value is in
        // range 5 to 90 mins
        double durationMinutes = duration / 60;
        if (durationMinutes < 5) {
            durationMinutes = 5;
        } else if (durationMinutes > 90) {
            durationMinutes = 90;
        }

        // width based on minutes
        double partFromTotal = (1 / (90 / durationMinutes));

        int width = (int) (availableWidth * partFromTotal);

        return width;
    }

    public static int getPercentage(int part, int total) {
        if (total == 0) return -1;
        return part * 100 / total;
    }

    public static long getPercentage(long part, long total) {
        if (total == 0) return -1;
        return part * 100 / total;
    }
}
