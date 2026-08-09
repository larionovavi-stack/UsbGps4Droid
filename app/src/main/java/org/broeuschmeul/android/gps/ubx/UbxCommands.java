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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Generator for u-blox UBX-CFG configuration commands.
 *
 * All methods return a complete UBX frame (sync + class + id + length + payload + checksum)
 * ready to send via USB serial.
 *
 * Checksum algorithm: Fletcher 8-bit over class + id + length + payload.
 */
public class UbxCommands {

    // Dynamic model constants for CFG-NAV5
    public static final int DYN_MODEL_PORTABLE = 0;
    public static final int DYN_MODEL_STATIONARY = 2;
    public static final int DYN_MODEL_PEDESTRIAN = 3;
    public static final int DYN_MODEL_AUTOMOTIVE = 4;
    public static final int DYN_MODEL_SEA = 5;
    public static final int DYN_MODEL_AIRBORNE_1G = 6;
    public static final int DYN_MODEL_AIRBORNE_2G = 7;
    public static final int DYN_MODEL_AIRBORNE_4G = 8;

    // Reset modes for CFG-RST
    public static final int RESET_HOT = 0x0000;
    public static final int RESET_WARM = 0x0001;
    public static final int RESET_COLD = 0xFFFF;

    // Reset type
    public static final int RESET_HW = 0x00;
    public static final int RESET_SW = 0x01;
    public static final int RESET_SW_GNSS_ONLY = 0x02;
    public static final int RESET_HW_AFTER_SHUTDOWN = 0x04;
    public static final int RESET_GNSS_STOP = 0x08;
    public static final int RESET_GNSS_START = 0x09;

    // CFG-CFG clear/save/load masks
    public static final int CFG_MASK_IOPORT = 0x0001;
    public static final int CFG_MASK_MSGCONF = 0x0002;
    public static final int CFG_MASK_INFMSG = 0x0004;
    public static final int CFG_MASK_NAVCONF = 0x0008;
    public static final int CFG_MASK_RXMCONF = 0x0010;
    public static final int CFG_MASK_RINVCONF = 0x0200;
    public static final int CFG_MASK_ANTCONF = 0x0400;
    public static final int CFG_MASK_ALL = 0x061F;

    /**
     * Build a complete UBX frame from class, id, and payload.
     */
    public static byte[] buildUbxFrame(byte msgClass, byte msgId, byte[] payload) {
        int payloadLen = (payload != null) ? payload.length : 0;
        byte[] frame = new byte[8 + payloadLen]; // sync(2) + class(1) + id(1) + len(2) + payload + ck(2)

        frame[0] = (byte) 0xB5;
        frame[1] = (byte) 0x62;
        frame[2] = msgClass;
        frame[3] = msgId;
        frame[4] = (byte) (payloadLen & 0xFF);
        frame[5] = (byte) ((payloadLen >> 8) & 0xFF);

        if (payload != null) {
            System.arraycopy(payload, 0, frame, 6, payloadLen);
        }

        // Fletcher checksum over class + id + length + payload
        byte ckA = 0, ckB = 0;
        for (int i = 2; i < 6 + payloadLen; i++) {
            ckA += frame[i];
            ckB += ckA;
        }
        frame[6 + payloadLen] = ckA;
        frame[7 + payloadLen] = ckB;

        return frame;
    }

    // ========== CFG-RATE (0x06 0x08) ==========

    /**
     * Set measurement rate.
     *
     * @param measRateMs measurement rate in milliseconds (e.g. 100, 200, 500, 1000)
     * @param navRate    navigation rate in cycles (usually 1)
     * @param timeRef    time reference (0=UTC, 1=GPS)
     * @return complete UBX frame
     */
    public static byte[] cfgRate(int measRateMs, int navRate, int timeRef) {
        byte[] payload = new byte[6];
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort(0, (short) measRateMs);
        buf.putShort(2, (short) navRate);
        buf.putShort(4, (short) timeRef);
        return buildUbxFrame((byte) 0x06, (byte) 0x08, payload);
    }

    /**
     * Set measurement rate with default navRate=1, timeRef=UTC.
     *
     * @param measRateMs measurement rate in milliseconds
     * @return complete UBX frame
     */
    public static byte[] cfgRate(int measRateMs) {
        return cfgRate(measRateMs, 1, 0);
    }

    // ========== CFG-MSG (0x06 0x01) ==========

    /**
     * Enable or disable a specific message on the current port.
     *
     * @param msgClass message class
     * @param msgId    message ID
     * @param rate     output rate on current port (0 = disabled, 1 = every solution, etc.)
     * @return complete UBX frame
     */
    public static byte[] cfgMsg(byte msgClass, byte msgId, int rate) {
        byte[] payload = new byte[3];
        payload[0] = msgClass;
        payload[1] = msgId;
        payload[2] = (byte) rate;
        return buildUbxFrame((byte) 0x06, (byte) 0x01, payload);
    }

    /**
     * Enable or disable a message on all 6 ports individually.
     *
     * @param msgClass message class
     * @param msgId    message ID
     * @param rates    array of 6 rates (DDC/I2C, UART1, UART2, USB, SPI, reserved)
     * @return complete UBX frame
     */
    public static byte[] cfgMsg(byte msgClass, byte msgId, int[] rates) {
        byte[] payload = new byte[8];
        payload[0] = msgClass;
        payload[1] = msgId;
        for (int i = 0; i < 6 && i < rates.length; i++) {
            payload[2 + i] = (byte) rates[i];
        }
        return buildUbxFrame((byte) 0x06, (byte) 0x01, payload);
    }

    /**
     * Enable NAV-PVT output.
     */
    public static byte[] enableNavPvt(int rate) {
        return cfgMsg((byte) 0x01, (byte) 0x07, rate);
    }

    /**
     * Enable NAV-SOL output.
     */
    public static byte[] enableNavSol(int rate) {
        return cfgMsg((byte) 0x01, (byte) 0x06, rate);
    }

    /**
     * Enable NAV-STATUS output.
     */
    public static byte[] enableNavStatus(int rate) {
        return cfgMsg((byte) 0x01, (byte) 0x03, rate);
    }

    /**
     * Enable NAV-SVINFO output.
     */
    public static byte[] enableNavSvInfo(int rate) {
        return cfgMsg((byte) 0x01, (byte) 0x30, rate);
    }

    /**
     * Disable NMEA GGA output.
     */
    public static byte[] disableNmeaGga() {
        return cfgMsg((byte) 0xF0, (byte) 0x00, 0);
    }

    /**
     * Disable NMEA GLL output.
     */
    public static byte[] disableNmeaGll() {
        return cfgMsg((byte) 0xF0, (byte) 0x01, 0);
    }

    /**
     * Disable NMEA GSA output.
     */
    public static byte[] disableNmeaGsa() {
        return cfgMsg((byte) 0xF0, (byte) 0x02, 0);
    }

    /**
     * Disable NMEA GSV output.
     */
    public static byte[] disableNmeaGsv() {
        return cfgMsg((byte) 0xF0, (byte) 0x03, 0);
    }

    /**
     * Disable NMEA RMC output.
     */
    public static byte[] disableNmeaRmc() {
        return cfgMsg((byte) 0xF0, (byte) 0x04, 0);
    }

    /**
     * Disable NMEA VTG output.
     */
    public static byte[] disableNmeaVtg() {
        return cfgMsg((byte) 0xF0, (byte) 0x05, 0);
    }

    /**
     * Enable NMEA GGA output.
     */
    public static byte[] enableNmeaGga(int rate) {
        return cfgMsg((byte) 0xF0, (byte) 0x00, rate);
    }

    /**
     * Enable NMEA RMC output.
     */
    public static byte[] enableNmeaRmc(int rate) {
        return cfgMsg((byte) 0xF0, (byte) 0x04, rate);
    }

    // ========== CFG-NAV5 (0x06 0x24) ==========

    /**
     * Set dynamic model.
     *
     * @param dynModel one of DYN_MODEL_* constants
     * @return complete UBX frame
     */
    public static byte[] cfgNav5DynModel(int dynModel) {
        byte[] payload = new byte[36];
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        // Mask: bit 0 = dyn model
        buf.putShort(0, (short) 0x0001);
        buf.put(2, (byte) dynModel);
        // fixMode at offset 3: 3 = auto 2D/3D
        buf.put(3, (byte) 3);
        return buildUbxFrame((byte) 0x06, (byte) 0x24, payload);
    }

    // ========== CFG-PRT (0x06 0x00) ==========

    /**
     * Set UART1 port baud rate.
     *
     * @param baudRate baud rate (e.g. 9600, 38400, 115200)
     * @return complete UBX frame
     */
    public static byte[] cfgPrtUart1(int baudRate) {
        byte[] payload = new byte[20];
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

        buf.put(0, (byte) 1);        // portID = UART1
        // reserved1 at offset 1
        // txReady at offset 2-3 (0 = disabled)
        // mode at offset 4-7: 8N1 = 0x000008D0
        buf.putInt(4, 0x000008D0);
        // baudRate at offset 8-11
        buf.putInt(8, baudRate);
        // inProtoMask at offset 12-13: UBX + NMEA = 0x0003
        buf.putShort(12, (short) 0x0003);
        // outProtoMask at offset 14-15: UBX + NMEA = 0x0003
        buf.putShort(14, (short) 0x0003);
        // flags at offset 16-17 (0)
        // reserved2 at offset 18-19 (0)

        return buildUbxFrame((byte) 0x06, (byte) 0x00, payload);
    }

    /**
     * Set UART1 protocol mask (which protocols are enabled for input/output).
     *
     * @param baudRate     baud rate
     * @param inUbx        enable UBX input
     * @param inNmea       enable NMEA input
     * @param outUbx       enable UBX output
     * @param outNmea      enable NMEA output
     * @return complete UBX frame
     */
    public static byte[] cfgPrtUart1(int baudRate, boolean inUbx, boolean inNmea,
                                      boolean outUbx, boolean outNmea) {
        byte[] payload = new byte[20];
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

        buf.put(0, (byte) 1);
        buf.putInt(4, 0x000008D0);
        buf.putInt(8, baudRate);

        int inProto = 0;
        if (inUbx) inProto |= 0x0001;
        if (inNmea) inProto |= 0x0002;
        buf.putShort(12, (short) inProto);

        int outProto = 0;
        if (outUbx) outProto |= 0x0001;
        if (outNmea) outProto |= 0x0002;
        buf.putShort(14, (short) outProto);

        return buildUbxFrame((byte) 0x06, (byte) 0x00, payload);
    }

    // ========== CFG-SBAS (0x06 0x16) ==========

    /**
     * Enable or disable SBAS (WAAS/EGNOS/MSAS/GAGAN).
     *
     * @param enable true to enable SBAS
     * @return complete UBX frame
     */
    public static byte[] cfgSbas(boolean enable) {
        byte[] payload = new byte[8];
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

        buf.put(0, (byte) (enable ? 0x01 : 0x00)); // mode: bit0 = enabled
        buf.put(1, (byte) 0x07); // usage: range + diffCorr + integrity
        buf.put(2, (byte) 0x03); // maxSBAS: 3
        // scanmode2 at offset 3 (0)
        // scanmode1 at offset 4-7: all PRNs
        buf.putInt(4, 0x00000000); // 0 = auto-scan

        return buildUbxFrame((byte) 0x06, (byte) 0x16, payload);
    }

    // ========== CFG-CFG (0x06 0x09) ==========

    /**
     * Save/Load/Clear configuration.
     *
     * @param clearMask sections to clear (use CFG_MASK_* constants)
     * @param saveMask  sections to save to flash
     * @param loadMask  sections to load from flash
     * @return complete UBX frame
     */
    public static byte[] cfgCfg(int clearMask, int saveMask, int loadMask) {
        byte[] payload = new byte[12];
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0, clearMask);
        buf.putInt(4, saveMask);
        buf.putInt(8, loadMask);
        return buildUbxFrame((byte) 0x06, (byte) 0x09, payload);
    }

    /**
     * Save/Load/Clear configuration with device mask.
     *
     * @param clearMask  sections to clear
     * @param saveMask   sections to save
     * @param loadMask   sections to load
     * @param deviceMask devices: bit0=BBR, bit1=Flash, bit2=EEPROM, bit4=SPI Flash
     * @return complete UBX frame
     */
    public static byte[] cfgCfg(int clearMask, int saveMask, int loadMask, int deviceMask) {
        byte[] payload = new byte[13];
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0, clearMask);
        buf.putInt(4, saveMask);
        buf.putInt(8, loadMask);
        buf.put(12, (byte) deviceMask);
        return buildUbxFrame((byte) 0x06, (byte) 0x09, payload);
    }

    /**
     * Save all configuration to flash.
     */
    public static byte[] saveConfig() {
        return cfgCfg(0, CFG_MASK_ALL, 0, 0x17); // save to all devices
    }

    /**
     * Load default configuration.
     */
    public static byte[] loadDefaults() {
        return cfgCfg(CFG_MASK_ALL, 0, CFG_MASK_ALL);
    }

    // ========== CFG-RST (0x06 0x04) ==========

    /**
     * Reset receiver.
     *
     * @param navBbrMask sections to clear (RESET_HOT=0, RESET_WARM=1, RESET_COLD=0xFFFF)
     * @param resetMode  reset type (RESET_HW, RESET_SW, etc.)
     * @return complete UBX frame
     */
    public static byte[] cfgRst(int navBbrMask, int resetMode) {
        byte[] payload = new byte[4];
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort(0, (short) navBbrMask);
        buf.put(2, (byte) resetMode);
        buf.put(3, (byte) 0); // reserved
        return buildUbxFrame((byte) 0x06, (byte) 0x04, payload);
    }

    /**
     * Hot start (fastest, keeps all data).
     */
    public static byte[] hotStart() {
        return cfgRst(RESET_HOT, RESET_SW);
    }

    /**
     * Warm start (clears ephemeris).
     */
    public static byte[] warmStart() {
        return cfgRst(RESET_WARM, RESET_SW);
    }

    /**
     * Cold start (clears everything, full reset).
     */
    public static byte[] coldStart() {
        return cfgRst(RESET_COLD, RESET_SW);
    }

    // ========== CFG-NAVX5 (0x06 0x23) - Dead Reckoning ==========

    /**
     * Enable or disable Automotive Dead Reckoning (ADR) on LEA-6R.
     *
     * @param enable true to enable DR
     * @return complete UBX frame
     */
    public static byte[] cfgNavx5Dr(boolean enable) {
        byte[] payload = new byte[40];
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

        // mask1 at offset 0-1: bit 10 = use ADR
        buf.putShort(0, (short) 0x0400);
        // mask2 at offset 2-3
        buf.putShort(2, (short) 0x0000);
        // reserved fields 4-5
        // adr at offset 26 (byte): 0=disabled, 1=enabled
        buf.put(26, (byte) (enable ? 1 : 0));

        return buildUbxFrame((byte) 0x06, (byte) 0x23, payload);
    }

    /**
     * Poll NAV-EKFSTATUS to get DR status.
     */
    public static byte[] pollNavEkfStatus() {
        return buildUbxFrame((byte) 0x01, (byte) 0x40, new byte[0]);
    }

    /**
     * Enable NAV-EKFSTATUS output.
     */
    public static byte[] enableNavEkfStatus(int rate) {
        return cfgMsg((byte) 0x01, (byte) 0x40, rate);
    }

    // ========== Utility ==========

    /**
     * Compute Fletcher 8-bit checksum for UBX (over class+id+length+payload).
     *
     * @param data   the frame bytes
     * @param offset start of class byte
     * @param length number of bytes to checksum (class+id+len+payload)
     * @return byte[2] = {ckA, ckB}
     */
    public static byte[] computeChecksum(byte[] data, int offset, int length) {
        byte ckA = 0, ckB = 0;
        for (int i = offset; i < offset + length; i++) {
            ckA += data[i];
            ckB += ckA;
        }
        return new byte[]{ckA, ckB};
    }
}
