package com.android.exchange.service;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.os.Bundle;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.exchange.EasResponse;

import org.apache.http.HttpEntity;

import java.io.IOException;

/**
 * Base class for performing a single sync action. It holds the state needed for all sync actions
 * (e.g. account and auth info, sync extras and results) and functions to communicate to with the
 * app UI.
 * Sublclasses must implement {@link #performSync}, but otherwise have no other requirements.
 */
public abstract class EasSyncHandler extends EasServerConnection {
    protected final ContentResolver mContentResolver;
    protected final Account mAccount;
    protected final Mailbox mMailbox;
    protected final Bundle mSyncExtras;
    protected final SyncResult mSyncResult;

    /**
     * Status values to indicate success or manner of failure when sending a single Message.
     * TODO: eliminate this enum, possibly in favor of {@link EmailServiceStatus} values.
     */
    public enum SyncStatus {
        // Message sent successfully.
        SUCCESS,
        // Sync failed due to an I/O error (file or network).
        FAILURE_IO,
        // Sync failed due to login problems.
        FAILURE_LOGIN,
        // Sync failed due to login problems.
        FAILURE_SECURITY,
        // Sync failed due to bad message data.
        FAILURE_MESSAGE,
        FAILURE_OTHER
    }

    protected EasSyncHandler(final Context context, final ContentResolver contentResolver,
            final Account account, final Mailbox mailbox, final Bundle syncExtras,
            final SyncResult syncResult) {
        super(context, HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv));
        mContentResolver = contentResolver;
        mAccount = account;
        mMailbox = mailbox;
        mSyncExtras = syncExtras;
        mSyncResult = syncResult;
    }

    /**
     * Create an instance of the appropriate subclass to handle sync for mailbox.
     * @param context
     * @param contentResolver
     * @param account The {@link Account} for mailbox.
     * @param mailbox The {@link Mailbox} to sync.
     * @param syncExtras The extras for this sync, for consumption by {@link #performSync}.
     * @param syncResult The output results for this sync, which may be written to by
     *      {@link #performSync}.
     * @return An appropriate EasSyncHandler for this mailbox, or null if this sync can't be
     *      handled.
     */
    public static EasSyncHandler getEasSyncHandler(final Context context,
            final ContentResolver contentResolver, final Account account, final Mailbox mailbox,
            final Bundle syncExtras, final SyncResult syncResult) {
        if (account != null && mailbox != null) {
            switch (mailbox.mType) {
                case Mailbox.TYPE_INBOX:
                case Mailbox.TYPE_MAIL:
                    return new EasMailboxSyncHandler(context, contentResolver, account, mailbox,
                            syncExtras, syncResult);
                case Mailbox.TYPE_OUTBOX:
                    return new EasOutboxSyncHandler(context, contentResolver, account, mailbox,
                            syncExtras, syncResult);
                case Mailbox.TYPE_EAS_ACCOUNT_MAILBOX:
                    return new EasAccountSyncHandler(context, contentResolver, account,
                            syncExtras, syncResult);
            }
        }
        // Could not handle this sync.
        return null;
    }

    /**
     * Perform the sync, updating {@link #mSyncResult} as appropriate (which was passed in from
     * the system SyncManager and will be read by it on the way out).
     * In the case of errors, this function should not attempt any retries, but rather should
     * set the {@link SyncResult} to reflect the problem and let the system SyncManager handle
     * any retries etc.
     * @return An exit status code.
     * TODO: Do we really need a return value, or should we just use the SyncResult for this?
     */
    public abstract SyncStatus performSync();

    // Convenience versions of EasServerConnection functions, using our member variables.

    protected String getProtocolVersion() {
        return getProtocolVersion(mAccount);
    }

    protected EasResponse sendHttpClientPost(final String cmd, final HttpEntity entity,
            final long timeout) throws IOException {
        return sendHttpClientPost(mAccount, cmd, entity, timeout);
    }

    protected EasResponse sendHttpClientPost(final String cmd, final byte[] bytes)
            throws IOException {
        return sendHttpClientPost(mAccount, cmd, bytes, COMMAND_TIMEOUT);
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
