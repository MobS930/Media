package fm.player.eventsbus;

public class Events {

    public static class EpisodeCompressed {

        public String episodeId;
        public String downloadedPath;

        public EpisodeCompressed(String episodeId, String downloadedPath) {
            this.episodeId = episodeId;
            this.downloadedPath = downloadedPath;
        }
    }

    public static class CompressionProgress {
        public long progressPercentage;

        public CompressionProgress(long progressPercentage) {
            this.progressPercentage = progressPercentage;
        }
    }
}