package com.auroracoin.is.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.ListPreference;
import android.support.v4.preference.PreferenceFragment;

import com.auroracoin.is.Configuration;
import com.auroracoin.is.R;
import com.auroracoin.is.WalletApplication;

/**
 * @author John L. Jegutanis
 */
public class SettingsFragment extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String CURRENCY_PREFERENCE = "currency";
    private ListPreference currencyPreference;
    private Configuration config;
    private WalletApplication application;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        application = (WalletApplication) context.getApplicationContext();
        config = application.getConfiguration();
    }

    @Override
    public void onCreate(Bundle paramBundle) {
        super.onCreate(paramBundle);
        addPreferencesFromResource(R.xml.preferences);
        currencyPreference = (ListPreference)getPreferenceScreen().findPreference(CURRENCY_PREFERENCE);
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals(CURRENCY_PREFERENCE)) {
            config.setExchangeCurrencyCode(currencyPreference.getEntry().toString());
        }
    }
}
