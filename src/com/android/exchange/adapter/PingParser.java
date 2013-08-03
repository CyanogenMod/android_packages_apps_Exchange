/* Copyright (C) 2008-2009 Marc Blank
 * Licensed to The Android Open Source Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.exchange.adapter;

import com.android.mail.utils.LogUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Parse the result of a Ping command.
 * After {@link #parse()}, {@link #getPingStatus()} will give a valid status value. Also, when
 * appropriate one of {@link #getSyncList()}, {@link #getMaxFolders()}, or
 * {@link #getHeartbeatInterval()} will contain further detailed results of the parsing.
 */
public class PingParser extends Parser {
    private static final String TAG = "PingParser";

    /** Sentinel value, used when some property doesn't have a meaningful value. */
    public static final int NO_VALUE = -1;

    // Values less than STATUS_FAILED are never actually returned by the PingParser. They detect
    // error statuses that happen outside the actual parsing, but since they are used in the same
    // context as parsing statuses, it's convenient to have them here.
    /** Indicates that the ping returned a redirect error. */
    public static final int STATUS_REDIRECT = -6;
    /** Indicates that the ping terminated due to an exception while making the POST. */
    public static final int STATUS_NETWORK_EXCEPTION = -5;
    /** Indicates that the ping was interrupted by a sync request. */
    public static final int STATUS_INTERRUPTED = -4;
    /** Indicates that there were no folders to ping. */
    public static final int STATUS_NO_FOLDERS = -3;

    /** Indicates a malformed response, or some other client failure while processing the ping. */
    public static final int STATUS_FAILED = -2;

    // The following are the actual status codes from the Exchange server.
    // See http://msdn.microsoft.com/en-us/library/gg663456(v=exchg.80).aspx for more details.
    /** Indicates that the heartbeat interval expired before a change happened. */
    public static final int STATUS_EXPIRED = 1;
    /** Indicates that one or more of the pinged folders changed. */
    public static final int STATUS_CHANGES_FOUND = 2;
    /** Indicates that the ping request was missing required parameters. */
    public static final int STATUS_REQUEST_INCOMPLETE = 3;
    /** Indicates that the ping request was malformed. */
    public static final int STATUS_REQUEST_MALFORMED = 4;
    /** Indicates that the ping request specified a bad heartbeat (too small or too big). */
    public static final int STATUS_REQUEST_HEARTBEAT_OUT_OF_BOUNDS = 5;
    /** Indicates that the ping requested more folders than the server will permit. */
    public static final int STATUS_REQUEST_TOO_MANY_FOLDERS = 6;
    /** Indicates that the folder structure is out of sync. */
    public static final int STATUS_FOLDER_REFRESH_NEEDED = 7;
    /** Indicates a server error. */
    public static final int STATUS_SERVER_ERROR = 8;

    private int mPingStatus = NO_VALUE;
    private final ArrayList<String> mSyncList = new ArrayList<String>();
    private int mMaxFolders = NO_VALUE;
    private int mHeartbeatInterval = NO_VALUE;

    public PingParser(final InputStream in) throws IOException {
        super(in);
    }

    @Override
    public boolean parse() {
        final boolean result = parseInternal();
        if (!result) {
            mPingStatus = STATUS_FAILED;
        }
        return result;
    }

    /**
     * @return The status for this ping.
     */
    public int getPingStatus() {
        return mPingStatus;
    }

    /**
     * If {@link #getPingStatus} indicates that there are folders to sync, this will return which
     * folders need syncing.
     * @return The list of folders to sync, or null if sync was not indicated in the response.
     */
    public ArrayList<String> getSyncList() {
        if (mPingStatus != STATUS_CHANGES_FOUND) {
            return null;
        }
        return mSyncList;
    }

    /**
     * If {@link #getPingStatus} indicates that we asked for too many folders, this will return the
     * limit.
     * @return The maximum number of folders we may ping, or {@link #NO_VALUE} if no maximum was
     * indicated in the response.
     */
    public int getMaxFolders() {
        if (mPingStatus != STATUS_REQUEST_TOO_MANY_FOLDERS) {
            return NO_VALUE;
        }
        return mMaxFolders;
    }

    /**
     * If {@link #getPingStatus} indicates that we specified an invalid heartbeat, this will return
     * a valid heartbeat to use.
     * @return If our request asked for too small a heartbeat, this will return the minimum value
     *         permissible. If the request was too large, this will return the maximum value
     *         permissible. Otherwise, this returns {@link #NO_VALUE}.
     */
    public int getHeartbeatInterval() {
        if (mPingStatus != STATUS_REQUEST_HEARTBEAT_OUT_OF_BOUNDS) {
            return NO_VALUE;
        }
        return mHeartbeatInterval;
    }

    /**
     * Checks whether a status code implies we ought to send another ping immediately.
     * @param pingStatus The ping status value we wish to check.
     * @return Whether we should send another ping immediately.
     */
    public static boolean shouldPingAgain(final int pingStatus) {
        // Explanation for why we ping again for each case:
        // - Redirect errors have already been processed by updating the HostAuth to reflect the
        //   new address, so we just ping again for those.
        // - If the ping expired we should keep looping with pings.
        // - The EAS spec says to handle incomplete and malformed request errors by pinging again
        //   with corrected request data. Since we always send a complete request, we simply
        //   repeat (and assume that some sort of network error is what caused the corruption).
        // - Heartbeat errors are handled by pinging with a better heartbeat value.
        // - Other server errors are considered transient and therefore we just reping for those.
        return pingStatus == STATUS_REDIRECT
                || pingStatus == STATUS_EXPIRED
                || pingStatus == STATUS_REQUEST_INCOMPLETE
                || pingStatus == STATUS_REQUEST_MALFORMED
                // TODO: Implement heartbeat adjusting and re-enable this.
                // || pingStatus == STATUS_REQUEST_HEARTBEAT_OUT_OF_BOUNDS
                || pingStatus == STATUS_SERVER_ERROR;
    }

    /**
     * Parse the Folders element of the ping response, and store the results.
     * @throws IOException
     */
    private void parsePingFolders() throws IOException {
        while (nextTag(Tags.PING_FOLDERS) != END) {
            if (tag == Tags.PING_FOLDER) {
                // Here we'll keep track of which mailboxes need syncing
                String serverId = getValue();
                mSyncList.add(serverId);
                LogUtils.i(TAG, "Changes found in: %s", serverId);
            } else {
                skipTag();
            }
        }
    }

    /**
     * Parse an integer value from the response for a particular property, and bounds check the
     * new value. A property cannot be set more than once.
     * @param name The name of the property we're parsing (for logging purposes).
     * @param currentValue The current value of the property we're parsing.
     * @param minValue The minimum value for the property we're parsing.
     * @param maxValue The maximum value for the property we're parsing.
     * @return The new value of the property we're parsing, or {@link #NO_VALUE} if there was an
     *         error.
     */
    private int getValue(final String name, final int currentValue, final int minValue,
            final int maxValue) {
        if (currentValue != NO_VALUE) {
            LogUtils.e(TAG, "Response has multiple values for %s", name);
            return NO_VALUE;
        }
        final int value;
        try {
            value = getValueInt();
        } catch (final IOException e) {
            LogUtils.e(TAG, "IOException while parsing %s: %s", name, e.getMessage());
            return NO_VALUE;
        }
        if (value < minValue || (maxValue > 0 && value > maxValue)) {
            LogUtils.e(TAG, "%s value out of bound (%d)", name, value);
            return NO_VALUE;
        }
        return value;
    }

    /**
     * Parse an integer value from the response for a particular property, and ensure it is
     * positive. A value cannot be set more than once.
     * @param name The name of the property we're parsing (for logging purposes).
     * @param currentValue The current value of the property we're parsing.
     * @return The new value of the property we're parsing, or {@link #NO_VALUE} if there was an
     *         error.
     */
    private int getValue(final String name, final int currentValue) {
        return getValue(name, currentValue, 1, -1);
    }

    /**
     * Parse the entire response, and set our internal state accordingly.
     * @return Whether the response was well-formed.
     */
    private boolean parseInternal() {
        try {
            if (nextTag(START_DOCUMENT) != Tags.PING_PING) {
                LogUtils.e(TAG, "Ping response does not include a Ping element");
                return false;
            }
            while (nextTag(START_DOCUMENT) != END_DOCUMENT) {
                if (tag == Tags.PING_STATUS) {
                    mPingStatus = getValue("Status", mPingStatus, STATUS_EXPIRED,
                            STATUS_SERVER_ERROR);
                    if (mPingStatus == NO_VALUE) {
                        return false;
                    }
                } else if (tag == Tags.PING_MAX_FOLDERS) {
                    mMaxFolders = getValue("MaxFolders", mMaxFolders);
                    if (mMaxFolders == NO_VALUE) {
                        return false;
                    }
                } else if (tag == Tags.PING_FOLDERS) {
                    if (!mSyncList.isEmpty()) {
                        LogUtils.e(TAG, "Response has multiple values for Folders");
                        mPingStatus = STATUS_FAILED;
                        return false;
                    }
                    parsePingFolders();
                    final int count = mSyncList.size();
                    LogUtils.i(TAG, "Folders has %d elements", count);
                    if (count == 0) {
                        LogUtils.e(TAG, "Folders was empty");
                        return false;
                    }
                } else if (tag == Tags.PING_HEARTBEAT_INTERVAL) {
                    mHeartbeatInterval = getValue("HeartbeatInterval", mHeartbeatInterval);
                    if (mHeartbeatInterval == NO_VALUE) {
                        return false;
                    }
                } else {
                    // TODO: Error?
                    skipTag();
                }
            }
        } catch (final IOException e) {
            LogUtils.e(TAG, "IOException while parsing ping response");
            return false;
        }

        // Check the parse results for status values that don't match the other output.
        switch (mPingStatus) {
            case NO_VALUE:
                return false;
            case STATUS_CHANGES_FOUND:
                return !mSyncList.isEmpty();
            case STATUS_REQUEST_HEARTBEAT_OUT_OF_BOUNDS:
                return mHeartbeatInterval != NO_VALUE;
            case STATUS_REQUEST_TOO_MANY_FOLDERS:
                return mMaxFolders != NO_VALUE;
        }
        return true;
    }
}
