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
    private static final String LATEST_RELEASE_COMMIT_URL = "https://api.github.com/repos/" + REPO + "/commits/%s";
    private static final String COMPARE_URL = "https://api.github.com/repos/" + REPO + "/compare/%s...%s";
    private final OkHttpClient mClient = VtOkHttpClient.getInstance();
    private final OTAListener mListener;
    private JSONObject mReleaseJson;
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
                    var commitJson = new JSONObject(GzipDecompressor.decompressResponse(result));
                    mCommitSHA = commitJson.getString("sha");
                    resolveVersion(isManualCheck);
                } catch (JSONException | RuntimeException | IOException e) {
                    e.printStackTrace();
                    mListener.onUpdateError();
                }
            }
        });
    }

    private void resolveVersion(boolean isManualCheck) {
        String installedCommit = getInstalledCommit();

        if (TextUtils.isEmpty(mCommitSHA)) {
            mListener.onUpdateError();
            return;
        }

        if (commitsMatch(installedCommit, mCommitSHA)) {
            mListener.onUpdateLatest(isManualCheck);
            return;
        }

        if (TextUtils.isEmpty(installedCommit)) {
            if (isManualCheck) {
                mListener.onUpdateApplied(true);
            } else {
                mListener.onUpdateLatest(false);
            }
            return;
        }

        Request compare = new Request.a()
                .b(String.format(COMPARE_URL, Uri.encode(installedCommit), Uri.encode(mCommitSHA)))
                .a("Accept-Encoding", "gzip")
                .a();

        mClient.a(compare).a(new Callback() {
            @Override
            public void a(Call call, IOException e) {
                Log.d("OTAHelper", "Error while comparing versions: " + e);
                fallbackVersionCheck(isManualCheck);
            }

            @Override
            public void a(Call call, Response response) {
                try (Response result = response) {
                    var compareJson = new JSONObject(GzipDecompressor.decompressResponse(result));
                    String status = compareJson.optString("status", "");

                    switch (status) {
                        case "ahead" -> mListener.onUpdateApplied(isManualCheck);
                        case "behind", "identical" -> mListener.onUpdateLatest(isManualCheck);
                        case "diverged" -> {
                            if (isManualCheck) {
                                mListener.onUpdateApplied(true);
                            } else {
                                mListener.onUpdateLatest(false);
                            }
                        }
                        default -> fallbackVersionCheck(isManualCheck);
                    }
                } catch (JSONException | RuntimeException | IOException e) {
                    e.printStackTrace();
                    fallbackVersionCheck(isManualCheck);
                }
            }
        });
    }

    private void fallbackVersionCheck(boolean isManualCheck) {
        if (isManualCheck) {
            mListener.onUpdateError();
        } else {
            mListener.onUpdateLatest(false);
        }
    }

    private static String getInstalledCommit() {
        String commit = VersionReader.getVersionCommit();
        if (TextUtils.isEmpty(commit)) {
            commit = VersionReader.getVersionBuild();
        }
        return commit == null ? "" : commit;
    }

    private static boolean commitsMatch(String installed, String release) {
        if (TextUtils.isEmpty(installed) || TextUtils.isEmpty(release)) return false;

        if (installed.equalsIgnoreCase(release)) return true;

        return installed.length() >= 7 && release.startsWith(installed)
                || release.length() >= 7 && installed.startsWith(release);
    }

    public String getCommitSHA() {
        return mCommitSHA;
    }

    public String getReleaseTag() {
        if (mReleaseJson == null) return "";

        try {
            return mReleaseJson.getString("tag_name");
        } catch (JSONException e) {
            return "";
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
