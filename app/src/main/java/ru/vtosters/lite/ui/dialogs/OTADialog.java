package ru.vtosters.lite.ui.dialogs;

import android.app.Activity;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.vk.core.dialogs.alert.VkAlertDialog;
import com.vtosters.lite.R;
import ru.vtosters.hooks.other.Preferences;
import ru.vtosters.hooks.other.ThemesUtils;
import ru.vtosters.lite.downloaders.OTADownloader;
import ru.vtosters.lite.utils.AndroidUtils;
import ru.vtosters.lite.utils.OTAUtils;

public class OTADialog implements OTAUtils.OTAListener {
    private static final String SKIPPED_RELEASE_PREF = "ota_skipped_release";
    private static final String LAST_CHECK_PREF = "ota_last_auto_check";
    private static final long AUTO_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L;

    private final Activity mActivity;
    private final OTAUtils mHelper;
    private final boolean mIsManualCheck;

    public OTADialog(Activity activity, boolean isManualCheck) {
        mActivity = activity;
        mIsManualCheck = isManualCheck;

        mHelper = new OTAUtils(this);
        mHelper.loadData(isManualCheck);
    }

    public static void checkUpdates(Activity activity) {
        long now = System.currentTimeMillis();
        long lastCheck = Preferences.getPreferences().getLong(LAST_CHECK_PREF, 0L);

        if (now - lastCheck < AUTO_CHECK_INTERVAL_MS) return;

        Preferences.getPreferences()
                .edit()
                .putLong(LAST_CHECK_PREF, now)
                .apply();

        new OTADialog(activity, false);
    }

    public static void checkUpdatesManual(Activity activity) {
        new OTADialog(activity, true);
    }

    @Override
    public void onUpdateApplied(boolean isManualCheck) {
        mActivity.runOnUiThread(() -> {
            String tag = mHelper.getReleaseTag();

            if (!isManualCheck
                    && !TextUtils.isEmpty(tag)
                    && tag.equals(Preferences.getPreferences().getString(SKIPPED_RELEASE_PREF, ""))) {
                return;
            }

            String downloadUrl = mHelper.getDownloadUrl();

            if (TextUtils.isEmpty(downloadUrl)) {
                if (isManualCheck) {
                    Toast.makeText(mActivity, AndroidUtils.getString("update_err"), Toast.LENGTH_SHORT).show();
                }
                return;
            }

            new VkAlertDialog.Builder(mActivity)
                    .setTitle(mActivity.getString(R.string.newversion) + " " + mHelper.getNewVersionName())
                    .setView(makeUpdateInfoView(mHelper.getUpdateDescription()))
                    .setPositiveButton(mActivity.getString(R.string.updateanddownload), (dialog, which) -> {
                        skipRelease(tag);

                        if (TextUtils.isEmpty(downloadUrl)) {
                            Toast.makeText(mActivity, AndroidUtils.getString("update_err"), Toast.LENGTH_SHORT).show();
                            return;
                        }

                        OTADownloader.downloadBuild(downloadUrl, mHelper.getCommitSHA());
                    })
                    .setNegativeButton(mActivity.getString(R.string.update_later), (dialog, which) -> {
                        skipRelease(tag);
                        dialog.dismiss();
                    })
                    .show();
        });
    }

    private void skipRelease(String tag) {
        if (TextUtils.isEmpty(tag)) return;

        Preferences.getPreferences()
                .edit()
                .putString(SKIPPED_RELEASE_PREF, tag)
                .apply();
    }

    private View makeUpdateInfoView(String changelog) {
        LinearLayout container = new LinearLayout(mActivity);
        TextView changelogView = new TextView(mActivity);

        container.setOrientation(LinearLayout.VERTICAL);

        changelogView.setText(AndroidUtils.getString("changelog") + ": \n" + (changelog == null ? "" : changelog));
        changelogView.setTextColor(ThemesUtils.getTextAttr());
        container.addView(changelogView, new LinearLayout.LayoutParams(-1, -2));

        return container;
    }

    @Override
    public void onUpdateLatest(boolean isManualCheck) {
        if (isManualCheck) {
            mActivity.runOnUiThread(() -> Toast.makeText(mActivity, AndroidUtils.getString("update_latest"), Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    public void onUpdateError() {
        if (!mIsManualCheck) return;

        mActivity.runOnUiThread(() -> Toast.makeText(mActivity, AndroidUtils.getString("update_err"), Toast.LENGTH_SHORT).show());
    }
}
