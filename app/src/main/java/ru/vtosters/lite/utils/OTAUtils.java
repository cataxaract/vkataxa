package ru.vtosters.lite.utils;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import okhttp3.*;
import org.json.JSONException;
import org.json.JSONObject;
import ru.vtosters.lite.di.singleton.VtOkHttpClient;
import ru.vtosters.sponsorpost.utils.GzipDecompressor;

import java.io.IOException;
import java.util.Locale;

public class OTAUtils {

    private static final String REPO = "cataxaract/vkataxa";
    private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String LATEST_RELEASE_COMMIT_URL = "https://api.github.com/repos/" + REPO + "/git/ref/tags/%s";
    private final OkHttpClient mClient = VtOkHttpClient.getInstance();
    private final OTAListener mListener;
    private JSONObject mReleaseJson;
    private JSONObject mCommitJson;
    private String mCommitSHA = "";

    public OTAUtils(OTAListener listener) {
        this.mListener = listener;
    }

    public void loadData(boolean isManualCheck) {
        Request release = new Request.a()
                .b(LATEST_RELEASE_URL)
                .a("Accept-Encoding", "gzip")
                .a();

        mClient.a(release).a(new Callback() {
            @Override
            public void a(Call call, IOException e) {
                Log.d("OTAHelper", "Error while getting latest release info: " + e);
                mListener.onUpdateError();
            }

            @Override
            public void a(Call call, Response response) throws IOException {
                try (Response result = response) {
                    setData(result, isManualCheck);
                } catch (JSONException | RuntimeException e) {
                    e.printStackTrace();
                    mListener.onUpdateError();
                }

            }
        });
    }

    void setData(Response response, boolean isManualCheck) throws JSONException, IOException {
        mReleaseJson = new JSONObject(GzipDecompressor.decompressResponse(response));

        String tag = mReleaseJson.getString("tag_name");
        Request commit = new Request.a()
                .b(String.format(LATEST_RELEASE_COMMIT_URL, Uri.encode(tag)))
                .a("Accept-Encoding", "gzip")
                .a();

        mClient.a(commit).a(new Callback() {
            @Override
            public void a(Call call, IOException e) {
                Log.d("OTAHelper", "Error while getting latest commit info: " + e);
                mListener.onUpdateError();
            }

            @Override
            public void a(Call call, Response response) {
                try (Response result = response) {
                    mCommitJson = new JSONObject(GzipDecompressor.decompressResponse(result));
                    mCommitSHA = mCommitJson.getJSONObject("object").getString("sha");
                    if (isNewVersion())
                        mListener.onUpdateApplied(isManualCheck);
                    else
                        mListener.onUpdateLatest(isManualCheck);
                } catch (JSONException | RuntimeException | IOException e) {
                    e.printStackTrace();
                    mListener.onUpdateError();
                }
            }
        });
    }

    public String getCommitSHA() {
        return mCommitSHA;
    }

    public boolean isNewVersion() {
        try {
            return !mCommitSHA.startsWith(VersionReader.getVersionBuild());
        } catch (Exception e) {
            return false;
        }
    }

    public String getNewVersionName() {
        if (mReleaseJson == null) return null;

        try {
            return mReleaseJson.getString("name");
        } catch (JSONException e) {
            return null;
        }
    }

    public String getUpdateDescription() {
        if (mReleaseJson == null) return null;

        try {
            return mReleaseJson.getString("body");
        } catch (JSONException e) {
            return null;
        }
    }

    public String getDownloadUrl() {
        if (mReleaseJson == null) return null;

        try {
            var assets = mReleaseJson.getJSONArray("assets");
            String fallback = null;

            for (int i = 0; i < assets.length(); i++) {
                var asset = assets.getJSONObject(i);
                String name = asset.optString("name", "");
                String url = asset.optString("browser_download_url", "");

                if (TextUtils.isEmpty(url)) continue;
                if (name.equalsIgnoreCase("VTLite.apk")) return url;
                if (name.toLowerCase(Locale.ROOT).endsWith(".apk") && fallback == null) {
                    fallback = url;
                }
            }

            return fallback;
        } catch (JSONException e) {
            return null;
        }
    }

    public interface OTAListener {

        void onUpdateApplied(boolean isManualCheck);

        void onUpdateError();

        void onUpdateLatest(boolean isManualCheck);
    }
}
