package com.android.exchange.service;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.text.format.DateUtils;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.exchange.Eas;
import com.android.exchange.EasException;
import com.android.exchange.EasResponse;
import com.android.exchange.adapter.PingParser;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;

import org.apache.http.HttpStatus;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Performs an Exchange Ping, which is the command for receiving push notifications.
 * TODO: Rename this, now that it's no longer a subclass of EasSyncHandler.
 */
public class EasPingSyncHandler extends EasServerConnection {
    private final ContentResolver mContentResolver;
    private final Account mAccount;
    private final HostAuth mHostAuth;
    private final PingTask mPingTask;

    private class PingTask extends AsyncTask<Void, Void, Void> {
        private static final String AND_FREQUENCY_PING_PUSH_AND_NOT_ACCOUNT_MAILBOX = " AND " +
                MailboxColumns.SYNC_INTERVAL + " IN (" + Mailbox.CHECK_INTERVAL_PING +
                ',' + Mailbox.CHECK_INTERVAL_PUSH + ") AND " + MailboxColumns.TYPE + "!=\"" +
                Mailbox.TYPE_EAS_ACCOUNT_MAILBOX + '\"';
        private static final String WHERE_ACCOUNT_KEY_AND_SERVER_ID =
                MailboxColumns.ACCOUNT_KEY + "=? and " + MailboxColumns.SERVER_ID + "=?";

        private final EmailSyncAdapterService.SyncHandlerSychronizer mSyncHandlerMap;

        // TODO: The old code used to increase the heartbeat time after successful pings. Is there
        // a good reason to not just start at the high value? If there's a problem, it'll just fail
        // early anyway...
        private static final long PING_HEARTBEAT = 8 * DateUtils.MINUTE_IN_MILLIS;

        private PingTask(final EmailSyncAdapterService.SyncHandlerSychronizer syncHandlerMap) {
            mSyncHandlerMap = syncHandlerMap;
        }

        @Override
        protected Void doInBackground(Void... params) {
            // We keep pinging until we're interrupted, or reach some error condition that
            // prevents us from proceeding.
            boolean continuePing = true;
            while(continuePing) {
                final Cursor c = mContentResolver.query(Mailbox.CONTENT_URI,
                        Mailbox.CONTENT_PROJECTION, MailboxColumns.ACCOUNT_KEY + '=' +
                        mAccount.mId + AND_FREQUENCY_PING_PUSH_AND_NOT_ACCOUNT_MAILBOX, null, null);
                if (c == null) {
                    // TODO: Signal error: can't read mailbox data.
                    break;
                }
                final android.accounts.Account amAccount = new android.accounts.Account(
                        mAccount.mEmailAddress, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE);
                try {
                    Serializer s = null;
                    try {
                        while (c.moveToNext()) {
                            Mailbox mailbox = new Mailbox();
                            mailbox.restore(c);
                            s = handleOneMailbox(s, mailbox, amAccount);
                        }
                    } finally {
                        c.close();
                    }
                    if (s != null) {
                        // Note: this sequence of end()s corresponds to the start()s that occur
                        // in handleOneMailbox when the Serializer is first created.
                        // If either side changes, the other must be kept in sync.
                        s.end().end().done();
                        final EasResponse resp = sendHttpClientPost(mAccount, mHostAuth, "Ping",
                                s.toByteArray(), PING_HEARTBEAT);
                        try {
                            continuePing = handleResponse(resp, amAccount);
                        } finally {
                            resp.close();
                        }
                    } else {
                        // No mailboxes want to receive a push notification right now.
                        // TODO: Need to set up code that restarts the push when things change.
                        break;
                    }
                } catch (IOException e) {
                    // TODO: This assumes that all IOExceptions are interruptions. Other sorts may
                    // require additional handling.
                    break;
                }
            }

            mSyncHandlerMap.signalDone(mAccount.mId, false, true);
            return null;
        }

        /**
         * If mailbox is eligible for push, add it to the ping request, creating the
         * {@link Serializer} for the request if necessary.
         * @param mailbox The mailbox to check.
         * @param s The {@link Serializer} for this ping request, or null if it hasn't been created
         *     yet.
         * @param amAccount The {@link android.accounts.Account} for this request.
         * @return The {@link Serializer} for this ping request, or null if it hasn't been created
         *     yet.
         * @throws IOException
         */
        private Serializer handleOneMailbox(Serializer s, final Mailbox mailbox,
                final android.accounts.Account amAccount) throws IOException {
            // We can't push until the initial sync is done
            if (mailbox.mSyncKey != null && !mailbox.mSyncKey.equals("0")) {
                final String authority;
                final String folderClass;
                switch (mailbox.mType) {
                    case Mailbox.TYPE_CALENDAR:
                        authority = CalendarContract.AUTHORITY;
                        folderClass = "Calendar";
                        break;
                    case Mailbox.TYPE_CONTACTS:
                        authority = ContactsContract.AUTHORITY;
                        folderClass = "Contacts";
                        break;
                    default:
                        authority = EmailContent.AUTHORITY;
                        folderClass = "Email";
                        break;
                }
                if (ContentResolver.getSyncAutomatically(amAccount, authority)) {
                    if (s == null) {
                        // No serializer yet, so create and initialize it.
                        // Note that these start()s correspond to the end()s in doInBackground.
                        // If either side changes, the other must be kept in sync.
                        s = new Serializer();
                        s.start(Tags.PING_PING);
                        s.data(Tags.PING_HEARTBEAT_INTERVAL,
                                Long.toString(PING_HEARTBEAT/DateUtils.SECOND_IN_MILLIS));
                        s.start(Tags.PING_FOLDERS);
                    }
                    s.start(Tags.PING_FOLDER);
                    s.data(Tags.PING_ID, mailbox.mServerId);
                    s.data(Tags.PING_CLASS, folderClass);
                    s.end();
                }
            }
            return s;
        }

        /**
         * Parse the response to determine which mailboxes need sync, and request them.
         * @param resp The response to the Ping from the Exchange server.
         * @param amAccount The AccountManager Account object for this account.
         * @return Whether the ping should continue after this returns. For example, if we request
         *     a sync, we should stop pinging, but if the ping timed out, then we should repeat it.
         * @throws IOException
         */
        private boolean handleResponse(final EasResponse resp,
                final android.accounts.Account amAccount) throws IOException {
            final int code = resp.getStatus();
            if (code == HttpStatus.SC_OK) {
                if (resp.isEmpty()) {
                    // Act as if we have an IOException (backoff, etc.)
                    // TODO: Err... is this right?
                    //throw new IOException();
                }

                final PingParser pp = new PingParser(resp.getInputStream());
                final boolean parseResult;
                try {
                    parseResult = pp.parse();
                } catch (EasException e) {
                    // TODO: proper error handling
                    return false;
                }
                boolean syncRequested = false;
                if (parseResult) {
                    final ArrayList<String> pingChangeList = pp.getSyncList();
                    final String[] bindArguments = new String[2];
                    bindArguments[0] = Long.toString(mAccount.mId);
                    for (final String serverId : pingChangeList) {
                        bindArguments[1] = serverId;
                        // TODO: Rather than one query per ping mailbox, do it all in one?
                        final Cursor c = mContentResolver.query(Mailbox.CONTENT_URI,
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
                                // Request the sync for this mailbox.
                                // TODO: Refactor with code in EmailProvider.
                                final Bundle extras = new Bundle();
                                extras.putLong(Mailbox.SYNC_EXTRA_MAILBOX_ID,
                                        c.getLong(Mailbox.CONTENT_ID_COLUMN));
                                extras.putString(EmailServiceStatus.SYNC_EXTRAS_CALLBACK_URI,
                                        EmailContent.CONTENT_URI.toString());
                                extras.putString(EmailServiceStatus.SYNC_EXTRAS_CALLBACK_METHOD,
                                        "sync_status");
                                ContentResolver.requestSync(amAccount, EmailContent.AUTHORITY,
                                        extras);
                                syncRequested = true;
                            }
                        } finally {
                            c.close();
                        }
                    }
                }

                // TODO: Handle pp.getSyncStatus().
                /*
                // If our ping completed (status = 1), and wasn't forced and we're
                // not at the maximum, try increasing timeout by two minutes
                if (pingResult == PROTOCOL_PING_STATUS_BAD_PARAMETERS ||
                        pingResult == PROTOCOL_PING_STATUS_RETRY) {
                    // These errors appear to be server-related (I've seen a bad
                    // parameters result with known good parameters...)
                    // Act as if we have an IOException (backoff, etc.)
                    throw new IOException();
                }
                */
                return parseResult && !syncRequested;
            } else if (EasResponse.isAuthError(code)) {
                // TODO: signal this error.
            }
            return false;
        }

    }

    public EasPingSyncHandler(final Context context, final Account account,
            final EmailSyncAdapterService.SyncHandlerSychronizer syncHandlerMap) {
        super(context);
        mContentResolver = context.getContentResolver();
        mAccount = account;
        mHostAuth = HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv);
        mPingTask = new PingTask(syncHandlerMap);
        mPingTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    public synchronized void stop() {
        // TODO: Consider also interrupting mPingTask in the non-refresh case, or alter refresh
        // to restart the task. (Yes, this override currently exists purely for this comment.)
        super.stop();
    }
}
