/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.exchange.eas;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.database.Cursor;
import android.text.format.DateUtils;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;
import com.android.exchange.adapter.PingParser;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;
import com.android.mail.utils.LogUtils;

import org.apache.http.HttpEntity;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Performs an Exchange Ping, which is the command for receiving push notifications.
 * See http://msdn.microsoft.com/en-us/library/ee200913(v=exchg.80).aspx for more details.
 */
public class EasPing extends EasOperation {
    private static final String TAG = "EasPing";

    private static final String WHERE_ACCOUNT_KEY_AND_SERVER_ID =
            MailboxColumns.ACCOUNT_KEY + "=? and " + MailboxColumns.SERVER_ID + "=?";

    private final long mAccountId;
    private final android.accounts.Account mAmAccount;

    // TODO: Implement Heartbeat autoadjustments based on the server responses.
    /**
     * The heartbeat interval specified to the Exchange server. This is the maximum amount of
     * time (in seconds) that the server should wait before responding to the ping request.
     */
    private static final long PING_HEARTBEAT =
            8 * (DateUtils.MINUTE_IN_MILLIS / DateUtils.SECOND_IN_MILLIS);

    /** {@link #PING_HEARTBEAT}, as a String. */
    private static final String PING_HEARTBEAT_STRING = Long.toString(PING_HEARTBEAT);

    /**
     * The timeout used for the HTTP POST (in milliseconds). Notionally this should be the same
     * as {@link #PING_HEARTBEAT} but in practice is a few seconds longer to allow for latency
     * in the server's response.
     */
    private static final long POST_TIMEOUT = (5 + PING_HEARTBEAT) * DateUtils.SECOND_IN_MILLIS;

    public EasPing(final Context context, final Account account) {
        super(context, account);
        mAccountId = account.mId;
        mAmAccount = new android.accounts.Account(account.mEmailAddress,
                Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE);
    }

    public final int doPing() {
        final int result = performOperation(null);
        if (result == RESULT_RESTART) {
            return PingParser.STATUS_EXPIRED;
        }
        return result;
    }

    public final long getAccountId() {
        return mAccountId;
    }

    public final android.accounts.Account getAmAccount() {
        return mAmAccount;
    }

    @Override
    protected String getCommand() {
        return "Ping";
    }

    @Override
    protected HttpEntity getRequestEntity() throws IOException {
        // Get the mailboxes that need push notifications.
        final Cursor c = Mailbox.getMailboxesForPush(mContext.getContentResolver(),
                mAccountId);
        if (c == null) {
            throw new IllegalStateException("Could not read mailboxes");
        }

        // TODO: Ideally we never even get here unless we already know we want a push.
        Serializer s = null;
        try {
            while (c.moveToNext()) {
                final Mailbox mailbox = new Mailbox();
                mailbox.restore(c);
                s = handleOneMailbox(s, mailbox);
            }
        } finally {
            c.close();
        }

        if (s == null) {
            abort();
            throw new IOException("No mailboxes want push");
        }
        // This sequence of end()s corresponds to the start()s that occur in handleOneMailbox when
        // the Serializer is first created. If either side changes, the other must be kept in sync.
        s.end().end().done();
        return makeEntity(s);
    }

    @Override
    protected int handleResponse(final EasResponse response, final SyncResult syncResult)
            throws IOException {
        if (response.isEmpty()) {
            throw new IOException("Empty ping response");
        }

        // Handle a valid response.
        final PingParser pp = new PingParser(response.getInputStream());
        pp.parse();
        final int pingStatus = pp.getPingStatus();

        // Take the appropriate action for this response.
        // Many of the responses require no explicit action here, they just influence
        // our re-ping behavior, which is handled by the caller.
        switch (pingStatus) {
            case PingParser.STATUS_EXPIRED:
                LogUtils.i(TAG, "Ping expired for account %d", mAccountId);
                break;
            case PingParser.STATUS_CHANGES_FOUND:
                LogUtils.i(TAG, "Ping found changed folders for account %d", mAccountId);
                requestSyncForSyncList(pp.getSyncList());
                break;
            case PingParser.STATUS_REQUEST_INCOMPLETE:
            case PingParser.STATUS_REQUEST_MALFORMED:
                // These two cases indicate that the ping request was somehow bad.
                // TODO: It's insanity to re-ping with the same data and expect a different
                // result. Improve this if possible.
                LogUtils.e(TAG, "Bad ping request for account %d", mAccountId);
                break;
            case PingParser.STATUS_REQUEST_HEARTBEAT_OUT_OF_BOUNDS:
                LogUtils.i(TAG, "Heartbeat out of bounds for account %d", mAccountId);
                // TODO: Implement auto heartbeat adjustments.
                break;
            case PingParser.STATUS_REQUEST_TOO_MANY_FOLDERS:
                LogUtils.i(TAG, "Too many folders for account %d", mAccountId);
                break;
            case PingParser.STATUS_FOLDER_REFRESH_NEEDED:
                LogUtils.i(TAG, "FolderSync needed for account %d", mAccountId);
                requestFolderSync();
                break;
            case PingParser.STATUS_SERVER_ERROR:
                LogUtils.i(TAG, "Server error for account %d", mAccountId);
                break;
            default:
                break;
        }

        return pingStatus;
    }


    @Override
    protected boolean addPolicyKeyHeaderToRequest() {
        return false;
    }

    @Override
    protected long getTimeout() {
        return POST_TIMEOUT;
    }

    /**
     * If mailbox is eligible for push, add it to the ping request, creating the {@link Serializer}
     * for the request if necessary.
     * @param mailbox The mailbox to check.
     * @param s The {@link Serializer} for this request, or null if it hasn't been created yet.
     * @return The {@link Serializer} for this request, or null if it hasn't been created yet.
     * @throws IOException
     */
    private Serializer handleOneMailbox(Serializer s, final Mailbox mailbox) throws IOException {
        // We can't push until the initial sync is done
        if (mailbox.mSyncKey != null && !mailbox.mSyncKey.equals("0")) {
            if (ContentResolver.getSyncAutomatically(mAmAccount,
                    Mailbox.getAuthority(mailbox.mType))) {
                if (s == null) {
                    // No serializer yet, so create and initialize it.
                    // Note that these start()s correspond to the end()s in doInBackground.
                    // If either side changes, the other must be kept in sync.
                    s = new Serializer();
                    s.start(Tags.PING_PING);
                    s.data(Tags.PING_HEARTBEAT_INTERVAL, PING_HEARTBEAT_STRING);
                    s.start(Tags.PING_FOLDERS);
                }
                s.start(Tags.PING_FOLDER);
                s.data(Tags.PING_ID, mailbox.mServerId);
                s.data(Tags.PING_CLASS, Eas.getFolderClass(mailbox.mType));
                s.end();
            }
        }
        return s;
    }

    /**
     * Make the appropriate calls to {@link ContentResolver#requestSync} indicated by the
     * current ping response.
     * @param syncList The list of folders that need to be synced.
     */
    private void requestSyncForSyncList(final ArrayList<String> syncList) {
        final String[] bindArguments = new String[2];
        bindArguments[0] = Long.toString(mAccountId);
        for (final String serverId : syncList) {
            bindArguments[1] = serverId;
            // TODO: Rather than one query per ping mailbox, do it all in one?
            final Cursor c = mContext.getContentResolver().query(Mailbox.CONTENT_URI,
                    Mailbox.CONTENT_PROJECTION, WHERE_ACCOUNT_KEY_AND_SERVER_ID,
                    bindArguments, null);
            if (c == null) {
                // TODO: proper error handling.
                break;
            }
            try {
                /**
                 * Check the boxes reporting changes to see if there really were any...
                 * We do this because bugs in various Exchange servers can put us into a
                 * looping behavior by continually reporting changes in a mailbox, even
                 * when there aren't any.
                 *
                 * This behavior is seemingly random, and therefore we must code
                 * defensively by backing off of push behavior when it is detected.
                 *
                 * One known cause, on certain Exchange 2003 servers, is acknowledged by
                 * Microsoft, and the server hotfix for this case can be found at
                 * http://support.microsoft.com/kb/923282
                 */
                // TODO: Implement the above.
                    /*
                    String status = c.getString(Mailbox.CONTENT_SYNC_STATUS_COLUMN);
                    int type = ExchangeService.getStatusType(status);
                    // This check should always be true...
                    if (type == ExchangeService.SYNC_PING) {
                        int changeCount = ExchangeService.getStatusChangeCount(status);
                        if (changeCount > 0) {
                            errorMap.remove(serverId);
                        } else if (changeCount == 0) {
                            // This means that a ping reported changes in error; we keep a
                            // count of consecutive errors of this kind
                            String name = c.getString(Mailbox.CONTENT_DISPLAY_NAME_COLUMN);
                            Integer failures = errorMap.get(serverId);
                            if (failures == null) {
                                userLog("Last ping reported changes in error for: ", name);
                                errorMap.put(serverId, 1);
                            } else if (failures > MAX_PING_FAILURES) {
                                // We'll back off of push for this box
                                pushFallback(c.getLong(Mailbox.CONTENT_ID_COLUMN));
                                continue;
                            } else {
                                userLog("Last ping reported changes in error for: ", name);
                                errorMap.put(serverId, failures + 1);
                            }
                        }
                    }
                    */
                if (c.moveToFirst()) {
                    requestSyncForMailbox(mAmAccount,
                            Mailbox.getAuthority(c.getInt(Mailbox.CONTENT_TYPE_COLUMN)),
                            c.getLong(Mailbox.CONTENT_ID_COLUMN));
                }
            } finally {
                c.close();
            }
        }
    }

    /**
     * Issue a {@link ContentResolver#requestSync} to trigger a FolderSync for an account.
     */
    private void requestFolderSync() {
        requestSyncForMailbox(mAmAccount, EmailContent.AUTHORITY,
                Mailbox.SYNC_EXTRA_MAILBOX_ID_ACCOUNT_ONLY);
    }

    public static void requestPing(final android.accounts.Account amAccount) {
        requestSyncForMailbox(amAccount, EmailContent.AUTHORITY,
                Mailbox.SYNC_EXTRA_MAILBOX_ID_PUSH_ONLY);
    }

}
