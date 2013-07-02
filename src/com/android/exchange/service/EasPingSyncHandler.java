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
import com.android.emailcommon.provider.Mailbox;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;
import com.android.exchange.adapter.PingParser;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;
import com.android.mail.utils.LogUtils;

import org.apache.http.HttpStatus;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Performs an Exchange Ping, which is the command for receiving push notifications.
 * TODO: Rename this, now that it's no longer a subclass of EasSyncHandler.
 */
public class EasPingSyncHandler extends EasServerConnection {
    private static final String TAG = "EasPingSyncHandler";

    private final ContentResolver mContentResolver;
    private final PingTask mPingTask;

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

    /**
     * An {@link AsyncTask} that actually does the work of pinging.
     */
    private class PingTask extends AsyncTask<Void, Void, Void> {
        /** Selection clause when querying for a specific mailbox. */
        private static final String WHERE_ACCOUNT_KEY_AND_SERVER_ID =
                MailboxColumns.ACCOUNT_KEY + "=? and " + MailboxColumns.SERVER_ID + "=?";

        private final EmailSyncAdapterService.SyncHandlerSynchronizer mSyncHandlerMap;

        private PingTask(final EmailSyncAdapterService.SyncHandlerSynchronizer syncHandlerMap) {
            mSyncHandlerMap = syncHandlerMap;
        }

        @Override
        protected Void doInBackground(Void... params) {
            final android.accounts.Account amAccount = new android.accounts.Account(
                    mAccount.mEmailAddress, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE);
            // We keep pinging until we're interrupted, or reach some error condition that
            // prevents us from proceeding.
            int pingStatus;
            do {
                // Get the mailboxes that need push notifications.
                final Cursor c = Mailbox.getMailboxesForPush(mContentResolver, mAccount.mId);
                if (c == null) {
                    pingStatus = PingParser.STATUS_FAILED;
                    continue;
                }
                // Set up the request.
                Serializer s = null;
                try {
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
                    }
                } catch (final IOException e) {
                    LogUtils.e(TAG, "IOException while building ping request for account %d: %s",
                            mAccount.mId, e.toString());
                    pingStatus = PingParser.STATUS_FAILED;
                    continue;
                }

                if (s == null) {
                    // No mailboxes want to receive a push notification right now.
                    // TODO: Need to set up code that restarts the push when things change.
                    pingStatus = PingParser.STATUS_NO_FOLDERS;
                    continue;
                }

                final EasResponse resp;
                try {
                    resp = sendHttpClientPost("Ping", s.toByteArray(), POST_TIMEOUT);
                } catch (final IOException e) {
                    LogUtils.i(TAG, "IOException during ping: %s", e.getMessage());
                    switch (getStoppedReason()) {
                        case STOPPED_REASON_NONE:
                            // The POST stopped for a reason other than an explicit stop request.
                            pingStatus = PingParser.STATUS_NETWORK_EXCEPTION;
                            break;
                        case STOPPED_REASON_ABORT:
                            // This ping was stopped by a new sync request.
                            pingStatus = PingParser.STATUS_INTERRUPTED;
                            break;
                        case STOPPED_REASON_RESTART:
                            // This ping was stopped in order to reload the push parameters.
                            mAccount.refresh(mContext);
                            if (mAccount.mSyncInterval == Account.CHECK_INTERVAL_PUSH) {
                                // We still want to push. Treat this as if we had timed out, so
                                // we'll loop again.
                                pingStatus = PingParser.STATUS_EXPIRED;
                            } else {
                                // No longer want to push. Treat this as if there were no folders
                                // found that want push.
                                pingStatus = PingParser.STATUS_NO_FOLDERS;
                            }
                            break;
                        default:
                            // This shouldn't be possible, but treat it the same as
                            // STOPPED_REASON_NONE.
                            LogUtils.e(TAG, "Account %d got bad stop reason %d", mAccount.mId,
                                    getStoppedReason());
                            pingStatus = PingParser.STATUS_NETWORK_EXCEPTION;
                            break;
                    }
                    continue;
                }

                try {
                    pingStatus = handleResponse(resp, amAccount);
                } finally {
                    resp.close();
                }
            } while (PingParser.shouldPingAgain(pingStatus));

            mSyncHandlerMap.pingComplete(amAccount, mAccount, pingStatus);
            return null;
        }

        /**
         * Gets the correct authority for a mailbox (PIM collections use different authorities).
         * @param mailboxType The type of the mailbox we're interested in, from {@link Mailbox}.
         * @return The authority for the mailbox we're interested in.
         */
        private String getAuthority(final int mailboxType) {
            switch (mailboxType) {
                case Mailbox.TYPE_CALENDAR:
                    return CalendarContract.AUTHORITY;
                case Mailbox.TYPE_CONTACTS:
                    return ContactsContract.AUTHORITY;
                default:
                    return EmailContent.AUTHORITY;
            }
        }

        /**
         * Gets the Exchange folder class for a mailbox type (PIM collections have different values
         * from email), needed when forming the request.
         * @param mailboxType The type of the mailbox we're interested in, from {@link Mailbox}.
         * @return The folder class for the mailbox we're interested in.
         */
        private String getFolderClass(final int mailboxType) {
            switch (mailboxType) {
                case Mailbox.TYPE_CALENDAR:
                    return "Calendar";
                case Mailbox.TYPE_CONTACTS:
                    return "Contacts";
                default:
                    return "Email";
            }
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
                if (ContentResolver.getSyncAutomatically(amAccount, getAuthority(mailbox.mType))) {
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
                    s.data(Tags.PING_CLASS, getFolderClass(mailbox.mType));
                    s.end();
                }
            }
            return s;
        }

        /**
         * Make the appropriate calls to {@link ContentResolver#requestSync} indicated by the
         * current ping response.
         * @param amAccount The {@link android.accounts.Account} that we pinged.
         * @param syncList The list of folders that need to be synced.
         */
        private void requestSyncForSyncList(final android.accounts.Account amAccount,
                final ArrayList<String> syncList) {
            final String[] bindArguments = new String[2];
            bindArguments[0] = Long.toString(mAccount.mId);
            for (final String serverId : syncList) {
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
                        requestSyncForMailbox(amAccount,
                                getAuthority(c.getInt(Mailbox.CONTENT_TYPE_COLUMN)),
                                c.getLong(Mailbox.CONTENT_ID_COLUMN));
                    }
                } finally {
                    c.close();
                }
            }
        }

        /**
         * Parse the response and take the appropriate action.
         * @param resp The response to the Ping from the Exchange server.
         * @param amAccount The AccountManager Account object for this account.
         * @return The status value from the {@link PingParser}, or (in case of other failures)
         *         {@link PingParser#STATUS_FAILED}.
         */
        private int handleResponse(final EasResponse resp,
                final android.accounts.Account amAccount) {
            final int code = resp.getStatus();

            // Handle error cases.
            if (EasResponse.isAuthError(code)) {
                // TODO: signal this error more precisely.
                LogUtils.i(TAG, "Auth Error for account %d", mAccount.mId);
                return PingParser.STATUS_FAILED;
            }
            if (code != HttpStatus.SC_OK || resp.isEmpty()) {
                LogUtils.e(TAG, "Bad response (%d) for account %d", code, mAccount.mId);
                return PingParser.STATUS_FAILED;
            }

            // Handle a valid response.
            final PingParser pp;

            try {
                pp = new PingParser(resp.getInputStream());
            } catch (final IOException e) {
                LogUtils.e(TAG, "IOException creating PingParser: %s", e.getMessage());
                return PingParser.STATUS_FAILED;
            }

            pp.parse();
            final int pingStatus = pp.getPingStatus();

            // Take the appropriate action for this response.
            // Many of the responses require no explicit action here, they just influence
            // our re-ping behavior, which is handled by the caller.
            switch (pingStatus) {
                case PingParser.STATUS_FAILED:
                    LogUtils.e(TAG, "Ping failed for account %d", mAccount.mId);
                    break;
                case PingParser.STATUS_EXPIRED:
                    LogUtils.i(TAG, "Ping expired for account %d", mAccount.mId);
                    break;
                case PingParser.STATUS_CHANGES_FOUND:
                    LogUtils.i(TAG, "Ping found changed folders for account %d", mAccount.mId);
                    requestSyncForSyncList(amAccount, pp.getSyncList());
                    break;
                case PingParser.STATUS_REQUEST_INCOMPLETE:
                case PingParser.STATUS_REQUEST_MALFORMED:
                    // These two cases indicate that the ping request was somehow bad.
                    // TODO: It's insanity to re-ping with the same data and expect a different
                    // result. Improve this if possible.
                    LogUtils.e(TAG, "Bad ping request for account %d", mAccount.mId);
                    break;
                case PingParser.STATUS_REQUEST_HEARTBEAT_OUT_OF_BOUNDS:
                    LogUtils.i(TAG, "Heartbeat out of bounds for account %d", mAccount.mId);
                    // TODO: Implement auto heartbeat adjustments.
                    break;
                case PingParser.STATUS_REQUEST_TOO_MANY_FOLDERS:
                    LogUtils.i(TAG, "Too many folders for account %d", mAccount.mId);
                    break;
                case PingParser.STATUS_FOLDER_REFRESH_NEEDED:
                    LogUtils.i(TAG, "FolderSync needed for account %d", mAccount.mId);
                    requestFolderSync(amAccount);
                    break;
                case PingParser.STATUS_SERVER_ERROR:
                    LogUtils.i(TAG, "Server error for account %d", mAccount.mId);
                    break;
                default:
                    LogUtils.e(TAG, "Unknown ping status %d for account %d", pingStatus,
                            mAccount.mId);
            }

            return pingStatus;
        }
    }

    /**
     * Issue a {@link ContentResolver#requestSync} for a specific mailbox.
     * @param amAccount The {@link android.accounts.Account} for the account we're pinging.
     * @param authority The authority for the mailbox that needs to sync.
     * @param mailboxId The id of the mailbox that needs to sync.
     */
    private static void requestSyncForMailbox(final android.accounts.Account amAccount,
            final String authority, final long mailboxId) {
        final Bundle extras = new Bundle(1);
        extras.putLong(Mailbox.SYNC_EXTRA_MAILBOX_ID, mailboxId);
        ContentResolver.requestSync(amAccount, authority, extras);
    }

    /**
     * Issue a {@link ContentResolver#requestSync} to trigger a FolderSync for an account.
     * @param amAccount The {@link android.accounts.Account} for the account that needs to sync.
     */
    private static void requestFolderSync(final android.accounts.Account amAccount) {
        requestSyncForMailbox(amAccount, EmailContent.AUTHORITY,
                Mailbox.SYNC_EXTRA_MAILBOX_ID_ACCOUNT_ONLY);
    }

    public static void requestPing(final android.accounts.Account amAccount) {
        requestSyncForMailbox(amAccount, EmailContent.AUTHORITY,
                Mailbox.SYNC_EXTRA_MAILBOX_ID_PUSH_ONLY);
    }

    public EasPingSyncHandler(final Context context, final Account account,
            final EmailSyncAdapterService.SyncHandlerSynchronizer syncHandlerMap) {
        super(context, account);
        mContentResolver = context.getContentResolver();
        mPingTask = new PingTask(syncHandlerMap);
    }

    /**
     * Start pinging as an {@link AsyncTask}.
     */
    public void startPing() {
        mPingTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    public synchronized boolean stop(final int reason) {
        // TODO: Consider also interrupting mPingTask in the non-refresh case.
        // (Yes, this override currently exists purely for this comment.)
        return super.stop(reason);
    }
}
