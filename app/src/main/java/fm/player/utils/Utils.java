package fm.player.utils;

/**
 * Created by mac on 02/04/15.
 */
public class Utils {

    // Hour in mmilliseconds 1000 * 60 * 60
    private static final int MILLISECONDS_HOUR = 3600000;
    // 60 * 1000
    private static final int MILLISECONDS_MINUTE = 60000;
    // 1000
    private static final int MILLISECONDS_SECOND = 1000;

    /**
     * Converts float value to readable string ie. 1.0 to 1x 2.5 to 2.5x
     *
     * @param playingSpeed
     * @return formatted string
     */
    public static String speedToString(float playingSpeed) {
        String speedText = String.format("%.1f", playingSpeed);
        if (speedText.endsWith(".0")) {
            speedText = speedText.replace(".0", "");
        }
        speedText += "x";
        return speedText;
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
        if (percentage > 99.5) {
            return 100;
        }

        // return percentage
        return (int) percentage;
    }

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

    public static String milliSecondsToTimer(long milliseconds) {
        return milliSecondsToTimer(milliseconds, 1f);
    }


}
