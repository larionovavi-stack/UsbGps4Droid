/*
 * Copyright (C) 2026 UsbGPS4Droid Project
 *
 * This file is part of UsbGPS4Droid.
 *
 * UsbGPS4Droid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * UsbGPS4Droid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with UsbGPS4Droid. If not, see <http://www.gnu.org/licenses/>.
 */

package org.broeuschmeul.android.gps.usb.provider.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import org.broeuschmeul.android.gps.usb.provider.R;
import org.broeuschmeul.android.gps.usb.provider.driver.USBGpsProviderService;

/**
 * PreferenceFragment for u-blox receiver configuration.
 *
 * Allows configuration of:
 * - Update rate (1Hz, 2Hz, 5Hz, 10Hz)
 * - Dynamic model (Portable, Stationary, Pedestrian, Automotive, Sea, Airborne)
 * - SBAS enable/disable
 * - Protocol mode (NMEA only, UBX only, NMEA+UBX)
 * - Dead Reckoning enable/disable
 * - Save config to flash
 * - Reset receiver (hot/warm/cold)
 *
 * When settings change, sends appropriate UBX-CFG commands via USBGpsProviderService.
 */
public class ReceiverConfigFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private SharedPreferences sharedPreferences;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.ubx_receiver_prefs);

        sharedPreferences = getPreferenceManager().getSharedPreferences();
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);

        // Set up save config button
        Preference saveConfigPref = findPreference(getString(R.string.pref_ubx_save_config_key));
        if (saveConfigPref != null) {
            saveConfigPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    sendUbxAction(USBGpsProviderService.ACTION_UBX_SAVE_CONFIG);
                    Toast.makeText(getActivity(), R.string.pref_ubx_save_config_toast, Toast.LENGTH_SHORT).show();
                    return true;
                }
            });
        }

        // Set up reset receiver selector
        ListPreference resetPref = (ListPreference) findPreference(getString(R.string.pref_ubx_reset_receiver_key));
        if (resetPref != null) {
            resetPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    String resetType = (String) newValue;
                    Intent intent = new Intent(getActivity(), USBGpsProviderService.class);
                    intent.setAction(USBGpsProviderService.ACTION_UBX_RESET_RECEIVER);
                    intent.putExtra(USBGpsProviderService.EXTRA_UBX_RESET_TYPE, resetType);
                    getActivity().startService(intent);
                    Toast.makeText(getActivity(),
                            getString(R.string.pref_ubx_reset_toast, resetType),
                            Toast.LENGTH_SHORT).show();
                    return false; // Don't actually save the preference value
                }
            });
        }

        updateSummaries();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key == null) return;

        String protocolKey = getString(R.string.pref_ubx_protocol_mode_key);
        String rateKey = getString(R.string.pref_ubx_update_rate_key);
        String dynModelKey = getString(R.string.pref_ubx_dynamic_model_key);
        String sbasKey = getString(R.string.pref_ubx_sbas_key);
        String drKey = getString(R.string.pref_ubx_dead_reckoning_key);

        if (key.equals(protocolKey)) {
            String mode = sharedPreferences.getString(key, "nmea");
            Intent intent = new Intent(getActivity(), USBGpsProviderService.class);
            intent.setAction(USBGpsProviderService.ACTION_UBX_CONFIGURE);
            intent.putExtra(USBGpsProviderService.EXTRA_UBX_PROTOCOL_MODE, mode);
            getActivity().startService(intent);

        } else if (key.equals(rateKey)) {
            int rateMs = Integer.parseInt(sharedPreferences.getString(key, "1000"));
            Intent intent = new Intent(getActivity(), USBGpsProviderService.class);
            intent.setAction(USBGpsProviderService.ACTION_UBX_CONFIGURE);
            intent.putExtra(USBGpsProviderService.EXTRA_UBX_RATE_MS, rateMs);
            getActivity().startService(intent);

        } else if (key.equals(dynModelKey)) {
            int model = Integer.parseInt(sharedPreferences.getString(key, "0"));
            Intent intent = new Intent(getActivity(), USBGpsProviderService.class);
            intent.setAction(USBGpsProviderService.ACTION_UBX_CONFIGURE);
            intent.putExtra(USBGpsProviderService.EXTRA_UBX_DYN_MODEL, model);
            getActivity().startService(intent);

        } else if (key.equals(sbasKey)) {
            boolean enabled = sharedPreferences.getBoolean(key, true);
            Intent intent = new Intent(getActivity(), USBGpsProviderService.class);
            intent.setAction(USBGpsProviderService.ACTION_UBX_CONFIGURE);
            intent.putExtra(USBGpsProviderService.EXTRA_UBX_SBAS, enabled);
            getActivity().startService(intent);

        } else if (key.equals(drKey)) {
            boolean enabled = sharedPreferences.getBoolean(key, false);
            Intent intent = new Intent(getActivity(), USBGpsProviderService.class);
            intent.setAction(USBGpsProviderService.ACTION_UBX_CONFIGURE);
            intent.putExtra(USBGpsProviderService.EXTRA_UBX_DEAD_RECKONING, enabled);
            getActivity().startService(intent);
        }

        updateSummaries();
    }

    private void updateSummaries() {
        ListPreference protocolPref = (ListPreference) findPreference(getString(R.string.pref_ubx_protocol_mode_key));
        if (protocolPref != null && protocolPref.getEntry() != null) {
            protocolPref.setSummary(getString(R.string.pref_ubx_protocol_mode_summary_val, protocolPref.getEntry()));
        }

        ListPreference ratePref = (ListPreference) findPreference(getString(R.string.pref_ubx_update_rate_key));
        if (ratePref != null && ratePref.getEntry() != null) {
            ratePref.setSummary(getString(R.string.pref_ubx_update_rate_summary_val, ratePref.getEntry()));
        }

        ListPreference dynPref = (ListPreference) findPreference(getString(R.string.pref_ubx_dynamic_model_key));
        if (dynPref != null && dynPref.getEntry() != null) {
            dynPref.setSummary(getString(R.string.pref_ubx_dynamic_model_summary_val, dynPref.getEntry()));
        }
    }

    private void sendUbxAction(String action) {
        Intent intent = new Intent(getActivity(), USBGpsProviderService.class);
        intent.setAction(action);
        getActivity().startService(intent);
    }

    @Override
    public void onDestroy() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
        super.onDestroy();
    }
}
