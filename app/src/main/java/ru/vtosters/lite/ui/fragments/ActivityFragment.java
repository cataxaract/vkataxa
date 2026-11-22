package ru.vtosters.lite.ui.fragments;

import android.os.Bundle;
import androidx.preference.Preference;
import com.vtosters.lite.R;
import ru.vtosters.hooks.other.Preferences;

import static ru.vtosters.hooks.other.Preferences.offline;

public class ActivityFragment extends TrackedMaterialPreferenceToolbarFragment {
    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        addPreferencesFromResource(R.xml.preferences_activity);
        findPreference("setoffline").setEnabled(offline());
        findPreference("onlinefixcategory").setVisible(!Preferences.serverFeaturesDisable());

        updateUndeleteDependentPreferences(Preferences.getBoolValue("undeletemsg", false));

        Preference undelete = findPreference("undeletemsg");
        if (undelete != null) {
            undelete.setOnPreferenceChangeListener((preference, newValue) -> {
                updateUndeleteDependentPreferences(Boolean.TRUE.equals(newValue));
                return true;
            });
        }
    }

    private void updateUndeleteDependentPreferences(boolean enabled) {
        Preference photos = findPreference("undeletemsg_photos");
        if (photos != null) photos.setEnabled(enabled);

        Preference transparency = findPreference("undeletemsg_transparency");
        if (transparency != null) transparency.setEnabled(enabled);
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        findPreference("setoffline").setEnabled(offline());
        updateUndeleteDependentPreferences(Preferences.getBoolValue("undeletemsg", false));
        return super.onPreferenceTreeClick(preference);
    }

    @Override
    public int T4() {
        return R.string.vtlactivity;
    }
}
