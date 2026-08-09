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

import android.util.Log;

import org.broeuschmeul.android.gps.usb.provider.BuildConfig;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Parser for u-blox UBX binary protocol frames.
 *
 * UBX frame format:
 *   Sync1(0xB5) Sync2(0x62) Class(1) ID(1) Length(2, little-endian) Payload(Length) CK_A(1) CK_B(1)
 *
 * Handles mixed NMEA+UBX streams by detecting the first byte:
 *   '$' (0x24) = NMEA sentence
 *   0xB5 = UBX binary frame
 *
 * Supports partial reads and buffer reassembly.
 */
public class UbxParser {

    private static final String LOG_TAG = UbxParser.class.getSimpleName();

    // UBX sync bytes
    public static final byte SYNC1 = (byte) 0xB5;
    public static final byte SYNC2 = (byte) 0x62;

    // UBX message classes
    public static final byte CLASS_NAV = 0x01;
    public static final byte CLASS_CFG = 0x06;

    // NAV message IDs
    public static final byte NAV_STATUS = 0x03;
    public static final byte NAV_SOL = 0x06;
    public static final byte NAV_PVT = 0x07;
    public static final byte NAV_SVINFO = 0x30;
    public static final byte NAV_EKFSTATUS = 0x40;

    // Parser states
    private static final int STATE_SYNC1 = 0;
    private static final int STATE_SYNC2 = 1;
    private static final int STATE_CLASS = 2;
    private static final int STATE_ID = 3;
    private static final int STATE_LEN1 = 4;
    private static final int STATE_LEN2 = 5;
    private static final int STATE_PAYLOAD = 6;
    private static final int STATE_CK_A = 7;
    private static final int STATE_CK_B = 8;

    private int state = STATE_SYNC1;
    private byte msgClass;
    private byte msgId;
    private int payloadLength;
    private int payloadIndex;
    private byte[] payload;
    private byte ckA;
    private byte ckB;
    private byte calcCkA;
    private byte calcCkB;

    // Buffer for NMEA sentences detected in mixed stream
    private StringBuilder nmeaBuffer = new StringBuilder();
    private boolean inNmea = false;

    private UbxListener listener;
    private NmeaByteListener nmeaByteListener;

    /**
     * Listener for parsed UBX messages.
     */
    public interface UbxListener {
        /**
         * Called when a NAV-PVT message is parsed.
         *
         * @param lat       latitude in degrees
         * @param lon       longitude in degrees
         * @param alt       altitude in mm above ellipsoid
         * @param altMsl    altitude in mm above mean sea level
         * @param speed     ground speed in mm/s
         * @param bearing   heading of motion in degrees * 1e-5
         * @param hAcc      horizontal accuracy estimate in mm
         * @param fixType   fix type: 0=no fix, 2=2D, 3=3D, 4=GNSS+DR, 5=time only
         * @param numSV     number of satellites used
         * @param timeMs    UTC time in milliseconds since epoch
         * @param valid     validity flags
         */
        void onUbxNavPvt(double lat, double lon, int alt, int altMsl,
                         int speed, int bearing, int hAcc,
                         int fixType, int numSV, long timeMs, int valid);

        /**
         * Called when a NAV-SOL message is parsed.
         */
        void onUbxNavSol(int fixType, int flags, int numSV,
                         double ecefX, double ecefY, double ecefZ,
                         int pAcc, int week, long timeMs);

        /**
         * Called when a NAV-STATUS message is parsed.
         */
        void onUbxNavStatus(int fixType, int flags, int fixStat, int flags2, long ttff, long msss);

        /**
         * Called when a NAV-SVINFO message is parsed.
         */
        void onUbxNavSvInfo(int numCh, int globalFlags, SvInfo[] svInfos);

        /**
         * Called when a NAV-EKFSTATUS message is parsed (Dead Reckoning status).
         */
        void onUbxNavEkfStatus(int pulses, int period, int gyroMean, int temperature,
                               int direction, int calibStatus, int pulseScale,
                               int gyroBias, int gyroScale);
    }

    /**
     * Listener for NMEA bytes detected in a mixed UBX+NMEA stream.
     */
    public interface NmeaByteListener {
        /**
         * Called when a complete NMEA sentence is detected.
         */
        void onNmeaSentence(String sentence);
    }

    /**
     * Satellite info from NAV-SVINFO.
     */
    public static class SvInfo {
        public int chn;
        public int svid;
        public int flags;
        public int quality;
        public int cno;    // signal strength dBHz
        public int elev;   // elevation degrees
        public int azim;   // azimuth degrees

        public boolean isUsed() {
            return (flags & 0x01) != 0;
        }
    }

    public UbxParser() {
    }

    public void setUbxListener(UbxListener listener) {
        this.listener = listener;
    }

    public void setNmeaByteListener(NmeaByteListener listener) {
        this.nmeaByteListener = listener;
    }

    /**
     * Feed raw bytes from USB serial into the parser.
     * Handles mixed NMEA + UBX streams.
     *
     * @param data   byte array
     * @param offset start offset
     * @param length number of bytes to process
     */
    public void process(byte[] data, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            byte b = data[i];

            // Handle NMEA detection in mixed stream
            if (inNmea) {
                nmeaBuffer.append((char) (b & 0xFF));
                if (b == '\n') {
                    inNmea = false;
                    if (nmeaByteListener != null) {
                        nmeaByteListener.onNmeaSentence(nmeaBuffer.toString());
                    }
                    nmeaBuffer.setLength(0);
                }
                continue;
            }

            // If we're waiting for sync and we see '$', switch to NMEA mode
            if (state == STATE_SYNC1 && b == '$') {
                inNmea = true;
                nmeaBuffer.setLength(0);
                nmeaBuffer.append('$');
                continue;
            }

            processByte(b);
        }
    }

    private void processByte(byte b) {
        switch (state) {
            case STATE_SYNC1:
                if (b == SYNC1) {
                    state = STATE_SYNC2;
                }
                break;

            case STATE_SYNC2:
                if (b == SYNC2) {
                    state = STATE_CLASS;
                } else {
                    state = STATE_SYNC1;
                }
                break;

            case STATE_CLASS:
                msgClass = b;
                calcCkA = b;
                calcCkB = b;
                state = STATE_ID;
                break;

            case STATE_ID:
                msgId = b;
                calcCkA += b;
                calcCkB += calcCkA;
                state = STATE_LEN1;
                break;

            case STATE_LEN1:
                payloadLength = b & 0xFF;
                calcCkA += b;
                calcCkB += calcCkA;
                state = STATE_LEN2;
                break;

            case STATE_LEN2:
                payloadLength |= (b & 0xFF) << 8;
                calcCkA += b;
                calcCkB += calcCkA;
                if (payloadLength > 0 && payloadLength <= 8192) {
                    payload = new byte[payloadLength];
                    payloadIndex = 0;
                    state = STATE_PAYLOAD;
                } else if (payloadLength == 0) {
                    state = STATE_CK_A;
                } else {
                    // Invalid length, reset
                    debugLog("Invalid UBX payload length: " + payloadLength);
                    state = STATE_SYNC1;
                }
                break;

            case STATE_PAYLOAD:
                payload[payloadIndex++] = b;
                calcCkA += b;
                calcCkB += calcCkA;
                if (payloadIndex >= payloadLength) {
                    state = STATE_CK_A;
                }
                break;

            case STATE_CK_A:
                ckA = b;
                state = STATE_CK_B;
                break;

            case STATE_CK_B:
                ckB = b;
                if ((ckA & 0xFF) == (calcCkA & 0xFF) && (ckB & 0xFF) == (calcCkB & 0xFF)) {
                    handleMessage(msgClass, msgId, payload, payloadLength);
                } else {
                    debugLog("UBX checksum mismatch: expected " +
                            String.format("%02X%02X", calcCkA & 0xFF, calcCkB & 0xFF) +
                            " got " +
                            String.format("%02X%02X", ckA & 0xFF, ckB & 0xFF));
                }
                state = STATE_SYNC1;
                break;
        }
    }

    /**
     * Dispatch parsed UBX message to appropriate handler.
     */
    private void handleMessage(byte msgClass, byte msgId, byte[] payload, int length) {
        if (listener == null) return;

        if (msgClass == CLASS_NAV) {
            switch (msgId) {
                case NAV_PVT:
                    parseNavPvt(payload, length);
                    break;
                case NAV_SOL:
                    parseNavSol(payload, length);
                    break;
                case NAV_STATUS:
                    parseNavStatus(payload, length);
                    break;
                case NAV_SVINFO:
                    parseNavSvInfo(payload, length);
                    break;
                case NAV_EKFSTATUS:
                    parseNavEkfStatus(payload, length);
                    break;
                default:
                    debugLog("Unhandled NAV message ID: " + String.format("0x%02X", msgId & 0xFF));
                    break;
            }
        } else {
            debugLog("Unhandled UBX class: " + String.format("0x%02X", msgClass & 0xFF));
        }
    }

    /**
     * Parse NAV-PVT (0x01 0x07) — Navigation Position Velocity Time Solution.
     * Expected payload length: 92 bytes (protocol version >= 15) or 84 bytes.
     */
    private void parseNavPvt(byte[] data, int length) {
        if (length < 84) {
            debugLog("NAV-PVT payload too short: " + length);
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // iTOW (ms) at offset 0
        long iTow = buf.getInt(0) & 0xFFFFFFFFL;

        // Time fields
        int year = buf.getShort(4) & 0xFFFF;
        int month = buf.get(6) & 0xFF;
        int day = buf.get(7) & 0xFF;
        int hour = buf.get(8) & 0xFF;
        int min = buf.get(9) & 0xFF;
        int sec = buf.get(10) & 0xFF;
        int valid = buf.get(11) & 0xFF;

        // Time accuracy (ns) at offset 12
        long tAcc = buf.getInt(12) & 0xFFFFFFFFL;

        // Nanoseconds of second at offset 16
        int nano = buf.getInt(16);

        // Fix type at offset 20
        int fixType = buf.get(20) & 0xFF;

        // Flags at offset 21
        int flags = buf.get(21) & 0xFF;

        // Additional flags at offset 22
        int flags2 = buf.get(22) & 0xFF;

        // Number of SVs used at offset 23
        int numSV = buf.get(23) & 0xFF;

        // Longitude (1e-7 degrees) at offset 24
        int lonRaw = buf.getInt(24);
        double lon = lonRaw / 1e7;

        // Latitude (1e-7 degrees) at offset 28
        int latRaw = buf.getInt(28);
        double lat = latRaw / 1e7;

        // Height above ellipsoid (mm) at offset 32
        int height = buf.getInt(32);

        // Height above mean sea level (mm) at offset 36
        int hMSL = buf.getInt(36);

        // Horizontal accuracy (mm) at offset 40
        int hAcc = (int) (buf.getInt(40) & 0xFFFFFFFFL);

        // Vertical accuracy (mm) at offset 44
        int vAcc = (int) (buf.getInt(44) & 0xFFFFFFFFL);

        // NED velocity (mm/s) at offsets 48, 52, 56
        int velN = buf.getInt(48);
        int velE = buf.getInt(52);
        int velD = buf.getInt(56);

        // Ground speed (mm/s) at offset 60
        int gSpeed = buf.getInt(60);

        // Heading of motion (1e-5 degrees) at offset 64
        int headMot = buf.getInt(64);

        // Speed accuracy (mm/s) at offset 68
        int sAcc = (int) (buf.getInt(68) & 0xFFFFFFFFL);

        // Heading accuracy (1e-5 degrees) at offset 72
        int headAcc = (int) (buf.getInt(72) & 0xFFFFFFFFL);

        // Convert time to millis since epoch
        long timeMs = 0;
        if ((valid & 0x03) == 0x03) { // validDate and validTime
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            cal.set(year, month - 1, day, hour, min, sec);
            cal.set(Calendar.MILLISECOND, nano / 1000000);
            timeMs = cal.getTimeInMillis();
        }

        listener.onUbxNavPvt(lat, lon, height, hMSL, gSpeed, headMot, hAcc,
                fixType, numSV, timeMs, valid);
    }

    /**
     * Parse NAV-SOL (0x01 0x06) — Navigation Solution.
     * Expected payload length: 52 bytes.
     */
    private void parseNavSol(byte[] data, int length) {
        if (length < 52) {
            debugLog("NAV-SOL payload too short: " + length);
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        long iTow = buf.getInt(0) & 0xFFFFFFFFL;
        int fTow = buf.getInt(4);   // fractional TOW (ns)
        int week = buf.getShort(8) & 0xFFFF;
        int gpsFix = buf.get(10) & 0xFF;
        int flags = buf.get(11) & 0xFF;

        // ECEF position (cm)
        int ecefX = buf.getInt(12);
        int ecefY = buf.getInt(16);
        int ecefZ = buf.getInt(20);

        // Position accuracy (cm)
        int pAcc = (int) (buf.getInt(24) & 0xFFFFFFFFL);

        // Number of SVs used
        int numSV = buf.get(47) & 0xFF;

        // Convert GPS week + iTow to millis
        long gpsEpochMs = 315964800000L; // Jan 6, 1980 UTC
        long timeMs = gpsEpochMs + (long) week * 604800000L + iTow;

        listener.onUbxNavSol(gpsFix, flags, numSV,
                ecefX / 100.0, ecefY / 100.0, ecefZ / 100.0,
                pAcc, week, timeMs);
    }

    /**
     * Parse NAV-STATUS (0x01 0x03) — Receiver Navigation Status.
     * Expected payload length: 16 bytes.
     */
    private void parseNavStatus(byte[] data, int length) {
        if (length < 16) {
            debugLog("NAV-STATUS payload too short: " + length);
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        int gpsFix = buf.get(4) & 0xFF;
        int flags = buf.get(5) & 0xFF;
        int fixStat = buf.get(6) & 0xFF;
        int flags2 = buf.get(7) & 0xFF;
        long ttff = buf.getInt(8) & 0xFFFFFFFFL;  // time to first fix (ms)
        long msss = buf.getInt(12) & 0xFFFFFFFFL;  // ms since startup

        listener.onUbxNavStatus(gpsFix, flags, fixStat, flags2, ttff, msss);
    }

    /**
     * Parse NAV-SVINFO (0x01 0x30) — Satellite Information.
     * Header: 8 bytes, then 12 bytes per channel.
     */
    private void parseNavSvInfo(byte[] data, int length) {
        if (length < 8) {
            debugLog("NAV-SVINFO payload too short: " + length);
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        int numCh = buf.get(4) & 0xFF;
        int globalFlags = buf.get(5) & 0xFF;

        int expectedLen = 8 + numCh * 12;
        if (length < expectedLen) {
            debugLog("NAV-SVINFO incomplete: expected " + expectedLen + " got " + length);
            numCh = (length - 8) / 12;
        }

        SvInfo[] svInfos = new SvInfo[numCh];
        for (int i = 0; i < numCh; i++) {
            int base = 8 + i * 12;
            SvInfo sv = new SvInfo();
            sv.chn = buf.get(base) & 0xFF;
            sv.svid = buf.get(base + 1) & 0xFF;
            sv.flags = buf.get(base + 2) & 0xFF;
            sv.quality = buf.get(base + 3) & 0xFF;
            sv.cno = buf.get(base + 4) & 0xFF;
            sv.elev = buf.get(base + 5);  // signed
            sv.azim = buf.getShort(base + 6);
            svInfos[i] = sv;
        }

        listener.onUbxNavSvInfo(numCh, globalFlags, svInfos);
    }

    /**
     * Parse NAV-EKFSTATUS (0x01 0x40) — Dead Reckoning status.
     * Expected payload length: 36 bytes.
     */
    private void parseNavEkfStatus(byte[] data, int length) {
        if (length < 36) {
            debugLog("NAV-EKFSTATUS payload too short: " + length);
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        int pulses = buf.getInt(0);
        int period = buf.getInt(4);
        int gyroMean = buf.getInt(8);
        int temperature = buf.getShort(12);
        int direction = buf.get(14) & 0xFF;
        int calibStatus = buf.get(15) & 0xFF;
        int pulseScale = buf.getInt(16);
        int gyroBias = buf.getInt(20);
        int gyroScale = buf.getInt(24);

        listener.onUbxNavEkfStatus(pulses, period, gyroMean, temperature,
                direction, calibStatus, pulseScale, gyroBias, gyroScale);
    }

    /**
     * Check if byte indicates start of a UBX frame.
     */
    public static boolean isUbxSync(byte b) {
        return b == SYNC1;
    }

    /**
     * Check if byte indicates start of an NMEA sentence.
     */
    public static boolean isNmeaStart(byte b) {
        return b == '$';
    }

    /**
     * Reset parser state. Call when reconnecting.
     */
    public void reset() {
        state = STATE_SYNC1;
        inNmea = false;
        nmeaBuffer.setLength(0);
    }

    private void debugLog(String message) {
        if (BuildConfig.DEBUG) Log.d(LOG_TAG, message);
    }
}
