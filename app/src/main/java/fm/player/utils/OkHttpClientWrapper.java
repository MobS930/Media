package fm.player.utils;

import android.content.Context;
import android.os.Build;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.internal.Util;

import java.security.GeneralSecurityException;

import javax.net.ssl.SSLContext;

/**
 * Created by mac on 09/09/2013.
 */
public class OkHttpClientWrapper {
    private static final String TAG = "OkHttpClientWrapper";
    //maybe this was causing problem with battery
    static OkHttpClient sClient;
    static OkHttpClient sImagesClient;

    /**
     * Create okhttpclient instance with some settings which fix issue when used with default httpurlconnection
     *
     * @return
     */
    public static OkHttpClient getApiOkHttpClient(Context context) {
        if (sClient == null) {
            sClient = new OkHttpClient();
            SSLContext sslContext;
            try {
                sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, null, null);
            } catch (GeneralSecurityException e) {
                throw new AssertionError(); // The system has no TLS. Just give up.
            }
            sClient.setSslSocketFactory(sslContext.getSocketFactory());

            //setup cache
//            if (!PremiumFeatures.fetch(context)) {
//                // if user doesn't have fetch feature, we can use local http cache, otherwise we rather get response from server
//                File httpCacheDirectory = new File(context.getCacheDir(), "okhttpcache");
//                int cacheSize = 20 * 1024 * 1024; // 20 MB
//                Cache cache = new Cache(httpCacheDirectory, cacheSize);
//
//                sClient.setCache(cache);
//            }
        }

        return sClient;
    }

    public static OkHttpClient getImagesOkHttpClient() {
        if (sImagesClient == null) {
            sImagesClient = new OkHttpClient();
            SSLContext sslContext;
            try {
                sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, null, null);
            } catch (GeneralSecurityException e) {
                throw new AssertionError(); // The system has no TLS. Just give up.
            }
            sImagesClient.setSslSocketFactory(sslContext.getSocketFactory());
        }
        return sImagesClient;
    }

    /**
     * Http client for servers which we don't control
     *
     * @return
     */
    public static OkHttpClient getUniqueOkHttpClientNonControledServerInstance() {
        OkHttpClient client = new OkHttpClient();
        SSLContext sslContext;
        try {
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, null, null);
        } catch (GeneralSecurityException e) {
            throw new AssertionError(); // The system has no TLS. Just give up.
        }
        if (Build.VERSION.SDK_INT >= 16 && Build.VERSION.SDK_INT < 21) {
            Alog.addLogMessage(TAG, "use OkHttpTls12SocketFactory");
            //some 4.4 devices support TLS 1.2 however it is not enabled
            //try to enable it
            //to fix stream + download errors ("server does not support streaming") on some series
            client.setSslSocketFactory(new OkHttpTls12SocketFactory(sslContext.getSocketFactory()));
        } else {
            client.setSslSocketFactory(sslContext.getSocketFactory());
        }
        //some servers have problem with http2 so because we don't control them, we rather use http1
        client.setProtocols(Util.immutableList(Protocol.HTTP_1_1));
        return client;
    }

    public static OkHttpClient getUniqueOkHttpClientPlayerFMServerInstance() {
        OkHttpClient client = new OkHttpClient();
        SSLContext sslContext;
        try {
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, null, null);
        } catch (GeneralSecurityException e) {
            throw new AssertionError(); // The system has no TLS. Just give up.
        }
        client.setSslSocketFactory(sslContext.getSocketFactory());
        return client;
    }
}
