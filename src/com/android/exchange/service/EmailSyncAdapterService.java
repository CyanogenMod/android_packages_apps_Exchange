/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.exchange.service;

import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;

import com.android.emailcommon.Api;
import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.SearchParams;
import com.android.emailcommon.utility.Utility;
import com.android.exchange.Eas;
import com.android.exchange.adapter.PingParser;
import com.android.exchange.adapter.Search;
import com.android.mail.providers.UIProvider.AccountCapabilities;
import com.android.mail.utils.LogUtils;

import java.util.HashMap;

/**
 * Service for communicating with Exchange servers. There are three main parts of this class:
 * TODO: Flesh out these comments.
 * 1) An {@link AbstractThreadedSyncAdapter} to handle actually performing syncs.
 * 2) Bookkeeping for running Ping requests, which handles push notifications.
 * 3) An {@link IEmailService} Stub to handle RPC from the UI.
 */
public class EmailSyncAdapterService extends AbstractSyncAdapterService {

    private static final String TAG = "EASEmailSyncAdaptSvc";

    /**
     * If sync extras do not include a mailbox id, then we want to perform a full sync.
     */
    private static final long FULL_ACCOUNT_SYNC = Mailbox.NO_MAILBOX;

    /** Projection used for getting email address for an account. */
    private static final String[] ACCOUNT_EMAIL_PROJECTION = { AccountColumns.EMAIL_ADDRESS };

    /**
     * Bookkeeping for handling synchronization between pings and syncs.
     * "Ping" refers to a hanging POST or GET that is used to receive push notifications. Ping is
     * the term for the Exchange command, but this code should be generic enough to be easily
     * extended to IMAP.
     * "Sync" refers to an actual sync command to either fetch mail state, account state, or send
     * mail (send is implemented as "sync the outbox").
     * TODO: Outbox sync probably need not stop a ping in progress.
     * Basic rules of how these interact (note that all rules are per account):
     * - Only one ping or sync may run at a time.
     * - Due to how {@link AbstractThreadedSyncAdapter} works, sync requests will not occur while
     *   a sync is in progress.
     * - On the other hand, ping requests may come in while handling a ping.
     * - "Ping request" is shorthand for "a request to change our ping parameters", which includes
     *   a request to stop receiving push notifications.
     * - If neither a ping nor a sync is running, then a request for either will run it.
     * - If a sync is running, new ping requests block until the sync completes.
     * - If a ping is running, a new sync request stops the ping and creates a pending ping
     *   (which blocks until the sync completes).
     * - If a ping is running, a new ping request stops the ping and either starts a new one or
     *   does nothing, as appopriate (since a ping request can be to stop pushing).
     * - As an optimization, while a ping request is waiting to run, subsequent ping requests are
     *   ignored (the pending ping will pick up the latest ping parameters at the time it runs).
     */
    public class SyncHandlerSynchronizer {
        /**
         * Map of account id -> ping handler.
         * For a given account id, there are three possible states:
         * 1) If no ping or sync is currently running, there is no entry in the map for the account.
         * 2) If a ping is running, there is an entry with the appropriate ping handler.
         * 3) If there is a sync running, there is an entry with null as the value.
         * We cannot have more than one ping or sync running at a time.
         */
        private final HashMap<Long, EasPingSyncHandler> mPingHandlers =
                new HashMap<Long, EasPingSyncHandler>();

        /**
         * Wait until neither a sync nor a ping is running on this account, and then return.
         * If there's a ping running, actively stop it. (For syncs, we have to just wait.)
         * @param accountId The account we want to wait for.
         */
        private synchronized void waitUntilNoActivity(final long accountId) {
            while (mPingHandlers.containsKey(accountId)) {
                final EasPingSyncHandler pingHandler = mPingHandlers.get(accountId);
                if (pingHandler != null) {
                    pingHandler.stop(EasServerConnection.STOPPED_REASON_ABORT);
                }
                try {
                    wait();
                } catch (final InterruptedException e) {
                    // TODO: When would this happen, and how should I handle it?
                }
            }
        }

        /**
         * Use this to see if we're currently syncing, as opposed to pinging or doing nothing.
         * @param accountId The account to check.
         * @return Whether that account is currently running a sync.
         */
        private synchronized boolean isRunningSync(final long accountId) {
            return (mPingHandlers.containsKey(accountId) && mPingHandlers.get(accountId) == null);
        }

        /**
         * If there are no running pings, stop the service.
         */
        private void stopServiceIfNoPings() {
            for (final EasPingSyncHandler pingHandler : mPingHandlers.values()) {
                if (pingHandler != null) {
                    return;
                }
            }
            EmailSyncAdapterService.this.stopSelf();
        }

        /**
         * Called prior to starting a sync to update our bookkeeping. We don't actually run the sync
         * here; the caller must do that.
         * @param accountId The account on which we are running a sync.
         */
        public synchronized void startSync(final long accountId) {
            waitUntilNoActivity(accountId);
            mPingHandlers.put(accountId, null);
        }

        /**
         * Starts or restarts a ping for an account, if the current account state indicates that it
         * wants to push.
         * @param account The account whose ping is being modified.
         */
        public synchronized void modifyPing(final Account account) {
            // If a sync is currently running, it will start a ping when it's done, so there's no
            // need to do anything right now.
            if (isRunningSync(account.mId)) {
                return;
            }

            // If a ping is currently running, tell it to restart to pick up new params.
            final EasPingSyncHandler pingSyncHandler = mPingHandlers.get(account.mId);
            if (pingSyncHandler != null) {
                pingSyncHandler.stop(EasServerConnection.STOPPED_REASON_RESTART);
                return;
            }

            // If we're here, then there's neither a sync nor a ping running. Start a new ping.
            final EmailSyncAdapterService service = EmailSyncAdapterService.this;
            if (account.mSyncInterval == Account.CHECK_INTERVAL_PUSH) {
                // This account needs to ping.
                // Note: unlike startSync, we CANNOT allow the caller to do the actual work.
                // If we return before the ping starts, there's a race condition where another
                // ping or sync might start first. It only works for startSync because sync is
                // higher priority than ping (i.e. a ping can't start while a sync is pending)
                // and only one sync can run at a time.
                final EasPingSyncHandler pingHandler =
                        new EasPingSyncHandler(service, account, this);
                mPingHandlers.put(account.mId, pingHandler);
                pingHandler.startPing();
                // Whenever we have a running ping, make sure this service stays running.
                service.startService(new Intent(service, EmailSyncAdapterService.class));
            }
        }

        /**
         * Updates the synchronization bookkeeping when a sync is done.
         * @param account The account whose sync just finished.
         */
        public synchronized void syncComplete(final Account account) {
            mPingHandlers.remove(account.mId);
            // Syncs can interrupt pings, so we should check if we need to start one now.
            modifyPing(account);
            stopServiceIfNoPings();
            notifyAll();
        }

        /**
         * Updates the synchronization bookkeeping when a ping is done. Also requests a ping-only
         * sync if necessary.
         * @param amAccount The {@link android.accounts.Account} for this account.
         * @param account The account whose ping just finished.
         * @param pingStatus The status value from {@link PingParser} for the last ping performed.
         *                   This cannot be one of the values that results in another ping, so this
         *                   function only needs to handle the terminal statuses.
         */
        public synchronized void pingComplete(final android.accounts.Account amAccount,
                final Account account, final int pingStatus) {
            mPingHandlers.remove(account.mId);

            // TODO: if (pingStatus == PingParser.STATUS_FAILED), notify UI.
            // TODO: if (pingStatus == PingParser.STATUS_REQUEST_TOO_MANY_FOLDERS), notify UI.

            if (pingStatus == PingParser.STATUS_NETWORK_EXCEPTION) {
                // Request a new ping through the SyncManager. This will do the right thing if the
                // exception was due to loss of network connectivity, etc. (i.e. it will wait for
                // network to restore and then request it).
                EasPingSyncHandler.requestPing(amAccount);
            } else if (pingStatus != PingParser.STATUS_INTERRUPTED &&
                    pingStatus != PingParser.STATUS_CHANGES_FOUND &&
                    pingStatus != PingParser.STATUS_FOLDER_REFRESH_NEEDED) {
                stopServiceIfNoPings();
            }

            // TODO: It might be the case that only STATUS_CHANGES_FOUND and
            // STATUS_FOLDER_REFRESH_NEEDED need to notifyAll(). Think this through.
            notifyAll();
        }

    }
    private final SyncHandlerSynchronizer mSyncHandlerMap = new SyncHandlerSynchronizer();

    /**
     * The binder for IEmailService.
     */
    private final IEmailService.Stub mBinder = new IEmailService.Stub() {

        private String getEmailAddressForAccount(final long accountId) {
            final String emailAddress = Utility.getFirstRowString(EmailSyncAdapterService.this,
                    Account.CONTENT_URI, ACCOUNT_EMAIL_PROJECTION, Account.ID_SELECTION,
                    new String[] {Long.toString(accountId)}, null, 0);
            if (emailAddress == null) {
                LogUtils.e(TAG, "Could not find email address for account %d", accountId);
            }
            return emailAddress;
        }

        @Override
        public Bundle validate(final HostAuth hostAuth) {
            LogUtils.d(TAG, "IEmailService.validate");
            return new EasAccountValidator(EmailSyncAdapterService.this, hostAuth).validate();
        }

        @Override
        public Bundle autoDiscover(final String username, final String password) {
            LogUtils.d(TAG, "IEmailService.autoDiscover");
            return new EasAutoDiscover(EmailSyncAdapterService.this, username, password)
                    .doAutodiscover();
        }

        @Override
        public void updateFolderList(final long accountId) {
            LogUtils.d(TAG, "IEmailService.updateFolderList: %d", accountId);
            final String emailAddress = getEmailAddressForAccount(accountId);
            if (emailAddress != null) {
                ContentResolver.requestSync(new android.accounts.Account(
                        emailAddress, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE),
                        EmailContent.AUTHORITY, new Bundle());
            }
        }

        @Override
        public void setCallback(final IEmailServiceCallback cb) {
            // TODO: Determine if this is ever called in practice.
            //mCallbackList.register(cb);
        }

        @Override
        public void setLogging(final int flags) {
            // TODO: fix this?
            // Protocol logging
            Eas.setUserDebug(flags);
            // Sync logging
            //setUserDebug(flags);
        }

        @Override
        public void loadAttachment(final IEmailServiceCallback callback, final long attachmentId,
                final boolean background) {
            LogUtils.d(TAG, "IEmailService.loadAttachment: %d", attachmentId);
            // TODO: Prevent this from happening in parallel with a sync?
            EasAttachmentLoader.loadAttachment(EmailSyncAdapterService.this, attachmentId,
                    callback);
        }

        @Override
        public void sendMeetingResponse(final long messageId, final int response) {
            LogUtils.d(TAG, "IEmailService.sendMeetingResponse");
            // TODO: Implement.
            //sendMessageRequest(new MeetingResponseRequest(messageId, response));
        }

        /**
         * Delete PIM (calendar, contacts) data for the specified account
         *
         * @param emailAddress the email address for the account whose data should be deleted
         */
        @Override
        public void deleteAccountPIMData(final String emailAddress) {
            LogUtils.d(TAG, "IEmailService.deleteAccountPIMData");
            if (emailAddress != null) {
                final Context context = EmailSyncAdapterService.this;
                EasContactsSyncHandler.wipeAccountFromContentProvider(context, emailAddress);
                EasCalendarSyncHandler.wipeAccountFromContentProvider(context, emailAddress);
            }
            // TODO: Run account reconciler?
        }

        @Override
        public int searchMessages(final long accountId, final SearchParams searchParams,
                final long destMailboxId) {
            LogUtils.d(TAG, "IEmailService.searchMessages");
            return Search.searchMessages(EmailSyncAdapterService.this, accountId, searchParams,
                    destMailboxId);

        }

        @Override
        public void sendMail(final long accountId) {}

        @Override
        public int getCapabilities(final Account acct) {
            String easVersion = acct.mProtocolVersion;
            Double easVersionDouble = 2.5D;
            if (easVersion != null) {
                try {
                    easVersionDouble = Double.parseDouble(easVersion);
                } catch (NumberFormatException e) {
                    // Stick with 2.5
                }
            }
            if (easVersionDouble >= 12.0D) {
                return AccountCapabilities.SYNCABLE_FOLDERS |
                        AccountCapabilities.SERVER_SEARCH |
                        AccountCapabilities.FOLDER_SERVER_SEARCH |
                        AccountCapabilities.SANITIZED_HTML |
                        AccountCapabilities.SMART_REPLY |
                        AccountCapabilities.SERVER_SEARCH |
                        AccountCapabilities.UNDO;
            } else {
                return AccountCapabilities.SYNCABLE_FOLDERS |
                        AccountCapabilities.SANITIZED_HTML |
                        AccountCapabilities.SMART_REPLY |
                        AccountCapabilities.UNDO;
            }
        }

        @Override
        public void serviceUpdated(final String emailAddress) {
            // Not required for EAS
        }

        // All IEmailService messages below are UNCALLED in Email.
        // TODO: Remove.
        @Deprecated
        @Override
        public int getApiLevel() {
            return Api.LEVEL;
        }

        @Deprecated
        @Override
        public void startSync(long mailboxId, boolean userRequest, int deltaMessageCount) {}

        @Deprecated
        @Override
        public void stopSync(long mailboxId) {}

        @Deprecated
        @Override
        public void loadMore(long messageId) {}

        @Deprecated
        @Override
        public boolean createFolder(long accountId, String name) {
            return false;
        }

        @Deprecated
        @Override
        public boolean deleteFolder(long accountId, String name) {
            return false;
        }

        @Deprecated
        @Override
        public boolean renameFolder(long accountId, String oldName, String newName) {
            return false;
        }

        @Deprecated
        @Override
        public void hostChanged(long accountId) {}
    };

    public EmailSyncAdapterService() {
        super();
    }

    /**
     * {@link AsyncTask} for restarting pings for all accounts that need it.
     */
    private static class RestartPingsTask extends AsyncTask<Void, Void, Void> {
        private static final String PUSH_ACCOUNTS_SELECTION =
                AccountColumns.SYNC_INTERVAL + "=" + Integer.toString(Account.CHECK_INTERVAL_PUSH);

        private final ContentResolver mContentResolver;
        private final SyncHandlerSynchronizer mSyncHandlerMap;

        public RestartPingsTask(final ContentResolver contentResolver,
                final SyncHandlerSynchronizer syncHandlerMap) {
            mContentResolver = contentResolver;
            mSyncHandlerMap = syncHandlerMap;
        }

        @Override
        protected Void doInBackground(Void... params) {
            final Cursor c = mContentResolver.query(Account.CONTENT_URI,
                    Account.CONTENT_PROJECTION, PUSH_ACCOUNTS_SELECTION, null, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        final Account account = new Account();
                        account.restore(c);
                        mSyncHandlerMap.modifyPing(account);
                    }
                } finally {
                    c.close();
                }
            }
            return null;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Restart push for all accounts that need it.
        new RestartPingsTask(getContentResolver(), mSyncHandlerMap).executeOnExecutor(
                AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (intent.getAction().equals(Eas.EXCHANGE_SERVICE_INTENT_ACTION)) {
            return mBinder;
        }
        return super.onBind(intent);
    }

    @Override
    protected AbstractThreadedSyncAdapter newSyncAdapter() {
        return new SyncAdapterImpl(this);
    }

    // TODO: Handle cancelSync() appropriately.
    private class SyncAdapterImpl extends AbstractThreadedSyncAdapter {
        public SyncAdapterImpl(Context context) {
            super(context, true /* autoInitialize */);
        }

        @Override
        public void onPerformSync(final android.accounts.Account acct, final Bundle extras,
                final String authority, final ContentProviderClient provider,
                final SyncResult syncResult) {
            LogUtils.i(TAG, "performSync: extras = %s", extras.toString());
            TempDirectory.setTempDirectory(EmailSyncAdapterService.this);

            // TODO: Perform any connectivity checks, bail early if we don't have proper network
            // for this sync operation.

            final Context context = getContext();
            final ContentResolver cr = context.getContentResolver();

            // Get the EmailContent Account
            final Account account;
            final Cursor accountCursor = cr.query(Account.CONTENT_URI, Account.CONTENT_PROJECTION,
                    AccountColumns.EMAIL_ADDRESS + "=?", new String[] {acct.name}, null);
            try {
                if (!accountCursor.moveToFirst()) {
                    // Could not load account.
                    // TODO: improve error handling.
                    return;
                }
                account = new Account();
                account.restore(accountCursor);
            } finally {
                accountCursor.close();
            }
            // Get the mailbox that we want to sync.
            // There are four possibilities for Mailbox.SYNC_EXTRA_MAILBOX_ID:
            // 1) Mailbox.SYNC_EXTRA_MAILBOX_ID_PUSH_ONLY: Restart push if appropriate.
            // 2) Mailbox.SYNC_EXTRA_MAILBOX_ID_ACCOUNT_ONLY: Sync only the account data.
            // 3) Not present: Perform a full account sync.
            // 4) Non-negative value: It's an actual mailbox id, sync that mailbox only.
            final long mailboxId = extras.getLong(Mailbox.SYNC_EXTRA_MAILBOX_ID, FULL_ACCOUNT_SYNC);

            // If we're just twiddling the push, we do the lightweight thing and just bail.
            if (mailboxId == Mailbox.SYNC_EXTRA_MAILBOX_ID_PUSH_ONLY) {
                mSyncHandlerMap.modifyPing(account);
                return;
            }

            // Do the bookkeeping for starting a sync, including stopping a ping if necessary.
            mSyncHandlerMap.startSync(account.mId);

            // TODO: Should we refresh the account here? It may have changed while waiting for any
            // pings to stop. It may not matter since the things that may have been twiddled might
            // not affect syncing.

            if (mailboxId == FULL_ACCOUNT_SYNC ||
                    mailboxId == Mailbox.SYNC_EXTRA_MAILBOX_ID_ACCOUNT_ONLY) {
                final EasAccountSyncHandler accountSyncHandler =
                        new EasAccountSyncHandler(context, account);
                accountSyncHandler.performSync();

                if (mailboxId == FULL_ACCOUNT_SYNC) {
                    // Full account sync includes all mailboxes that participate in system sync.
                    final Cursor c = Mailbox.getMailboxIdsForSync(cr, account.mId);
                    if (c != null) {
                        try {
                            while (c.moveToNext()) {
                                syncMailbox(context, cr, acct, account, c.getLong(0), extras,
                                        syncResult);
                            }
                        } finally {
                            c.close();
                        }
                    }
                }
            } else {
                // Sync the mailbox that was explicitly requested.
                if (!syncMailbox(context, cr, acct, account, mailboxId, extras, syncResult)) {
                    // We can't sync this mailbox, so just send the expected UI callbacks.
                    EmailServiceStatus.syncMailboxStatus(cr, extras, mailboxId,
                            EmailServiceStatus.IN_PROGRESS, 0);
                    EmailServiceStatus.syncMailboxStatus(cr, extras, mailboxId,
                            EmailServiceStatus.SUCCESS, 0);
                }
            }

            // Clean up the bookkeeping, including restarting ping if necessary.
            mSyncHandlerMap.syncComplete(account);

            // TODO: It may make sense to have common error handling here. Two possible mechanisms:
            // 1) performSync return value can signal some useful info.
            // 2) syncResult can contain useful info.
        }

        private boolean syncMailbox(final Context context, final ContentResolver cr,
                final android.accounts.Account acct, final Account account, final long mailboxId,
                final Bundle extras, final SyncResult syncResult) {
            final Mailbox mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
            if (mailbox == null) {
                return false;
            }

            if (mailbox.mType == Mailbox.TYPE_OUTBOX) {
                final EasOutboxSyncHandler outboxSyncHandler =
                        new EasOutboxSyncHandler(context, account, mailbox);
                outboxSyncHandler.performSync();
            } else {
                final EasSyncHandler syncHandler = EasSyncHandler.getEasSyncHandler(context, cr,
                        acct, account, mailbox, extras, syncResult);
                if (syncHandler == null) {
                    return false;
                }
                syncHandler.performSync();
            }
            return true;
        }
    }
}
