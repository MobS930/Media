package fm.player.playeraudio;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnClick;
import fm.player.playerandroidaudio.R;
import fm.player.utils.FileUtils;

/**
 * Allow user to select some episodes from player fm
 */
public class SelectPlayerFMEpisode extends AppCompatActivity {


    private static final String[] SERIES_SLUGS = new String[]{
            "Select Series Slug",
            "evolution-talk",
            "this-week-in-tech-mp3",
            "reply-all",
            "radiolab-from-wnyc",
            "upvoted-by-reddit",
            "the-tim-ferriss-show",
            "kwlug-audio-podcast-flac-feed",
            "tedtalks-business",
            "above-beyond-group-therapy"
    };

    @Bind(R.id.list)
    ListView mListView;

    @Bind(R.id.progressBar)
    ProgressBar mProgressBar;

    @Bind(R.id.seriesId)
    EditText mSeriesIDEditText;

    @Bind(R.id.spinner)
    Spinner mSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_select_episode);
        ButterKnife.bind(this);

        mProgressBar.setVisibility(View.GONE);
        mListView.setVisibility(View.GONE);
        mSpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, SERIES_SLUGS));
        mSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    return;
                }
                mSeriesIDEditText.setText(SERIES_SLUGS[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });


    }


    @OnClick(R.id.loadSeriesEpisodes)
    void loadSeriesEpisodes() {
        if (mSeriesIDEditText.getText() != null) {
            String seriesId = mSeriesIDEditText.getText().toString();
            new LoadSeriesEpisodesAsyncTask(seriesId).execute();
        }
    }

    private class LoadSeriesEpisodesAsyncTask extends AsyncTask<Void, Void, Series> {

        private String seriesId;

        public LoadSeriesEpisodesAsyncTask(String seriesId) {
            this.seriesId = seriesId;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            mProgressBar.setVisibility(View.VISIBLE);
            mListView.setVisibility(View.GONE);
        }

        @Override
        protected Series doInBackground(Void... params) {
            Series series = null;
            InputStream is = null;
            String seriesUrl = "https://player.fm/series/" + seriesId + ".json?episode_detail=full";
            // Only display the first 500 characters of the retrieved
            // web page content.
            HttpURLConnection conn = null;
            try {
                URL url = new URL(seriesUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setReadTimeout(10000 /* milliseconds */);
                conn.setConnectTimeout(15000 /* milliseconds */);
                conn.setRequestMethod("GET");
                conn.setDoInput(true);
                conn.setInstanceFollowRedirects(true);
                // Starts the query
                conn.connect();
                int response = conn.getResponseCode();
                if (response >= 200 && response <= 299) {
                    is = conn.getInputStream();
                    InputStreamReader isr = new InputStreamReader(is);
                    series = new Gson().fromJson(isr, Series.class);
                }

                // Makes sure that the InputStream is closed after the app is
                // finished using it.
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (conn != null) {
                    conn.disconnect();
                }
            }

            return series;
        }

        @Override
        protected void onPostExecute(Series series) {
            super.onPostExecute(series);

            if (series != null) {
                mProgressBar.setVisibility(View.GONE);
                mListView.setVisibility(View.VISIBLE);

                mListView.setAdapter(new EpisodesAdapter(getBaseContext(), series.episodes));
            } else {
                mProgressBar.setVisibility(View.GONE);
                mListView.setVisibility(View.GONE);
                Toast.makeText(getBaseContext(), "Error", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private class EpisodesAdapter extends ArrayAdapter<Episode> {

        public EpisodesAdapter(Context context, List<Episode> objects) {
            super(context, R.layout.episode_item, R.id.title, objects);
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            View row;
            if (convertView == null) {
                LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(
                        Context.LAYOUT_INFLATER_SERVICE);
                row = (View) inflater.inflate(R.layout.episode_item, null);
            } else {
                row = (View) convertView;
            }
            Episode episode = getItem(position);
            ((TextView) row.findViewById(R.id.title)).setText(episode.title);
            ((TextView) row.findViewById(R.id.url)).setText(episode.url);
            row.findViewById(R.id.download).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Toast.makeText(getContext(), "Starting download...", Toast.LENGTH_LONG).show();
                    new GetFileName().execute(getItem(position).url);
                }
            });

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Episode episode = getItem(position);
                    Intent data = new Intent();
                    data.putExtra("url", episode.url);
                    setResult(RESULT_OK, data);
                    finish();
                }
            });

            return row;
        }

    }

    class GetFileName extends AsyncTask<String, Integer, String> {

        URL url;

        protected String doInBackground(String... urls) {

            String filename = null;
            try {
                url = new URL(urls[0]);
                String cookie = CookieManager.getInstance().getCookie(urls[0]);
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestProperty("Cookie", cookie);
                con.setRequestMethod("HEAD");
                con.setInstanceFollowRedirects(false);
                con.connect();

                String contentDisposition = con.getHeaderField("Content-Disposition");
                String contentLocation = con.getHeaderField("Content-Location");

                filename = FileUtils.chooseFilename(url.toString(), contentDisposition, contentLocation);

            } catch (MalformedURLException e1) {
                e1.printStackTrace();
            } catch (IOException e) {
            }
            return filename;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected void onPostExecute(String result) {
            if (result != null) {
                DownloadManager dm = (DownloadManager) getApplicationContext().getSystemService(DOWNLOAD_SERVICE);
                DownloadManager.Request request = new DownloadManager.Request(
                        Uri.parse(url.toString()));
                request.addRequestHeader("Content-Type", "application/octet-stream");
                File folder = new File(Environment.getExternalStorageDirectory(), "PlayerAudioTest");
                request.setDestinationUri(Uri.fromFile(new File(folder, result)));
                dm.enqueue(request);
            }
        }
    }
}
