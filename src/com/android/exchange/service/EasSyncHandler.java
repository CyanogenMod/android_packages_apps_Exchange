package com.android.exchange.service;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.net.TrafficStats;
import android.os.Bundle;
import android.text.format.DateUtils;

import com.android.emailcommon.TrafficFlags;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.exchange.CommandStatusException;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;
import com.android.exchange.adapter.AbstractSyncParser;
import com.android.exchange.adapter.Parser;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;
import com.android.mail.utils.LogUtils;

import org.apache.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;

/**
 * Base class for performing a sync from an Exchange server (i.e. retrieving collection data from
 * the server). A single sync request from the app will result in one or more Sync POST messages to
 * the server, but that's all encapsulated in this class as a single "sync".
 * Different collection types (e.g. mail, contacts, calendar) should subclass this class and
 * implement the various abstract functions. The majority of how the sync flow is common to all,
 * aside from a few details and the {@link Parser} used.
 */
public abstract class EasSyncHandler extends EasServerConnection {
    private static final String TAG = "EasSyncHandler";

    /** Window size for PIM (contact & calendar) sync options. */
    protected static final String PIM_WINDOW_SIZE = "4";

    // TODO: For each type of failure, provide info about why.
    private static final int SYNC_RESULT_FAILED = -1;
    private static final int SYNC_RESULT_DONE = 0;
    private static final int SYNC_RESULT_MORE_AVAILABLE = 1;

    /** Maximum number of Sync requests we'll send to the Exchange server in one sync attempt. */
    private static final int MAX_LOOPING_COUNT = 100;

    protected final ContentResolver mContentResolver;
    protected final Mailbox mMailbox;
    protected final Bundle mSyncExtras;
    protected final SyncResult mSyncResult;

    protected EasSyncHandler(final Context context, final ContentResolver contentResolver,
            final Account account, final Mailbox mailbox, final Bundle syncExtras,
            final SyncResult syncResult) {
        super(context, account, HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv));
        mContentResolver = contentResolver;
        mMailbox = mailbox;
        mSyncExtras = syncExtras;
        mSyncResult = syncResult;
    }

    /**
     * Create an instance of the appropriate subclass to handle sync for mailbox.
     * @param context
     * @param contentResolver
     * @param accountManagerAccount The {@link android.accounts.Account} for this sync.
     * @param account The {@link Account} for mailbox.
     * @param mailbox The {@link Mailbox} to sync.
     * @param syncExtras The extras for this sync, for consumption by {@link #performSync}.
     * @param syncResult The output results for this sync, which may be written to by
     *      {@link #performSync}.
     * @return An appropriate EasSyncHandler for this mailbox, or null if this sync can't be
     *      handled.
     */
    public static EasSyncHandler getEasSyncHandler(final Context context,
            final ContentResolver contentResolver,
            final android.accounts.Account accountManagerAccount,
            final Account account, final Mailbox mailbox,
            final Bundle syncExtras, final SyncResult syncResult) {
        if (account != null && mailbox != null) {
            switch (mailbox.mType) {
                case Mailbox.TYPE_INBOX:
                case Mailbox.TYPE_MAIL:
                    return new EasMailboxSyncHandler(context, contentResolver, account, mailbox,
                            syncExtras, syncResult);
                case Mailbox.TYPE_CONTACTS:
                    return new EasContactsSyncHandler(context, contentResolver,
                            accountManagerAccount, account, mailbox, syncExtras, syncResult);
                case Mailbox.TYPE_CALENDAR:
                    return new EasCalendarSyncHandler(context, contentResolver,
                            accountManagerAccount, account, mailbox, syncExtras, syncResult);
            }
        }
        // Unknown mailbox type.
        return null;
    }

    // Interface for subclasses to implement:
    // Subclasses must implement the abstract functions below to provide the information needed by
    // performSync.

    /**
     * Get the flag for traffic bookkeeping for this sync type.
     * @return The appropriate value from {@link TrafficFlags} for this sync.
     */
    protected abstract int getTrafficFlag();

    /**
     * Get the sync key for this mailbox.
     * @return The sync key for the object being synced. "0" means this is the first sync. If
     *      there is an error in getting the sync key, this function returns null.
     */
    protected abstract String getSyncKey();

    /**
     * Get the folder class name for this mailbox.
     * @return The string for this folder class, as defined by the Exchange spec.
     */
    // TODO: refactor this to be the same strings as EasPingSyncHandler#handleOneMailbox.
    protected abstract String getFolderClassName();

    /**
     * Return an {@link AbstractSyncParser} appropriate for this sync type and response.
     * @param is The {@link InputStream} for the {@link EasResponse} for this sync.
     * @return The {@link AbstractSyncParser} for this response.
     * @throws IOException
     */
    protected abstract AbstractSyncParser getParser(final InputStream is) throws IOException;

    /**
     * Add sync options to the {@link Serializer} for this sync, if it's the first sync on this
     * mailbox.
     * @param s The {@link Serializer} for this sync.
     * @throws IOException
     */
    protected abstract void setInitialSyncOptions(final Serializer s) throws IOException;

    /**
     * Add sync options to the {@link Serializer} for this sync, if it's not the first sync on this
     * mailbox.
     * @param s The {@link Serializer} for this sync.
     * @throws IOException
     */
    protected abstract void setNonInitialSyncOptions(final Serializer s) throws IOException;

    protected abstract void setUpsyncCommands(final Serializer s) throws IOException;

    // End of abstract functions.

    /**
     * Shared non-initial sync options for PIM (contacts & calendar) objects.
     * @param s The {@link Serializer} for this sync request.
     * @param filter The lookback to use, or null if no lookback is desired.
     * @throws IOException
     */
    protected void setPimSyncOptions(final Serializer s, final String filter) throws IOException {
        s.tag(Tags.SYNC_DELETES_AS_MOVES);
        s.tag(Tags.SYNC_GET_CHANGES);
        s.data(Tags.SYNC_WINDOW_SIZE, PIM_WINDOW_SIZE);
        s.start(Tags.SYNC_OPTIONS);
        // Set the filter (lookback), if provided
        if (filter != null) {
            s.data(Tags.SYNC_FILTER_TYPE, filter);
        }
        // Set the truncation amount and body type
        if (getProtocolVersion() >= Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
            s.start(Tags.BASE_BODY_PREFERENCE);
            // Plain text
            s.data(Tags.BASE_TYPE, Eas.BODY_PREFERENCE_TEXT);
            s.data(Tags.BASE_TRUNCATION_SIZE, Eas.EAS12_TRUNCATION_SIZE);
            s.end();
        } else {
            s.data(Tags.SYNC_TRUNCATION, Eas.EAS2_5_TRUNCATION_SIZE);
        }
        s.end();
    }

    /**
     * Create and populate the {@link Serializer} for this Sync POST to the Exchange server.
     * @param syncKey The sync key to use for this request.
     * @param initialSync Whether this sync is the first for this object.
     * @return The {@link Serializer} for to use for this request.
     * @throws IOException
     */
    private Serializer buildEasRequest(final String syncKey, final boolean initialSync)
            throws IOException {
        final String className = getFolderClassName();
        LogUtils.i(TAG, "Syncing account %d mailbox %d (class %s) with syncKey %s", mAccount.mId,
                mMailbox.mId, className, syncKey);

        final Serializer s = new Serializer();

        s.start(Tags.SYNC_SYNC);
        s.start(Tags.SYNC_COLLECTIONS);
        s.start(Tags.SYNC_COLLECTION);
        // The "Class" element is removed in EAS 12.1 and later versions
        if (getProtocolVersion() < Eas.SUPPORTED_PROTOCOL_EX2007_SP1_DOUBLE) {
            s.data(Tags.SYNC_CLASS, className);
        }
        s.data(Tags.SYNC_SYNC_KEY, syncKey);
        s.data(Tags.SYNC_COLLECTION_ID, mMailbox.mServerId);
        if (initialSync) {
            setInitialSyncOptions(s);
        } else {
            setNonInitialSyncOptions(s);
            // TODO: handle when previous iteration's upsync failed.
            setUpsyncCommands(s);
        }
        s.end().end().end().done();

        return s;
    }

    /**
     * Interpret a successful (HTTP code = 200) response from the Exchange server.
     * @param resp The {@link EasResponse} for the Sync message.
     * @return One of {@link #SYNC_RESULT_FAILED}, {@link #SYNC_RESULT_MORE_AVAILABLE}, or
     *      {@link #SYNC_RESULT_DONE} as appropriate for the server response.
     */
    private int parse(final EasResponse resp) {
        try {
            if (getParser(resp.getInputStream()).parse()) {
                return SYNC_RESULT_MORE_AVAILABLE;
            }
        } catch (final Parser.EmptyStreamException e) {
            // This indicates a compressed response which was empty, which is OK.
        } catch (final IOException e) {
            return SYNC_RESULT_FAILED;
        } catch (final CommandStatusException e) {
            return SYNC_RESULT_FAILED;
        }
        return SYNC_RESULT_DONE;
    }

    /**
     * Send one Sync POST to the Exchange server, and handle the response.
     * @return One of {@link #SYNC_RESULT_FAILED}, {@link #SYNC_RESULT_MORE_AVAILABLE}, or
     *      {@link #SYNC_RESULT_DONE} as appropriate for the server response.
     */
    private int performOneSync() {
        final String syncKey = getSyncKey();
        if (syncKey == null) {
            return SYNC_RESULT_FAILED;
        }
        final boolean initialSync = syncKey.equals("0");

        final EasResponse resp;
        try {
            final Serializer s = buildEasRequest(syncKey, initialSync);
            final long timeout = initialSync ? 120 * DateUtils.SECOND_IN_MILLIS : COMMAND_TIMEOUT;
            resp = sendHttpClientPost("Sync", s.toByteArray(), timeout);
        } catch (final IOException e) {
            return SYNC_RESULT_FAILED;
        }

        final int result;
        try {
            final int code = resp.getStatus();
            if (code == HttpStatus.SC_OK) {
                // A successful sync can have an empty response -- this indicates no change.
                // In the case of a compressed stream, resp will be non-empty, but parse() handles
                // that case.
                if (!resp.isEmpty()) {
                    result = parse(resp);
                } else {
                    result = SYNC_RESULT_DONE;
                }
            } else if (EasResponse.isProvisionError(code)) {
                return SYNC_RESULT_FAILED; // TODO: Handle SyncStatus.FAILURE_SECURITY;
            } else if (EasResponse.isAuthError(code)) {
                return SYNC_RESULT_FAILED; // TODO: Handle SyncStatus.FAILURE_LOGIN;
            } else {
                return SYNC_RESULT_FAILED; // TODO: Handle SyncStatus.FAILURE_OTHER;
            }
        } finally {
            resp.close();
        }

        if (result == SYNC_RESULT_DONE) {
            // TODO: target.cleanup() or equivalent
        }

        if (initialSync && result != SYNC_RESULT_FAILED) {
            // TODO: Handle Automatic Lookback
        }

        return result;
    }

    /**
     * Perform the sync, updating {@link #mSyncResult} as appropriate (which was passed in from
     * the system SyncManager and will be read by it on the way out).
     * This function can send multiple Sync messages to the Exchange server, up to
     * {@link #MAX_LOOPING_COUNT}, due to the server replying to a Sync request with MoreAvailable.
     * In the case of errors, this function should not attempt any retries, but rather should
     * set {@link #mSyncResult} to reflect the problem and let the system SyncManager handle
     * any it.
     */
    public final void performSync() {
        // Set up traffic stats bookkeeping.
        final int trafficFlags = TrafficFlags.getSyncFlags(mContext, mAccount);
        TrafficStats.setThreadStatsTag(trafficFlags | getTrafficFlag());

        // TODO: Properly handle UI status updates.
        //syncMailboxStatus(EmailServiceStatus.IN_PROGRESS, 0);
        int syncResult = SYNC_RESULT_MORE_AVAILABLE;
        int loopingCount = 0;
        while (syncResult == SYNC_RESULT_MORE_AVAILABLE && loopingCount < MAX_LOOPING_COUNT) {
            syncResult = performOneSync();
            // TODO: Clear pending request queue.
            ++loopingCount;
        }
        if (syncResult == SYNC_RESULT_MORE_AVAILABLE) {
            // TODO: Signal caller that it probably wants to sync again.
        }
    }

    // Communication with the application.

    // TODO: Consider bringing the EmailServiceStatus functions here?
    /**
     * Convenience wrapper to {@link EmailServiceStatus#syncMailboxStatus}.
     * @param statusCode
     * @param progress
     */
    protected void syncMailboxStatus(final int statusCode, final int progress) {
        EmailServiceStatus.syncMailboxStatus(mContentResolver, mSyncExtras, mMailbox.mId,
                statusCode, progress);
    }

    /**
     * Convenience wrapper to {@link EmailServiceStatus#sendMessageStatus}.
     * @param messageId
     * @param subject
     * @param statusCode
     * @param progress
     */
    protected void sendMessageStatus(final long messageId, final String subject,
            final int statusCode, final int progress) {
        EmailServiceStatus.sendMessageStatus(mContentResolver, mSyncExtras, messageId, subject,
                statusCode, progress);
    }

    /**
     * Convenience wrapper to {@link EmailServiceStatus#syncMailboxListStatus}.
     * @param statusCode
     * @param progress
     */
    protected void syncMailboxListStatus(final int statusCode, final int progress) {
        EmailServiceStatus.syncMailboxListStatus(mContentResolver, mSyncExtras, mAccount.mId,
                statusCode, progress);
    }
}
