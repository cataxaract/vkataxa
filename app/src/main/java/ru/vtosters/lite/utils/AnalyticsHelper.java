package ru.vtosters.lite.utils;

import android.app.Application;
import android.os.Handler;
import androidx.annotation.NonNull;
import com.microsoft.appcenter.AppCenter;
import com.microsoft.appcenter.analytics.Analytics;
import com.microsoft.appcenter.channel.AbstractChannelListener;
import com.microsoft.appcenter.channel.Channel;
import com.microsoft.appcenter.crashes.Crashes;
import com.microsoft.appcenter.ingestion.models.Log;
import ru.vtosters.hooks.other.Preferences;
import ru.vtosters.lite.BuildConfig;
import ru.vtosters.lite.ui.fragments.TrackedMaterialPreferenceToolbarFragment;

import java.util.HashMap;

public class AnalyticsHelper {
    private static final boolean analyticsDisabled = Preferences.getBoolValue("analyticsDisabled", false) || Preferences.serverFeaturesDisable();
    private static final String key = BuildConfig.APPCENTER_KEY;
    private static volatile boolean started = false;

    private static final Channel.Listener patchDeviceListener = new AbstractChannelListener() {
        @Override
        public void onPreparedLog(@NonNull Log log, @NonNull String groupName, int flags) {
            var device = log.getDevice();
            device.setAppVersion(VersionReader.getVersionBuild());
            device.setAppBuild(VersionReader.getVersionBuild());
        }
    };

    private static void addPatchDeviceListener() {
        try {
            var channelField = AppCenter.class.getDeclaredField("mChannel");
            channelField.setAccessible(true);
            var channel = (Channel) channelField.get(AppCenter.getInstance());
            assert channel != null;
            channel.addListener(patchDeviceListener);
        } catch (ReflectiveOperationException e) {
            e.printStackTrace();
        }
    }

    private static void patchDevice() {
        try {
            var handlerField = AppCenter.class.getDeclaredField("mHandler");
            handlerField.setAccessible(true);
            var handler = ((Handler) handlerField.get(AppCenter.getInstance()));
            assert handler != null;
            handler.post(AnalyticsHelper::addPatchDeviceListener);
        } catch (ReflectiveOperationException e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("ConstantConditions")
    public static void start(Application application) {
        if (started || analyticsDisabled || key.isEmpty()) return;

        AppCenter.start(application, AnalyticsHelper.key, Analytics.class, Crashes.class);
        started = true;
        patchDevice();
        setAnalyticsStatus(true);
    }

    public static void trackEvent(String event) {
        if (!started || analyticsDisabled) return;

        Analytics.trackEvent(event);
    }

    public static void trackEvent(String event, HashMap<String, String> map) {
        if (!started || analyticsDisabled) return;

        Analytics.trackEvent(event, map);
    }

    public static void trackSettingsFragment(Class<? extends TrackedMaterialPreferenceToolbarFragment> fragment) {
        if (!started || analyticsDisabled) return;

        var props = new HashMap<String, String>();
        props.put("fragment", fragment.getSimpleName());

        AnalyticsHelper.trackEvent("Opened settings", props);
    }

    public static void trackError(Throwable th) {
        if (!started || analyticsDisabled) return;

        Crashes.trackError(th);
    }

    public static void setAnalyticsStatus(Boolean bool) {
        if (!started) return;

        Analytics.setEnabled(bool && Preferences.isValidSignature());
        Crashes.setEnabled(bool && !AndroidUtils.isDebuggable() && Preferences.isValidSignature());
    }
}

