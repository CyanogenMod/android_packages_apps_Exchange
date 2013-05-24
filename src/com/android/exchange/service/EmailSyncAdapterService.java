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
import com.android.mail.providers.UIProvider.AccountCapabilities;

import java.util.HashMap;
import java.util.HashSet;

import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.database.Cursor;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

/**
 * Service for communicating with Exchange servers. There are three main parts of this class:
 * TODO: Flesh out these comments.
 * 1) An {@link AbstractThreadedSyncAdapter} to handle actually performing syncs.
 * 2) Bookkeeping for running Ping requests, which handles push notifications.
 * 3) An {@link IEmailService} Stub to handle RPC from the UI.
 */
public class EmailSyncAdapterService extends AbstractSyncAdapterService {

    private static final String TAG = "EAS EmailSyncAdapterService";

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
         * Set of all accounts that are in the middle of processing a ping modification. This is
         * used to ignore duplicate modification requests.
         */
        private final HashSet<Long> mPendingPings = new HashSet<Long>();

        /**
         * Wait until neither a sync nor a ping is running on this account, and then return.
         * If there's a ping running, actively stop it. (For syncs, we have to just wait.)
         * @param accountId The account we want to wait for.
         */
        private synchronized void waitUntilNoActivity(final long accountId) {
            while (mPingHandlers.containsKey(accountId)) {
                final EasPingSyncHandler pingHandler = mPingHandlers.get(accountId);
                if (pingHandler != null) {
                    pingHandler.stop();
                }
                try {
                    wait();
                } catch (InterruptedException e) {
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

        private void stopServiceIfNoPings() {
            for (final EasPingSyncHandler pingHandler : mPingHandlers.values()) {
                if (pingHandler != null) {
                    return;
                }
            }
            EmailSyncAdapterService.this.stopSelf();
        }

        /**
         * Called prior to starting a sync to update our state.
         * @param accountId The account on which we are running a sync.
         */
        public synchronized void startSync(final long accountId) {
            waitUntilNoActivity(accountId);
            mPingHandlers.put(accountId, null);
        }

        /**
         * Called prior to starting, stopping, or changing a ping for reasons other than a sync
         * request (e.g. new account added, settings change, or app startup). This is currently
         * implemented as shutting down any running ping and starting a new one if needed. It might
         * be better to signal any running ping to reload itself, but this is simpler for now.
         * @param accountId The account whose ping is being modified.
         */
        public synchronized void modifyPing(final long accountId) {
            // If a sync is currently running, we'd have to wait for it complete, but it'll call
            // modifyPing at that point anyway. Therefore we can ignore this request.
            if (isRunningSync(accountId)) {
                return;
            }

            // Similarly, if multiple ping requests happen while a ping is running, we can ignore
            // all but one of them -- by the time the first one is done waiting, it'll pick up the
            // latest account settings anyway.
            if (mPendingPings.contains(accountId)) {
                return;
            }
            mPendingPings.add(accountId);

            try {
                // TODO: If a ping is running, it'd be better to just tell it to reload its state
                // rather than kill and restart it.
                waitUntilNoActivity(accountId);
                final Context context = EmailSyncAdapterService.this;
                // No ping or sync running. Figure out whether a ping is needed, and if so with
                // what params.
                final Account account = Account.restoreAccountWithId(context, accountId);
                if (account == null || account.mSyncInterval != Account.CHECK_INTERVAL_PUSH) {
                    // A ping that was running is no longer running, or something happened to the
                    // account.
                    stopServiceIfNoPings();
                } else {
                    // Note: unlike startSync, we CANNOT allow the caller to do the actual work.
                    // If we return before the ping starts, there's a race condition where another
                    // ping or sync might start first. It only works for startSync because sync is
                    // higher priority than ping (i.e. a ping can't start while a sync is pending)
                    // and only one ping can run at a time.
                    EasPingSyncHandler pingHandler = new EasPingSyncHandler(context, account, this);
                    mPingHandlers.put(accountId, pingHandler);
                    // Whenever we have a running ping, make sure this service stays running.
                    final EmailSyncAdapterService service = EmailSyncAdapterService.this;
                    service.startService(new Intent(service, EmailSyncAdapterService.class));
                }
            } finally {
                mPendingPings.remove(accountId);
            }
        }

        /**
         * All operations must call this when they complete to update the synchronization
         * bookkeeping.
         * @param accountId The account whose ping or sync just completed.
         * @param wasSync Whether the operation that's completing was a sync.
         * @param notify Whether to notify all threads waiting on this object. This should be true
         *     for all sync operations, and for any pings that were interrupted. Pings that complete
         *     naturally possibly don't need to wake up anyone else.
         *     TODO: is this optimization worth any possible problem? For example, the syncs started
         *     by a ping may need to be signaled here.
         */
        public synchronized void signalDone(final long accountId, final boolean wasSync,
                final boolean notify) {
            mPingHandlers.remove(accountId);
            // If this was a sync, we may have killed a ping that now needs to be restarted.
            // modifyPing will do the appropriate checks.
            // We do this here rather than at the caller because at this point, we are guaranteed
            // that there is no entry for this account in mPingHandlers, and therefore we cannot
            // block.
            if (wasSync) {
                modifyPing(accountId);
            } else {
                // A ping stopped, so check if we should stop the service.
                stopServiceIfNoPings();
            }

            // Similarly, it's ok to notify after we restart the ping, because we know the ping
            // can't possibly be waiting.
            if (notify) {
                notifyAll();
            }
        }
    }
    private final SyncHandlerSynchronizer mSyncHandlerMap = new SyncHandlerSynchronizer();

    /**
     * The binder for IEmailService.
     */
    private final IEmailService.Stub mBinder = new IEmailService.Stub() {
        @Override
        public Bundle validate(final HostAuth hostAuth) {
            Log.d(TAG, "IEmailService.validate");
            return new EasAccountValidator(EmailSyncAdapterService.this, hostAuth).validate();
        }

        @Override
        public Bundle autoDiscover(final String userName, final String password) {
            Log.d(TAG, "IEmailService.autoDiscover");
            HostAuth hostAuth = new HostAuth();
            hostAuth.mLogin = userName;
            hostAuth.mPassword = password;
            hostAuth.mFlags = HostAuth.FLAG_AUTHENTICATE | HostAuth.FLAG_SSL;
            hostAuth.mPort = 443;
            return null;
            //return new EasSyncService().tryAutodiscover(ExchangeService.this, hostAuth);
        }

        @Override
        public void updateFolderList(final long accountId) {
            Log.d(TAG, "IEmailService.updateFolderList");
            final String emailAddress = Utility.getFirstRowString(EmailSyncAdapterService.this,
                    Account.CONTENT_URI, new String[] {AccountColumns.EMAIL_ADDRESS},
                    Account.ID_SELECTION, new String[] {Long.toString(accountId)}, null, 0);
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
        public void loadAttachment(final long attachmentId, final boolean background) {
            Log.d(TAG, "IEmailService.loadAttachment");
            // TODO: Implement.
            /*
            Attachment att = Attachment.restoreAttachmentWithId(ExchangeService.this, attachmentId);
            log("loadAttachment " + attachmentId + ": " + att.mFileName);
            sendMessageRequest(new PartRequest(att, null, null));
            */
        }

        @Override
        public void sendMeetingResponse(final long messageId, final int response) {
            Log.d(TAG, "IEmailService.sendMeetingResponse");
            // TODO: Implement.
            //sendMessageRequest(new MeetingResponseRequest(messageId, response));
        }

        /**
         * Delete PIM (calendar, contacts) data for the specified account
         *
         * @param accountId the account whose data should be deleted
         */
        @Override
        public void deleteAccountPIMData(final long accountId) {
            Log.d(TAG, "IEmailService.deleteAccountPIMData");
            // TODO: Implement
            /*
            SyncManager exchangeService = INSTANCE;
            if (exchangeService == null) return;
            // Stop any running syncs
            ExchangeService.stopAccountSyncs(accountId);
            // Delete the data
            ExchangeService.deleteAccountPIMData(ExchangeService.this, accountId);
            long accountMailboxId = Mailbox.findMailboxOfType(exchangeService, accountId,
                    Mailbox.TYPE_EAS_ACCOUNT_MAILBOX);
            if (accountMailboxId != Mailbox.NO_MAILBOX) {
                // Make sure the account mailbox is held due to security
                synchronized(sSyncLock) {
                    mSyncErrorMap.put(accountMailboxId, exchangeService.new SyncError(
                            AbstractSyncService.EXIT_SECURITY_FAILURE, false));

                }
            }
            // Make sure the reconciler runs
            runAccountReconcilerSync(ExchangeService.this);
            */
        }

        @Override
        public int searchMessages(final long accountId, final SearchParams searchParams,
                final long destMailboxId) {
            Log.d(TAG, "IEmailService.searchMessages");
            return 0;
            // TODO: Implement correctly.
            /*
            return Search.searchMessages(exchangeService, accountId, searchParams,
                    destMailboxId);
                    */
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
        public void onPerformSync(android.accounts.Account acct, Bundle extras,
                String authority, ContentProviderClient provider, SyncResult syncResult) {

            TempDirectory.setTempDirectory(EmailSyncAdapterService.this);

            // TODO: Perform any connectivity checks, bail early if we don't have proper network
            // for this sync operation.

            final Context context = getContext();
            Log.i(TAG, "performSync");
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

            // TODO: If this account is on security hold (i.e. not enforcing policy), do not permit
            // sync to occur.

            // Do the bookkeeping for starting a sync, including stopping a ping if necessary.
            mSyncHandlerMap.startSync(account.mId);

            // TODO: Should we refresh the account here? It may have changed while waiting for any
            // pings to stop. It may not matter since the things that may have been twiddled might
            // not affect syncing.

            final long mailboxId = extras.getLong(Mailbox.SYNC_EXTRA_MAILBOX_ID,
                    Mailbox.NO_MAILBOX);
            if (mailboxId == Mailbox.NO_MAILBOX) {
                // If no mailbox is specified, this is an account sync.
                final EasAccountSyncHandler accountSyncHandler =
                        new EasAccountSyncHandler(context, account);
                accountSyncHandler.performSync();

                // Account sync also does an inbox sync.
                final Mailbox inbox = Mailbox.restoreMailboxOfType(context, account.mId,
                        Mailbox.TYPE_INBOX);
                final EasSyncHandler inboxSyncHandler = EasSyncHandler.getEasSyncHandler(
                        context, cr, account, inbox, extras, syncResult);
                if (inboxSyncHandler == null) {
                    // TODO: Inbox does not exist for this account, add proper error handling.
                } else {
                    inboxSyncHandler.performSync();
                }
            } else {
                // Sync the mailbox that was explicitly requested.
                final Mailbox mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
                final EasSyncHandler syncHandler = EasSyncHandler.getEasSyncHandler(context, cr,
                        account, mailbox, extras, syncResult);

                if (syncHandler != null) {
                    syncHandler.performSync();
                } else {
                    // We can't sync this mailbox, so just send the expected UI callbacks.
                    EmailServiceStatus.syncMailboxStatus(cr, extras, mailboxId,
                            EmailServiceStatus.IN_PROGRESS, 0);
                    EmailServiceStatus.syncMailboxStatus(cr, extras, mailboxId,
                            EmailServiceStatus.SUCCESS, 0);
                }
            }

            // Signal any waiting ping that it's good to go now.
            mSyncHandlerMap.signalDone(account.mId, true, true);

            // TODO: It may make sense to have common error handling here. Two possible mechanisms:
            // 1) performSync return value can signal some useful info.
            // 2) syncResult can contain useful info.
        }
    }
}
