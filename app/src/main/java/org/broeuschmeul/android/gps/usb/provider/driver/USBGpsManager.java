/*
 * Copyright (C) 2016, 2017 Oliver Bell
 * Copyright (C) 2010, 2011, 2012 Herbert von Broeuschmeul
 * Copyright (C) 2010, 2011, 2012 BluetoothGPS4Droid Project
 * Copyright (C) 2011, 2012 UsbGPS4Droid Project
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

package org.broeuschmeul.android.gps.usb.provider.driver;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.broeuschmeul.android.gps.nmea.util.NmeaParser;
import org.broeuschmeul.android.gps.sirf.util.SirfUtils;
import org.broeuschmeul.android.gps.ubx.DeadReckoningManager;
import org.broeuschmeul.android.gps.ubx.UbxCommands;
import org.broeuschmeul.android.gps.ubx.UbxParser;
import org.broeuschmeul.android.gps.usb.provider.BuildConfig;
import org.broeuschmeul.android.gps.usb.provider.R;
import org.broeuschmeul.android.gps.usb.provider.USBGpsApplication;
import org.broeuschmeul.android.gps.usb.provider.ui.GpsInfoActivity;
import org.broeuschmeul.android.gps.usb.provider.util.SuperuserManager;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Build;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.app.AppOpsManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;


/**
 * This class is used to establish and manage the connection with the bluetooth GPS.
 *
 * @author Herbert von Broeuschmeul
 */
public class USBGpsManager {

    /**
     * Tag used for log messages
     */
    private static final String LOG_TAG = USBGpsManager.class.getSimpleName();

    // Has more connections logs
    private boolean debug = true;

    private UsbManager usbManager = null;
    private static final String ACTION_USB_PERMISSION =
            "org.broeuschmeul.android.gps.usb.provider.driver.USBGpsManager.USB_PERMISSION";

    /**
     * Used to listen for nmea updates from UsbGpsManager
     */
    public interface NmeaListener {
        void onNmeaReceived(long timestamp, String nmea);
    }

    private final BroadcastReceiver permissionAndDetachReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            if (usbManager.hasPermission(device)) {
                                debugLog("We have permission, good!");
                                if (enabled) {
                                    openConnection(device);
                                }
                            }
                        }
                    } else {
                        debugLog("permission denied for device " + device);
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                synchronized (this) {
                    if (connectedGps != null && enabled) {
                        connectedGps.close();
                    }
                }
            }
        }
    };

    /**
     * A utility class used to manage the communication with the USB GPS using usb-serial-for-android.
     * It is used to read NMEA data from the GPS or to send SIRF III binary commands or SIRF III NMEA commands to the GPS.
     * You should run the main read loop in one thread and send the commands in a separate one.
     *
     * @author Herbert von Broeuschmeul
     */
    private class ConnectedGps extends Thread {
        private final UsbDevice gpsUsbDev;
        private UsbSerialPort serialPort;
        private boolean closed = false;
        /**
         * A boolean which indicates if the GPS is ready to receive data.
         * In fact we consider that the GPS is ready when it begins to sends data...
         */
        private boolean ready = false;

        public ConnectedGps(UsbDevice device) {
            this(device, defaultDeviceSpeed);
        }

        public ConnectedGps(UsbDevice device, String deviceSpeed) {
            this.gpsUsbDev = device;

            debugLog("Opening USB serial connection using usb-serial-for-android");

            // Use UsbSerialProber to auto-detect the driver (CP210x, FTDI, PL2303, CH340, CDC-ACM)
            List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);

            UsbSerialDriver selectedDriver = null;
            for (UsbSerialDriver driver : availableDrivers) {
                if (driver.getDevice().equals(device)) {
                    selectedDriver = driver;
                    break;
                }
            }

            if (selectedDriver == null) {
                if (BuildConfig.DEBUG || debug)
                    Log.e(LOG_TAG, "No USB serial driver found for device, notifying");
                disable(R.string.msg_gps_provider_cant_connect);
                closed = true;
                return;
            }

            debugLog("Found driver: " + selectedDriver.getClass().getSimpleName());

            // Open connection
            UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) {
                if (BuildConfig.DEBUG || debug)
                    Log.e(LOG_TAG, "Could not open USB device connection");
                disable(R.string.msg_gps_provider_cant_connect);
                closed = true;
                return;
            }

            serialPort = selectedDriver.getPorts().get(0);

            try {
                serialPort.open(connection);

                int baudRate;
                if (setDeviceSpeed) {
                    baudRate = Integer.parseInt(deviceSpeed);
                } else {
                    baudRate = Integer.parseInt(defaultDeviceSpeed);
                }

                serialPort.setParameters(
                    baudRate,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                );

                // Enable DTR/RTS - required by some USB-serial chips to start data flow
                try { serialPort.setDTR(true); } catch (Exception ignored) {}
                try { serialPort.setRTS(true); } catch (Exception ignored) {}

                debugLog("Serial port opened at " + baudRate + " baud, 8N1, DTR/RTS on");

            } catch (IOException e) {
                if (BuildConfig.DEBUG || debug)
                    Log.e(LOG_TAG, "Error opening serial port", e);
                disable(R.string.msg_gps_provider_cant_connect);
                close();
                return;
            }

            // Send SiRF binary-to-NMEA command if SiRF mode enabled
            if (sirfGps) {
                debugLog("trying to switch from SiRF binary to NMEA");
                final byte[] sirfBin2Nmea = SirfUtils.genSirfCommandFromPayload(callingService.getString(R.string.sirf_bin_to_nmea));
                try {
                    serialPort.write(sirfBin2Nmea, 100);
                } catch (IOException e) {
                    if (BuildConfig.DEBUG || debug)
                        Log.e(LOG_TAG, "Error sending SiRF command", e);
                    close();
                    return;
                }
            }
        }

        public boolean isReady() {
            return ready;
        }

        private long lastRead = 0;

        public void run() {
            if (closed || serialPort == null) {
                debugLog("ConnectedGps.run(): already closed or no serial port");
                disableIfNeeded();
                return;
            }

            try {
                byte[] readBuffer = new byte[1024];
                StringBuilder lineBuffer = new StringBuilder();

                long now = SystemClock.uptimeMillis();

                // we will wait more at the beginning of the connection
                // but if we don't get a signal after 45 seconds we can assume the device
                // is not usable
                lastRead = now + 45000;

                // Determine if we should use UBX parsing
                boolean useUbx = "ubx".equals(protocolMode) || "both".equals(protocolMode);
                boolean useNmea = "nmea".equals(protocolMode) || "both".equals(protocolMode);

                // Set up UBX parser NMEA bridge for mixed mode
                if (useUbx) {
                    ubxParser.reset();
                    ubxParser.setNmeaByteListener(new UbxParser.NmeaByteListener() {
                        @Override
                        public void onNmeaSentence(String sentence) {
                            String s = sentence.trim();
                            if (!s.isEmpty()) {
                                if (notifyNmeaSentence(s + "\r\n")) {
                                    markDataReceived();
                                }
                            }
                        }
                    });
                }

                while ((enabled) && (now < lastRead + 10000) && (!closed)) {

                    int bytesRead;
                    try {
                        bytesRead = serialPort.read(readBuffer, 2000);
                    } catch (IOException e) {
                        bytesRead = 0;
                        debugLog("IOException during read: " + e.getMessage());
                    }

                    if (bytesRead > 0) {
                        // Any data received = keep alive timer reset
                        lastRead = SystemClock.uptimeMillis();

                        if (useUbx) {
                            // In UBX or mixed mode, feed raw bytes to UBX parser
                            // The UBX parser handles both UBX frames and NMEA sentences
                            ubxParser.process(readBuffer, 0, bytesRead);

                            // Mark as ready since we're receiving data
                            ready = true;
                            resetProblemState();
                        } else {
                            // NMEA-only mode: original line-based parsing
                            String chunk = new String(readBuffer, 0, bytesRead, "US-ASCII");
                            lineBuffer.append(chunk);

                            // Extract complete lines
                            int newlinePos;
                            while ((newlinePos = lineBuffer.indexOf("\n")) >= 0) {
                                String line = lineBuffer.substring(0, newlinePos + 1);
                                lineBuffer.delete(0, newlinePos + 1);

                                // Ensure line ends with \r\n for NMEA parser
                                String s = line.trim();
                                if (!s.isEmpty()) {
                                    if (notifyNmeaSentence(s + "\r\n")) {
                                        ready = true;
                                        resetProblemState();
                                    }
                                }
                            }
                        }
                    } else {
                        debugLog("data: not ready, waiting...");
                        SystemClock.sleep(200);
                    }

                    now = SystemClock.uptimeMillis();
                }

                if (now > lastRead + 10000) {
                    if (BuildConfig.DEBUG || debug)
                        Log.e(LOG_TAG, "Read timeout in read thread");
                } else if (closed) {
                    debugLog("Device connection closing, stopping read thread");
                } else {
                    debugLog("Provider disabled, stopping read thread");
                }
            } catch (Exception e) {
                if (BuildConfig.DEBUG || debug)
                    Log.e(LOG_TAG, "error while getting data", e);
                setMockLocationProviderOutOfService();
            } finally {
                // cleanly closing everything...
                debugLog("Closing read thread");
                this.close();
                disableIfNeeded();
            }
        }

        private void markDataReceived() {
            ready = true;
            lastRead = SystemClock.uptimeMillis();
            resetProblemState();
        }

        private void resetProblemState() {
            if (problemNotified) {
                problemNotified = false;
                setDisableReason(0);
                debugLog("connection is good so resetting the number of connection retries");
                nbRetriesRemaining = maxConnectionRetries;
                notificationManager.cancel(R.string.connection_problem_notification_title);
            }
        }

        /**
         * Write to the connected serial port.
         *
         * @param buffer The bytes to write
         */
        public void write(byte[] buffer) {
            try {
                do {
                    Thread.sleep(100);
                } while ((enabled) && (!ready) && (!closed));
                if ((enabled) && (ready) && (!closed) && serialPort != null) {
                    serialPort.write(buffer, 100);
                }
            } catch (IOException | InterruptedException e) {
                if (BuildConfig.DEBUG || debug)
                    Log.e(LOG_TAG, "Exception during write", e);
            }
        }

        /**
         * Write to the connected serial port.
         *
         * @param buffer The data to write
         */
        public void write(String buffer) {
            try {
                do {
                    Thread.sleep(100);
                } while ((enabled) && (!ready) && (!closed));
                if ((enabled) && (ready) && (!closed) && serialPort != null) {
                    serialPort.write(buffer.getBytes("US-ASCII"), 100);
                }
            } catch (IOException | InterruptedException e) {
                if (BuildConfig.DEBUG || debug)
                    Log.e(LOG_TAG, "Exception during write", e);
            }
        }

        public void close() {
            ready = false;
            closed = true;
            try {
                if (serialPort != null) {
                    debugLog("closing USB serial port");
                    serialPort.close();
                }
            } catch (IOException e) {
                if (BuildConfig.DEBUG || debug)
                    Log.e(LOG_TAG, "error while closing USB serial port", e);
            }
        }
    }

    private boolean timeSetAlready;
    private boolean shouldSetTime;

    private Service callingService;
    private UsbDevice gpsDev;

    private NmeaParser parser;
    private UbxParser ubxParser;
    private DeadReckoningManager drManager;
    private String protocolMode = "nmea"; // "nmea", "ubx", "both"
    private boolean enabled = false;
    private ExecutorService notificationPool;
    private ScheduledExecutorService connectionAndReadingPool;

    private final List<NmeaListener> nmeaListeners =
            Collections.synchronizedList(new LinkedList<NmeaListener>());

    private LocationManager locationManager;
    private SharedPreferences sharedPreferences;
    private ConnectedGps connectedGps;
    private int disableReason = 0;

    private NotificationCompat.Builder connectionProblemNotificationBuilder;
    private NotificationCompat.Builder serviceStoppedNotificationBuilder;

    private Context appContext;
    private NotificationManager notificationManager;

    private int maxConnectionRetries;
    private int nbRetriesRemaining;
    private boolean problemNotified = false;

    private boolean connected = false;
    private boolean setDeviceSpeed = false;
    private boolean sirfGps = false;
    private String deviceSpeed = "auto";
    private String defaultDeviceSpeed = "4800";

    private int gpsProductId = 8963;
    private int gpsVendorId = 1659;

    /**
     * @param callingService
     * @param vendorId
     * @param productId
     * @param maxRetries
     */
    public USBGpsManager(Service callingService, int vendorId, int productId, int maxRetries) {
        this.gpsVendorId = vendorId;
        this.gpsProductId = productId;
        this.callingService = callingService;
        this.maxConnectionRetries = maxRetries + 1;
        this.nbRetriesRemaining = maxConnectionRetries;
        this.appContext = callingService.getApplicationContext();
        this.parser = new NmeaParser(10f, this.appContext);

        // Initialize UBX parser and Dead Reckoning manager
        this.ubxParser = new UbxParser();
        this.drManager = new DeadReckoningManager(this.appContext);

        locationManager = (LocationManager) callingService.getSystemService(Context.LOCATION_SERVICE);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(callingService);

        deviceSpeed = sharedPreferences.getString(
                USBGpsProviderService.PREF_GPS_DEVICE_SPEED,
                callingService.getString(R.string.defaultGpsDeviceSpeed)
        );

        shouldSetTime = sharedPreferences.getBoolean(USBGpsProviderService.PREF_SET_TIME, false);
        timeSetAlready = true;

        defaultDeviceSpeed = callingService.getString(R.string.defaultGpsDeviceSpeed);
        setDeviceSpeed = !deviceSpeed.equals(callingService.getString(R.string.autoGpsDeviceSpeed));
        sirfGps = sharedPreferences.getBoolean(USBGpsProviderService.PREF_SIRF_GPS, false);
        protocolMode = sharedPreferences.getString(
                appContext.getString(R.string.pref_ubx_protocol_mode_key), "nmea");
        notificationManager = (NotificationManager) callingService.getSystemService(Context.NOTIFICATION_SERVICE);
        parser.setLocationManager(locationManager);

        // Set up UBX parser listeners
        setupUbxParser();

        // Set up Dead Reckoning command sender
        drManager.setCommandSender(new DeadReckoningManager.CommandSender() {
            @Override
            public void sendUbxCommand(byte[] data) {
                USBGpsManager.this.sendUbxCommand(data);
            }
        });

        Intent stopIntent = new Intent(USBGpsProviderService.ACTION_STOP_GPS_PROVIDER);

        PendingIntent stopPendingIntent = PendingIntent.getService(appContext, 0, stopIntent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        connectionProblemNotificationBuilder = new NotificationCompat.Builder(appContext)
                .setContentIntent(stopPendingIntent)
                .setSmallIcon(R.drawable.ic_stat_notify);


        Intent restartIntent = new Intent(USBGpsProviderService.ACTION_START_GPS_PROVIDER);
        PendingIntent restartPendingIntent = PendingIntent.getService(appContext, 0, restartIntent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        serviceStoppedNotificationBuilder = new NotificationCompat.Builder(appContext)
                .setContentIntent(restartPendingIntent)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(appContext.getString(R.string.service_closed_because_connection_problem_notification_title))
                .setContentText(appContext.getString(R.string.service_closed_because_connection_problem_notification));

        usbManager = (UsbManager) callingService.getSystemService(Service.USB_SERVICE);

    }

    private void setDisableReason(int reasonId) {
        disableReason = reasonId;
    }

    /**
     * @return
     */
    public int getDisableReason() {
        return disableReason;
    }

    /**
     * @return true if the bluetooth GPS is enabled
     */
    public synchronized boolean isEnabled() {
        return enabled;
    }


    public boolean isMockLocationEnabled() {
        // Checks if mock location is enabled in settings

        boolean isMockLocation;

        try {
            //If marshmallow or higher then we need to check that this app is set as the provider
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AppOpsManager opsManager = (AppOpsManager)
                        appContext.getSystemService(Context.APP_OPS_SERVICE);
                isMockLocation =
                        opsManager.checkOp(
                                AppOpsManager.OPSTR_MOCK_LOCATION,
                                android.os.Process.myUid(),
                                BuildConfig.APPLICATION_ID
                        ) == AppOpsManager.MODE_ALLOWED;

            } else {
                // Anything below it then we just need to check the tickbox is checked.
                isMockLocation =
                        !android.provider.Settings.Secure.getString(
                                appContext.getContentResolver(),
                                "mock_location"
                        ).equals("0");
            }

        } catch (Exception e) {
            return false;
        }

        return isMockLocation;
    }

    /**
     * Starts the connection for the given usb gps device
     * @param device GPS device
     */
    private void openConnection(UsbDevice device) {
        UsbDevice attached = getDeviceFromAttached();
        if (attached == null || !attached.equals(device)) {
            debugLog("Device not found in attached list, skipping connection");
            return;
        }

        // After 10 seconds we can assume the GPS must have the
        // correct time and so we are ready to assume the GPS can
        // set the correct time
        new Handler(appContext.getMainLooper())
                .postDelayed(
                        new Runnable() {
                            @Override
                            public void run() {
                                timeSetAlready = false;
                            }
                        },
                        10000
                );

        connected = true;

        if (setDeviceSpeed) {
            log("will set device speed: " + deviceSpeed);

        } else {
            log("will use default device speed: " + defaultDeviceSpeed);
            deviceSpeed = defaultDeviceSpeed;
        }

        log("starting usb reading task");
        connectedGps = new ConnectedGps(device, deviceSpeed);
        if (isEnabled()) {
            connectionAndReadingPool.execute(connectedGps);
            log("usb reading thread started");
        }
    }

    // Known USB-serial chip vendor IDs
    private static final int[] KNOWN_SERIAL_VIDS = {
        5446,  // 0x1546 u-blox AG
        1027,  // 0x0403 FTDI
        4292,  // 0x10C4 Silicon Labs CP210x
        1659,  // 0x067B Prolific PL2303
        6790,  // 0x1A86 QinHeng CH340/CH341
        9025,  // 0x2341 Arduino/CDC
    };

    private UsbDevice getDeviceFromAttached() {
        debugLog("Checking all connected devices");

        // First try exact VID+PID match (user-configured)
        for (UsbDevice connectedDevice : usbManager.getDeviceList().values()) {
            debugLog("Checking device: VID=" + connectedDevice.getVendorId() + " PID=" + connectedDevice.getProductId());
            if (connectedDevice.getVendorId() == gpsVendorId && connectedDevice.getProductId() == gpsProductId) {
                debugLog("Found exact match device");
                return connectedDevice;
            }
        }

        // Fallback: match any known USB-serial chip by VID only
        for (UsbDevice connectedDevice : usbManager.getDeviceList().values()) {
            for (int vid : KNOWN_SERIAL_VIDS) {
                if (connectedDevice.getVendorId() == vid) {
                    debugLog("Found device by known VID: " + vid + " PID: " + connectedDevice.getProductId());
                    // Update stored IDs so subsequent lookups are fast
                    gpsVendorId = connectedDevice.getVendorId();
                    gpsProductId = connectedDevice.getProductId();
                    return connectedDevice;
                }
            }
        }

        return null;
    }

    /**
     * Enables the USB GPS Provider.
     *
     * @return
     */
    public synchronized boolean enable() {
        IntentFilter permissionFilter = new IntentFilter(ACTION_USB_PERMISSION);
        permissionFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        notificationManager.cancel(
                R.string.service_closed_because_connection_problem_notification_title
        );

        if (!enabled) {
            log("enabling USB GPS manager");

            if (!isMockLocationEnabled()) {
                if (BuildConfig.DEBUG || debug)
                    Log.e(LOG_TAG, "Mock location provider OFF");
                disable(R.string.msg_mock_location_disabled);
                return this.enabled;

            } else if (PackageManager.PERMISSION_GRANTED  !=
                    ContextCompat.checkSelfPermission(
                            callingService, Manifest.permission.ACCESS_FINE_LOCATION)
                    ) {
                if (BuildConfig.DEBUG || debug)
                    Log.e(LOG_TAG, "No location permission given");
                disable(R.string.msg_no_location_permission);
                return this.enabled;

            } else {
                gpsDev = getDeviceFromAttached();

                // This thread will be run by the executor at a delay of 1 second, and will be
                // run again if the read thread dies. It will run until maximum number of retries
                // is exceeded
                Runnable connectThread = new Runnable() {
                        @Override
                        public void run() {
                            try {
                                debugLog("Starting connect thread");
                                connected = false;
                                gpsDev = getDeviceFromAttached();

                                if (nbRetriesRemaining > 0) {
                                    if (connectedGps != null) {
                                        connectedGps.close();
                                    }

                                    if (gpsDev != null) {
                                        debugLog("GPS device: " + gpsDev.getDeviceName());

                                        PendingIntent permissionIntent = PendingIntent.getBroadcast(callingService, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
                                        UsbDevice device = gpsDev;

                                        if (device != null && usbManager.hasPermission(device)) {
                                            debugLog("We have permission, good!");
                                            openConnection(device);

                                        } else if (device != null) {
                                            debugLog("We don't have permission, so requesting...");
                                            usbManager.requestPermission(device, permissionIntent);

                                        } else {
                                            if (BuildConfig.DEBUG || debug)
                                                Log.e(LOG_TAG, "Error while establishing connection: no device - " + gpsVendorId + ": " + gpsProductId);
                                            disable(R.string.msg_usb_provider_device_not_connected);
                                        }
                                    } else {
                                        if (BuildConfig.DEBUG || debug)
                                            Log.e(LOG_TAG, "Device not connected");
                                    }
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            } finally {
                                nbRetriesRemaining--;
                                if (!connected) {
                                    disableIfNeeded();
                                }
                            }

                        }
                    };

                    if (gpsDev != null) {
                        this.enabled = true;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            callingService.registerReceiver(permissionAndDetachReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED);
                        } else {
                            callingService.registerReceiver(permissionAndDetachReceiver, permissionFilter);
                        }

                        debugLog("USB GPS manager enabled");

                        notificationPool = Executors.newSingleThreadExecutor();
                        debugLog("starting connection and reading thread");
                        connectionAndReadingPool = Executors.newSingleThreadScheduledExecutor();

                        debugLog("starting connection to socket task");
                        connectionAndReadingPool.scheduleWithFixedDelay(
                                connectThread,
                                1000,
                                1000,
                                TimeUnit.MILLISECONDS
                        );

                        if (sirfGps) {
                            enableSirfConfig(sharedPreferences);
                        }
                    }
                }

                if (!this.enabled) {
                    if (BuildConfig.DEBUG || debug)
                        Log.e(LOG_TAG, "Error while establishing connection: no device");
                    disable(R.string.msg_usb_provider_device_not_connected);
                }
        }
        return this.enabled;
    }

    /**
     * Disables the USB GPS Provider if the maximal number of connection retries is exceeded.
     * This is used when there are possibly non fatal connection problems.
     * In these cases the provider will try to reconnect with the usb device
     * and only after a given retries number will give up and shutdown the service.
     */
    private synchronized void disableIfNeeded() {
        if (enabled) {
            problemNotified = true;
            if (nbRetriesRemaining > 0) {
                // Unable to connect
                if (BuildConfig.DEBUG || debug)
                    Log.e(LOG_TAG, "Connection ended");

                String pbMessage = appContext.getResources()
                        .getQuantityString(
                                R.plurals.connection_problem_notification,
                                nbRetriesRemaining,
                                nbRetriesRemaining
                        );

                Notification connectionProblemNotification = connectionProblemNotificationBuilder
                        .setWhen(System.currentTimeMillis())
                        .setContentTitle(
                                appContext.getString(R.string.connection_problem_notification_title)
                        )
                        .setContentText(pbMessage)
                        .setNumber(1 + maxConnectionRetries - nbRetriesRemaining)
                        .build();

                notificationManager.notify(
                        R.string.connection_problem_notification_title,
                        connectionProblemNotification
                );

            } else {
                disable(R.string.msg_two_many_connection_problems);

            }
        }
    }

    /**
     * Disables the USB GPS provider.
     * <p>
     * It will:
     * <ul>
     * <li>close the connection with the bluetooth device</li>
     * <li>disable the Mock Location Provider used for the Usb GPS</li>
     * <li>stop the UsbGPS4Droid service</li>
     * </ul>
     * The reasonId parameter indicates the reason to close the bluetooth provider.
     * If its value is zero, it's a normal shutdown (normally, initiated by the user).
     * If it's non-zero this value should correspond a valid localized string id (res/values..../...)
     * which will be used to display a notification.
     *
     * @param reasonId the reason to close the bluetooth provider.
     */
    public synchronized void disable(int reasonId) {
        debugLog("disabling USB GPS manager reason: " + callingService.getString(reasonId));
        setDisableReason(reasonId);
        disable();
    }

    /**
     * Disables the Usb GPS provider.
     * <p>
     * It will:
     * <ul>
     * <li>close the connection with the bluetooth device</li>
     * <li>disable the Mock Location Provider used for the bluetooth GPS</li>
     * <li>stop the BlueGPS4Droid service</li>
     * </ul>
     * If the bluetooth provider is closed because of a problem, a notification is displayed.
     */
    public synchronized void disable() {
        notificationManager.cancel(R.string.connection_problem_notification_title);

        if (getDisableReason() != 0) {
            NotificationCompat.Builder partialServiceStoppedNotification =
                    serviceStoppedNotificationBuilder
                            .setWhen(System.currentTimeMillis())
                            .setAutoCancel(true)
                            .setContentTitle(
                                    appContext.getString(
                                            R.string.service_closed_because_connection_problem_notification_title
                                    )
                            )
                            .setContentText(
                                    appContext.getString(
                                            R.string.service_closed_because_connection_problem_notification,
                                            appContext.getString(getDisableReason())
                                    )
                            );

            // Make the correct notification to direct the user to the correct setting
            if (getDisableReason() == R.string.msg_mock_location_disabled) {
                PendingIntent mockLocationsSettingsIntent =
                        PendingIntent.getActivity(
                            appContext,
                            0,
                            new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                            PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        );

                partialServiceStoppedNotification
                        .setContentIntent(mockLocationsSettingsIntent)
                        .setStyle(
                                new NotificationCompat.BigTextStyle().bigText(
                                        appContext.getString(
                                                R.string.service_closed_because_connection_problem_notification,
                                                appContext.getString(R.string.msg_mock_location_disabled_full))
                                )
                        );

            } else if (getDisableReason() == R.string.msg_no_location_permission) {
                PendingIntent mockLocationsSettingsIntent = PendingIntent.getActivity(
                        appContext,
                        0,
                        new Intent(callingService, GpsInfoActivity.class),
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);

                USBGpsApplication.setLocationNotAsked();

                partialServiceStoppedNotification
                        .setContentIntent(mockLocationsSettingsIntent)
                        .setStyle(
                                new NotificationCompat.BigTextStyle().bigText(
                                        appContext.getString(
                                                R.string.service_closed_because_connection_problem_notification,
                                                appContext.getString(R.string.msg_no_location_permission)
                                        )
                                )
                        );
            }

            Notification serviceStoppedNotification = partialServiceStoppedNotification.build();
            notificationManager.notify(
                    R.string.service_closed_because_connection_problem_notification_title,
                    serviceStoppedNotification
            );

            sharedPreferences
                    .edit()
                    .putInt(
                            appContext.getString(R.string.pref_disable_reason_key),
                            getDisableReason()
                    )
                    .apply();
        }

        if (enabled) {
            debugLog("disabling USB GPS manager");
            callingService.unregisterReceiver(permissionAndDetachReceiver);

            enabled = false;
            connectionAndReadingPool.shutdown();

            Runnable closeAndShutdown = new Runnable() {
                @Override
                public void run() {
                    try {
                        connectionAndReadingPool.awaitTermination(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    if (!connectionAndReadingPool.isTerminated()) {
                        connectionAndReadingPool.shutdownNow();
                        if (connectedGps != null) {
                            connectedGps.close();
                        }

                    }
                }
            };

            notificationPool.execute(closeAndShutdown);
            nmeaListeners.clear();
            disableMockLocationProvider();
            if (drManager != null) {
                drManager.release();
            }
            notificationPool.shutdown();
            callingService.stopSelf();

            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean(USBGpsProviderService.PREF_START_GPS_PROVIDER, false);
            editor.apply();

            debugLog("USB GPS manager disabled");
        }
    }

    /**
     * Enables the Mock GPS Location Provider used for the bluetooth GPS.
     * In fact, it delegates to the NMEA parser.
     *
     * @param gpsName the name of the Location Provider to use for the bluetooth GPS
     * @param force   true if we want to force auto-activation of the mock location provider (and bypass user preference).
     */
    public void enableMockLocationProvider(String gpsName, boolean force) {
        if (parser != null) {
            debugLog("enabling mock locations provider: " + gpsName);
            parser.enableMockLocationProvider(gpsName, force);
        }
    }

    /**
     * Enables the Mock GPS Location Provider used for the bluetooth GPS.
     * In fact, it delegates to the NMEA parser.
     *
     * @param gpsName the name of the Location Provider to use for the bluetooth GPS
     */
    public void enableMockLocationProvider(String gpsName) {
        if (parser != null) {
            debugLog("enabling mock locations provider: " + gpsName);
            boolean force = sharedPreferences.getBoolean(
                    USBGpsProviderService.PREF_FORCE_ENABLE_PROVIDER, true
            );
            parser.enableMockLocationProvider(gpsName, force);
        }
    }

    /**
     * Disables the current Mock GPS Location Provider used for the bluetooth GPS.
     * In fact, it delegates to the NMEA parser.
     *
     * @see NmeaParser#disableMockLocationProvider()
     */
    public void disableMockLocationProvider() {
        if (parser != null) {
            debugLog("disabling mock locations provider");
            parser.disableMockLocationProvider();
        }
    }

    /**
     * Getter use to know if the Mock GPS Listener used for the bluetooth GPS is enabled or not.
     * In fact, it delegates to the NMEA parser.
     *
     * @return true if the Mock GPS Listener used for the bluetooth GPS is enabled.
     * @see NmeaParser#isMockGpsEnabled()
     */
    public boolean isMockGpsEnabled() {
        boolean mockGpsEnabled = false;
        if (parser != null) {
            mockGpsEnabled = parser.isMockGpsEnabled();
        }
        return mockGpsEnabled;
    }

    /**
     * Getter for the name of the current Mock Location Provider in use.
     * In fact, it delegates to the NMEA parser.
     *
     * @return the Mock Location Provider name used for the bluetooth GPS
     * @see NmeaParser#getMockLocationProvider()
     */
    public String getMockLocationProvider() {
        String mockLocationProvider = null;
        if (parser != null) {
            mockLocationProvider = parser.getMockLocationProvider();
        }
        return mockLocationProvider;
    }

    /**
     * Indicates that the bluetooth GPS Provider is out of service.
     * In fact, it delegates to the NMEA parser.
     *
     * @see NmeaParser#setMockLocationProviderOutOfService()
     */
    private void setMockLocationProviderOutOfService() {
        if (parser != null) {
            parser.setMockLocationProviderOutOfService();
        }
    }

    /**
     * Adds an NMEA listener.
     * In fact, it delegates to the NMEA parser.
     *
     * @param listener a {@link NmeaListener} object to register
     * @return true if the listener was successfully added
     */
    public boolean addNmeaListener(NmeaListener listener) {
        if (!nmeaListeners.contains(listener)) {
            debugLog("adding new NMEA listener");
            nmeaListeners.add(listener);
        }
        return true;
    }

    /**
     * Removes an NMEA listener.
     * In fact, it delegates to the NMEA parser.
     *
     * @param listener a {@link NmeaListener} object to remove
     */
    public void removeNmeaListener(NmeaListener listener) {
        debugLog("removing NMEA listener");
        nmeaListeners.remove(listener);
    }

    /**
     * Sets the system time to the given UTC time value
     * @param time UTC value HHmmss.SSS
     */
    @SuppressLint("SimpleDateFormat")
    private void setSystemTime(String time) {
        long parseTime = parser.parseNmeaTime(time);

        Log.v(LOG_TAG, "What?: " + parseTime);

        String timeFormatToybox =
                new SimpleDateFormat("MMddHHmmyyyy.ss").format(new Date(parseTime));

        String timeFormatToolbox =
                new SimpleDateFormat("yyyyMMdd.HHmmss").format(new Date(parseTime));

        debugLog("Setting system time to: " + timeFormatToybox);
        SuperuserManager suManager = SuperuserManager.getInstance();

        debugLog("toolbox date -s " + timeFormatToolbox+ "; toybox date " + timeFormatToybox +
                "; am broadcast -a android.intent.action.TIME_SET");

        if (suManager.hasPermission()) {
            suManager.asyncExecute("toolbox date -s " + timeFormatToolbox+ "; toybox date " + timeFormatToybox +
                    "; am broadcast -a android.intent.action.TIME_SET");
        } else {
            sharedPreferences
                    .edit()
                    .putBoolean(USBGpsProviderService.PREF_SET_TIME, false)
                    .apply();
        }
    }

    /**
     * Notifies the reception of a NMEA sentence from the USB GPS to registered NMEA listeners.
     *
     * @param nmeaSentence the complete NMEA sentence received from the USB GPS (i.e. $....*XY where XY is the checksum)
     * @return true if the input string is a valid NMEA sentence, false otherwise.
     */
    private boolean notifyNmeaSentence(final String nmeaSentence) {
        boolean res = false;
        if (enabled) {
            log("parsing and notifying NMEA sentence: " + nmeaSentence);
            String sentence = null;
            try {
                if (shouldSetTime && !timeSetAlready) {
                    parser.clearLastSentenceTime();
                }

                sentence = parser.parseNmeaSentence(nmeaSentence);

                if (shouldSetTime && !timeSetAlready) {
                    if (!parser.getLastSentenceTime().isEmpty()) {
                        setSystemTime(parser.getLastSentenceTime());
                        timeSetAlready = true;
                    }
                }

            } catch (SecurityException e) {
                if (BuildConfig.DEBUG || debug)
                    Log.e(LOG_TAG, "Mock location permission issue: " + nmeaSentence, e);
                // Continue without mock location - data still shown in UI
            } catch (Exception e) {
                if (BuildConfig.DEBUG || debug) {
                    Log.e(LOG_TAG, "Sentence not parsable");
                    Log.e(LOG_TAG, nmeaSentence);
                }
                e.printStackTrace();
            }
            final String recognizedSentence = sentence;
            final long timestamp = System.currentTimeMillis();
            if (recognizedSentence != null) {
                res = true;
                log("notifying NMEA sentence: " + recognizedSentence);

                ((USBGpsApplication) appContext).notifyNewSentence(
                        recognizedSentence.replaceAll("(\\r|\\n)", "")
                );

                synchronized (nmeaListeners) {
                    for (final NmeaListener listener : nmeaListeners) {
                        notificationPool.execute(new Runnable() {
                            @Override
                            public void run() {
                                listener.onNmeaReceived(timestamp, recognizedSentence);
                            }
                        });
                    }
                }
            }
        }
        return res;
    }

    /**
     * Sends a NMEA sentence to the bluetooth GPS.
     *
     * @param command the complete NMEA sentence (i.e. $....*XY where XY is the checksum).
     */
    public void sendPackagedNmeaCommand(final String command) {
        log("sending NMEA sentence: " + command);
        connectedGps.write(command);
        log("sent NMEA sentence: " + command);
    }

    /**
     * Sends a SIRF III binary command to the bluetooth GPS.
     *
     * @param commandHexa an hexadecimal string representing a complete binary command
     *                    (i.e. with the <em>Start Sequence</em>, <em>Payload Length</em>, <em>Payload</em>, <em>Message Checksum</em> and <em>End Sequence</em>).
     */
    public void sendPackagedSirfCommand(final String commandHexa) {
        final byte[] command = SirfUtils.genSirfCommand(commandHexa);
        log("sendind SIRF sentence: " + commandHexa);
        connectedGps.write(command);
        log("sent SIRF sentence: " + commandHexa);
    }

    /**
     * Sends a NMEA sentence to the bluetooth GPS.
     *
     * @param sentence the NMEA sentence without the first "$", the last "*" and the checksum.
     */
    public void sendNmeaCommand(String sentence) {
        String command = String.format((Locale) null, "$%s*%02X\r\n", sentence, parser.computeChecksum(sentence));
        sendPackagedNmeaCommand(command);
    }

    /**
     * Sends a SIRF III binary command to the bluetooth GPS.
     *
     * @param payload an hexadecimal string representing the payload of the binary command
     *                (i.e. without <em>Start Sequence</em>, <em>Payload Length</em>, <em>Message Checksum</em> and <em>End Sequence</em>).
     */
    public void sendSirfCommand(String payload) {
        String command = SirfUtils.createSirfCommandFromPayload(payload);
        sendPackagedSirfCommand(command);
    }

    private void enableNMEA(boolean enable) {
//            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(callingService);
//            String deviceSpeed = sharedPreferences.getString(USBGpsProviderService.PREF_GPS_DEVICE_SPEED, callingService.getString(R.string.defaultGpsDeviceSpeed));
        if (deviceSpeed.equals(callingService.getString(R.string.autoGpsDeviceSpeed))) {
            deviceSpeed = callingService.getString(R.string.defaultGpsDeviceSpeed);
        }
        SystemClock.sleep(400);
        if (enable) {
//                int gll = (sharedPreferences.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GLL, false)) ? 1 : 0 ;
//                int vtg = (sharedPreferences.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_VTG, false)) ? 1 : 0 ;
//                int gsa = (sharedPreferences.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GSA, false)) ? 5 : 0 ;
//                int gsv = (sharedPreferences.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GSV, false)) ? 5 : 0 ;
//                int zda = (sharedPreferences.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_ZDA, false)) ? 1 : 0 ;
//                int mss = 0;
//                int epe = 0;
//                int gga = 1;
//                int rmc = 1;
//                String command = getString(R.string.sirf_bin_to_nmea_38400_alt, gga, gll, gsa, gsv, rmc, vtg, mss, epe, zda);
//                String command = getString(R.string.sirf_bin_to_nmea_alt, gga, gll, gsa, gsv, rmc, vtg, mss, epe, zda, Integer.parseInt(deviceSpeed));
            String command = callingService.getString(R.string.sirf_bin_to_nmea);
            this.sendSirfCommand(command);
        } else {
//                this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_to_binary));
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_to_binary_alt, Integer.parseInt(deviceSpeed)));
        }
        SystemClock.sleep(400);
    }

    private void enableNmeaGGA(boolean enable) {
        if (enable) {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_gga_on));
        } else {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_gga_off));
        }
    }

    private void enableNmeaGLL(boolean enable) {
        if (enable) {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_gll_on));
        } else {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_gll_off));
        }
    }

    private void enableNmeaGSA(boolean enable) {
        if (enable) {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_gsa_on));
        } else {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_gsa_off));
        }
    }

    private void enableNmeaGSV(boolean enable) {
        if (enable) {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_gsv_on));
        } else {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_gsv_off));
        }
    }

    private void enableNmeaRMC(boolean enable) {
        if (enable) {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_rmc_on));
        } else {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_rmc_off));
        }
    }

    private void enableNmeaVTG(boolean enable) {
        if (enable) {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_vtg_on));
        } else {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_vtg_off));
        }
    }

    private void enableNmeaZDA(boolean enable) {
        if (enable) {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_zda_on));
        } else {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_zda_off));
        }
    }

    private void enableSBAS(boolean enable) {
        if (enable) {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_sbas_on));
        } else {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_sbas_off));
        }
    }

    public void enableSirfConfig(final Bundle extra) {
        debugLog("spooling SiRF config: " + extra);
        if (isEnabled()) {
            notificationPool.execute(new Runnable() {
                @Override
                public void run() {
                    while ((enabled) && ((!connected) || (connectedGps == null) || (!connectedGps.isReady()))) {
                        debugLog("writing thread is not ready");
                        SystemClock.sleep(500);
                    }
                    if (isEnabled() && (connected) && (connectedGps != null) && (connectedGps.isReady())) {
                        debugLog("init SiRF config: " + extra);
                        if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_GGA)) {
                            enableNmeaGGA(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GGA, true));
                        }
                        if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_RMC)) {
                            enableNmeaRMC(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_RMC, true));
                        }
                        if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_GLL)) {
                            enableNmeaGLL(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GLL, false));
                        }
                        if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_VTG)) {
                            enableNmeaVTG(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_VTG, false));
                        }
                        if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_GSA)) {
                            enableNmeaGSA(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GSA, false));
                        }
                        if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_GSV)) {
                            enableNmeaGSV(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GSV, false));
                        }
                        if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_ZDA)) {
                            enableNmeaZDA(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_ZDA, false));
                        }
                        if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_STATIC_NAVIGATION)) {
                            enableStaticNavigation(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_STATIC_NAVIGATION, false));
                        } else if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_NMEA)) {
                            enableNMEA(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_NMEA, true));
                        }
                        if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_SBAS)) {
                            enableSBAS(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_SBAS, true));
                        }
                        debugLog("initialized SiRF config: " + extra);
                    }
                }
            });
        }
    }

    public void enableSirfConfig(final SharedPreferences extra) {
        debugLog("spooling SiRF config: " + extra);
        if (isEnabled()) {
            notificationPool.execute(new Runnable() {
                @Override
                public void run() {
                    while ((enabled) && ((!connected) || (connectedGps == null) || (!connectedGps.isReady()))) {
                        debugLog("writing thread is not ready");
                        SystemClock.sleep(500);
                    }
                    if (isEnabled() && (connected) && (connectedGps != null) && (connectedGps.isReady())) {
                        debugLog("init SiRF config: " + extra);
                        if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_GLL)) {
                            enableNmeaGLL(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GLL, false));
                        }
                        if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_VTG)) {
                            enableNmeaVTG(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_VTG, false));
                        }
                        if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_GSA)) {
                            enableNmeaGSA(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GSA, false));
                        }
                        if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_GSV)) {
                            enableNmeaGSV(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GSV, false));
                        }
                        if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_ZDA)) {
                            enableNmeaZDA(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_ZDA, false));
                        }
                        if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_STATIC_NAVIGATION)) {
                            enableStaticNavigation(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_STATIC_NAVIGATION, false));
                        } else if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_NMEA)) {
                            enableNMEA(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_NMEA, true));
                        }
                        if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_SBAS)) {
                            enableSBAS(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_SBAS, true));
                        }
                        sendNmeaCommand(callingService.getString(R.string.sirf_nmea_gga_on));
                        sendNmeaCommand(callingService.getString(R.string.sirf_nmea_rmc_on));
                        if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_GGA)) {
                            enableNmeaGGA(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GGA, true));
                        }
                        if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_RMC)) {
                            enableNmeaRMC(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_RMC, true));
                        }
                    }
                }
            });
        }
    }

    private void enableStaticNavigation(boolean enable) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(callingService);
        boolean isInNmeaMode = sharedPreferences.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_NMEA, true);
        if (isInNmeaMode) {
            enableNMEA(false);
        }
        if (enable) {
            this.sendSirfCommand(callingService.getString(R.string.sirf_bin_static_nav_on));
        } else {
            this.sendSirfCommand(callingService.getString(R.string.sirf_bin_static_nav_off));
        }
        if (isInNmeaMode) {
            enableNMEA(true);
        }
    }

    // ============================================================
    // UBX Integration
    // ============================================================

    /**
     * Set up the UBX parser with listeners that create Location objects.
     */
    private void setupUbxParser() {
        ubxParser.setUbxListener(new UbxParser.UbxListener() {
            @Override
            public void onUbxNavPvt(double lat, double lon, int alt, int altMsl,
                                     int speed, int bearing, int hAcc,
                                     int fixType, int numSV, long timeMs, int valid) {
                if (!enabled) return;

                // Only process valid fixes (fixType 2=2D, 3=3D, 4=GNSS+DR, 5=time only)
                if (fixType >= 2 && fixType <= 4 && parser.isMockGpsEnabled()) {
                    Location fix = new Location(parser.getMockLocationProvider());
                    fix.setLatitude(lat);
                    fix.setLongitude(lon);
                    fix.setAltitude(altMsl / 1000.0); // mm to meters
                    fix.setSpeed(speed / 1000.0f);     // mm/s to m/s
                    fix.setBearing(bearing / 100000.0f); // 1e-5 degrees to degrees
                    fix.setAccuracy(hAcc / 1000.0f);   // mm to meters
                    fix.setTime(timeMs);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                    }

                    Bundle extras = new Bundle();
                    extras.putInt(NmeaParser.SATELLITE_KEY, numSV);
                    extras.putLong(NmeaParser.SYSTEM_TIME_FIX, System.currentTimeMillis());
                    extras.putInt("ubxFixType", fixType);
                    fix.setExtras(extras);

                    try {
                        ((USBGpsApplication) appContext).notifyNewLocation(fix);
                        locationManager.setTestProviderLocation(
                                parser.getMockLocationProvider(), fix);
                        debugLog("UBX NAV-PVT fix: " + lat + ", " + lon +
                                " alt=" + (altMsl / 1000.0) +
                                " spd=" + (speed / 1000.0) +
                                " sv=" + numSV);
                    } catch (SecurityException e) {
                        if (BuildConfig.DEBUG || debug)
                            Log.e(LOG_TAG, "SecurityException setting UBX location", e);
                    } catch (IllegalArgumentException e) {
                        debugLog("UBX fix incomplete, skipping");
                    }

                    // Notify sentence to log
                    String ubxLog = String.format("UBX-NAV-PVT: %.6f,%.6f alt=%.1f spd=%.1f sv=%d fix=%d",
                            lat, lon, altMsl / 1000.0, speed / 1000.0, numSV, fixType);
                    ((USBGpsApplication) appContext).notifyNewSentence(ubxLog);

                } else if (fixType == 0 || fixType == 1) {
                    // No fix or dead reckoning only
                    if (parser.isMockGpsEnabled()) {
                        try {
                            locationManager.setTestProviderStatus(
                                    parser.getMockLocationProvider(),
                                    LocationProvider.TEMPORARILY_UNAVAILABLE,
                                    null, System.currentTimeMillis());
                        } catch (SecurityException e) {
                            // ignore
                        }
                    }
                }
            }

            @Override
            public void onUbxNavSol(int fixType, int flags, int numSV,
                                     double ecefX, double ecefY, double ecefZ,
                                     int pAcc, int week, long timeMs) {
                debugLog("UBX NAV-SOL: fix=" + fixType + " sv=" + numSV + " pAcc=" + pAcc + "cm");
            }

            @Override
            public void onUbxNavStatus(int fixType, int flags, int fixStat,
                                        int flags2, long ttff, long msss) {
                debugLog("UBX NAV-STATUS: fix=" + fixType + " ttff=" + ttff + "ms");
            }

            @Override
            public void onUbxNavSvInfo(int numCh, int globalFlags, UbxParser.SvInfo[] svInfos) {
                int usedCount = 0;
                for (UbxParser.SvInfo sv : svInfos) {
                    if (sv.isUsed()) usedCount++;
                }
                debugLog("UBX NAV-SVINFO: " + numCh + " channels, " + usedCount + " used");
            }

            @Override
            public void onUbxNavEkfStatus(int pulses, int period, int gyroMean,
                                           int temperature, int direction,
                                           int calibStatus, int pulseScale,
                                           int gyroBias, int gyroScale) {
                debugLog("UBX NAV-EKFSTATUS: calib=" + calibStatus + " dir=" + direction);
                drManager.onEkfStatus(pulses, period, gyroMean, temperature,
                        direction, calibStatus, pulseScale, gyroBias, gyroScale);
            }
        });
    }

    /**
     * Send a raw UBX command to the receiver.
     *
     * @param data complete UBX frame bytes
     */
    public void sendUbxCommand(final byte[] data) {
        if (connectedGps != null && enabled) {
            debugLog("Sending UBX command: " + data.length + " bytes");
            connectedGps.write(data);
        }
    }

    /**
     * Configure the measurement rate via UBX-CFG-RATE.
     *
     * @param rateMs measurement rate in milliseconds
     */
    public void configureRate(final int rateMs) {
        debugLog("Configuring UBX rate: " + rateMs + "ms");
        if (connectedGps != null && enabled) {
            notificationPool.execute(new Runnable() {
                @Override
                public void run() {
                    waitForReady();
                    sendUbxCommand(UbxCommands.cfgRate(rateMs));
                }
            });
        }
    }

    /**
     * Configure the dynamic model via UBX-CFG-NAV5.
     *
     * @param dynModel one of UbxCommands.DYN_MODEL_* constants
     */
    public void configureDynamicModel(final int dynModel) {
        debugLog("Configuring UBX dynamic model: " + dynModel);
        if (connectedGps != null && enabled) {
            notificationPool.execute(new Runnable() {
                @Override
                public void run() {
                    waitForReady();
                    sendUbxCommand(UbxCommands.cfgNav5DynModel(dynModel));
                }
            });
        }
    }

    /**
     * Enable or disable SBAS via UBX-CFG-SBAS.
     *
     * @param enable true to enable SBAS
     */
    public void configureUbxSbas(final boolean enable) {
        debugLog("Configuring UBX SBAS: " + enable);
        if (connectedGps != null && enabled) {
            notificationPool.execute(new Runnable() {
                @Override
                public void run() {
                    waitForReady();
                    sendUbxCommand(UbxCommands.cfgSbas(enable));
                }
            });
        }
    }

    /**
     * Configure protocol mode (NMEA only, UBX only, or both).
     *
     * @param mode "nmea", "ubx", or "both"
     */
    public void configureProtocolMode(final String mode) {
        debugLog("Configuring protocol mode: " + mode);
        this.protocolMode = mode;

        if (connectedGps != null && enabled) {
            notificationPool.execute(new Runnable() {
                @Override
                public void run() {
                    waitForReady();

                    int baudRate = Integer.parseInt(
                            setDeviceSpeed ? deviceSpeed : defaultDeviceSpeed);

                    switch (mode) {
                        case "ubx":
                            // UBX only: enable UBX output, disable NMEA output
                            sendUbxCommand(UbxCommands.cfgPrtUart1(baudRate, true, false, true, false));
                            // Enable NAV-PVT at every solution
                            SystemClock.sleep(200);
                            sendUbxCommand(UbxCommands.enableNavPvt(1));
                            SystemClock.sleep(100);
                            sendUbxCommand(UbxCommands.enableNavStatus(1));
                            SystemClock.sleep(100);
                            sendUbxCommand(UbxCommands.enableNavSvInfo(5));
                            break;

                        case "both":
                            // Both protocols
                            sendUbxCommand(UbxCommands.cfgPrtUart1(baudRate, true, true, true, true));
                            SystemClock.sleep(200);
                            sendUbxCommand(UbxCommands.enableNavPvt(1));
                            break;

                        case "nmea":
                        default:
                            // NMEA only: enable NMEA output, disable UBX output
                            sendUbxCommand(UbxCommands.cfgPrtUart1(baudRate, false, true, false, true));
                            break;
                    }
                }
            });
        }
    }

    /**
     * Enable or disable Dead Reckoning.
     *
     * @param enable true to enable DR
     */
    public void configureDeadReckoning(final boolean enable) {
        debugLog("Configuring Dead Reckoning: " + enable);
        if (enable) {
            drManager.enableDR();
        } else {
            drManager.disableDR();
        }
    }

    /**
     * Save current configuration to receiver flash.
     */
    public void saveUbxConfig() {
        debugLog("Saving UBX config to flash");
        if (connectedGps != null && enabled) {
            notificationPool.execute(new Runnable() {
                @Override
                public void run() {
                    waitForReady();
                    sendUbxCommand(UbxCommands.saveConfig());
                }
            });
        }
    }

    /**
     * Reset the receiver.
     *
     * @param type "hot", "warm", or "cold"
     */
    public void resetReceiver(final String type) {
        debugLog("Resetting receiver: " + type);
        if (connectedGps != null && enabled) {
            notificationPool.execute(new Runnable() {
                @Override
                public void run() {
                    waitForReady();
                    switch (type) {
                        case "warm":
                            sendUbxCommand(UbxCommands.warmStart());
                            break;
                        case "cold":
                            sendUbxCommand(UbxCommands.coldStart());
                            break;
                        case "hot":
                        default:
                            sendUbxCommand(UbxCommands.hotStart());
                            break;
                    }
                }
            });
        }
    }

    /**
     * Wait until the connected GPS is ready for commands.
     */
    private void waitForReady() {
        while ((enabled) && ((!connected) || (connectedGps == null) || (!connectedGps.isReady()))) {
            debugLog("waiting for GPS to be ready for UBX commands");
            SystemClock.sleep(500);
        }
    }

    /**
     * Get the Dead Reckoning manager.
     */
    public DeadReckoningManager getDeadReckoningManager() {
        return drManager;
    }

    private void log(String message) {
        Log.w(LOG_TAG, message);
    }

    private void debugLog(String message) {
        Log.w(LOG_TAG, message);
    }
}
