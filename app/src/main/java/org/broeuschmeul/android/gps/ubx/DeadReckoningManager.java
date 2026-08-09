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

package org.broeuschmeul.android.gps.ubx;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import org.broeuschmeul.android.gps.usb.provider.BuildConfig;

/**
 * Manager for LEA-6R Automotive Dead Reckoning (ADR) support.
 *
 * Handles:
 * - Enabling/disabling DR via CFG-NAVX5
 * - Parsing NAV-EKFSTATUS for DR status
 * - Optionally feeding phone accelerometer/gyroscope data as supplementary sensors
 *
 * Usage:
 *   manager = new DeadReckoningManager(context);
 *   manager.setCommandSender(sender);
 *   manager.enableDR();
 *   // ... later
 *   manager.onEkfStatus(pulses, period, ...);
 */
public class DeadReckoningManager implements SensorEventListener {

    private static final String LOG_TAG = DeadReckoningManager.class.getSimpleName();

    /**
     * Interface for sending UBX commands to the receiver.
     */
    public interface CommandSender {
        void sendUbxCommand(byte[] data);
    }

    /**
     * Listener for DR status changes.
     */
    public interface DrStatusListener {
        void onDrStatusChanged(DrStatus status);
    }

    /**
     * Dead Reckoning status information.
     */
    public static class DrStatus {
        public boolean enabled;
        public int calibStatus;  // 0=uncalibrated, 1=calibrating, 2=calibrated
        public int pulses;
        public int period;
        public int gyroMean;
        public int temperature;
        public int direction;    // 0=forward, 1=backward, 2=unknown

        public boolean isCalibrated() {
            return calibStatus >= 2;
        }

        public String getCalibStatusString() {
            switch (calibStatus) {
                case 0: return "Uncalibrated";
                case 1: return "Calibrating";
                case 2: return "Coarse calibration";
                case 3: return "Fine calibration";
                default: return "Unknown (" + calibStatus + ")";
            }
        }

        public String getDirectionString() {
            switch (direction) {
                case 0: return "Forward";
                case 1: return "Backward";
                default: return "Unknown";
            }
        }
    }

    private final Context context;
    private CommandSender commandSender;
    private DrStatusListener statusListener;
    private SensorManager sensorManager;
    private boolean drEnabled = false;
    private boolean phoneSensorsActive = false;
    private final DrStatus currentStatus = new DrStatus();

    // Phone sensor data (for supplementary input)
    private float[] lastAccelerometer = new float[3];
    private float[] lastGyroscope = new float[3];
    private boolean hasAccelerometer = false;
    private boolean hasGyroscope = false;

    public DeadReckoningManager(Context context) {
        this.context = context;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    }

    public void setCommandSender(CommandSender sender) {
        this.commandSender = sender;
    }

    public void setDrStatusListener(DrStatusListener listener) {
        this.statusListener = listener;
    }

    /**
     * Enable Dead Reckoning on the receiver.
     * Sends CFG-NAVX5 command and enables NAV-EKFSTATUS output.
     */
    public void enableDR() {
        if (commandSender == null) {
            debugLog("Cannot enable DR: no command sender");
            return;
        }

        debugLog("Enabling Dead Reckoning");
        drEnabled = true;
        currentStatus.enabled = true;

        // Send CFG-NAVX5 to enable ADR
        commandSender.sendUbxCommand(UbxCommands.cfgNavx5Dr(true));

        // Enable NAV-EKFSTATUS output at 1Hz
        commandSender.sendUbxCommand(UbxCommands.enableNavEkfStatus(1));
    }

    /**
     * Disable Dead Reckoning on the receiver.
     */
    public void disableDR() {
        if (commandSender == null) {
            debugLog("Cannot disable DR: no command sender");
            return;
        }

        debugLog("Disabling Dead Reckoning");
        drEnabled = false;
        currentStatus.enabled = false;

        // Send CFG-NAVX5 to disable ADR
        commandSender.sendUbxCommand(UbxCommands.cfgNavx5Dr(false));

        // Disable NAV-EKFSTATUS output
        commandSender.sendUbxCommand(UbxCommands.enableNavEkfStatus(0));

        // Stop phone sensors if active
        stopPhoneSensors();
    }

    /**
     * Get current DR status.
     */
    public DrStatus getDRStatus() {
        return currentStatus;
    }

    /**
     * Check if DR is enabled.
     */
    public boolean isDREnabled() {
        return drEnabled;
    }

    /**
     * Poll the receiver for current DR status.
     */
    public void pollDRStatus() {
        if (commandSender != null) {
            commandSender.sendUbxCommand(UbxCommands.pollNavEkfStatus());
        }
    }

    /**
     * Called when a NAV-EKFSTATUS message is received from the UBX parser.
     * Updates the current DR status.
     */
    public void onEkfStatus(int pulses, int period, int gyroMean, int temperature,
                            int direction, int calibStatus, int pulseScale,
                            int gyroBias, int gyroScale) {
        currentStatus.pulses = pulses;
        currentStatus.period = period;
        currentStatus.gyroMean = gyroMean;
        currentStatus.temperature = temperature;
        currentStatus.direction = direction;
        currentStatus.calibStatus = calibStatus;

        debugLog("DR Status: calib=" + currentStatus.getCalibStatusString() +
                " dir=" + currentStatus.getDirectionString() +
                " temp=" + temperature);

        if (statusListener != null) {
            statusListener.onDrStatusChanged(currentStatus);
        }
    }

    /**
     * Start using phone sensors (accelerometer, gyroscope) as supplementary input.
     * These can provide additional dead reckoning data when the receiver supports
     * external sensor fusion (ESF messages).
     *
     * Note: LEA-6R uses its own wheel tick and gyro inputs via hardware pins.
     * Phone sensors are used as a best-effort supplement and may not be consumed
     * by all receiver firmware versions.
     */
    public void startPhoneSensors() {
        if (phoneSensorsActive) return;

        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            hasAccelerometer = true;
            debugLog("Accelerometer registered for DR");
        }

        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
            hasGyroscope = true;
            debugLog("Gyroscope registered for DR");
        }

        phoneSensorsActive = true;
    }

    /**
     * Stop phone sensors.
     */
    public void stopPhoneSensors() {
        if (!phoneSensorsActive) return;

        sensorManager.unregisterListener(this);
        phoneSensorsActive = false;
        hasAccelerometer = false;
        hasGyroscope = false;
        debugLog("Phone sensors stopped for DR");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                System.arraycopy(event.values, 0, lastAccelerometer, 0, 3);
                break;
            case Sensor.TYPE_GYROSCOPE:
                System.arraycopy(event.values, 0, lastGyroscope, 0, 3);
                break;
        }
        // Note: ESF-MEAS external sensor feeding is receiver-firmware dependent.
        // The LEA-6R primarily uses hardware wheel-tick and gyro inputs.
        // Phone sensor data is stored for potential future use.
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used
    }

    /**
     * Release resources. Call when shutting down.
     */
    public void release() {
        stopPhoneSensors();
        commandSender = null;
        statusListener = null;
    }

    private void debugLog(String message) {
        if (BuildConfig.DEBUG) Log.d(LOG_TAG, message);
    }
}
