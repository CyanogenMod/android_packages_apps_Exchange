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

import android.app.AlarmManager;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

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
import com.android.emailcommon.service.ServiceProxy;
import com.android.emailcommon.utility.IntentUtilities;
import com.android.emailcommon.utility.Utility;
import com.android.exchange.Eas;
import com.android.exchange.R.drawable;
import com.android.exchange.R.string;
import com.android.exchange.adapter.PingParser;
import com.android.exchange.adapter.Search;
import com.android.exchange.eas.EasFolderSync;
import com.android.exchange.eas.EasMoveItems;
import com.android.exchange.eas.EasOperation;
import com.android.exchange.eas.EasPing;
import com.android.exchange.eas.EasSync;
import com.android.mail.providers.UIProvider;
import com.android.mail.providers.UIProvider.AccountCapabilities;
import com.android.mail.utils.LogUtils;

import java.util.HashMap;
import java.util.HashSet;

/**
 * Service for communicating with Exchange servers. There are three main parts of this class:
 * TODO: Flesh out these comments.
 * 1) An {@link AbstractThreadedSyncAdapter} to handle actually performing syncs.
 * 2) Bookkeeping for running Ping requests, which handles push notifications.
 * 3) An {@link IEmailService} Stub to handle RPC from the UI.
 */
public class EmailSyncAdapterService extends AbstractSyncAdapterService {

    private static final String TAG = Eas.LOG_TAG;

    private static final String EXTRA_START_PING = "START_PING";
    private static final String EXTRA_PING_ACCOUNT = "PING_ACCOUNT";
    private static final long SYNC_ERROR_BACKOFF_MILLIS = 5 * DateUtils.MINUTE_IN_MILLIS;

    /**
     * The amount of time between periodic syncs intended to ensure that push hasn't died.
     */
    private static final long KICK_SYNC_INTERVAL =
            DateUtils.HOUR_IN_MILLIS / DateUtils.SECOND_IN_MILLIS;

    /** Controls whether we do a periodic "kick" to restart the ping. */
    private static final boolean SCHEDULE_KICK = true;

    /**
     * If sync extras do not include a mailbox id, then we want to perform a full sync.
     */
    private static final long FULL_ACCOUNT_SYNC = Mailbox.NO_MAILBOX;

    /** Projection used for getting email address for an account. */
    private static final String[] ACCOUNT_EMAIL_PROJECTION = { AccountColumns.EMAIL_ADDRESS };

    private static final Object sSyncAdapterLock = new Object();
    private static AbstractThreadedSyncAdapter sSyncAdapter = null;

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
        private final HashMap<Long, PingTask> mPingHandlers = new HashMap<Long, PingTask>();

        /**
         * Wait until neither a sync nor a ping is running on this account, and then return.
         * If there's a ping running, actively stop it. (For syncs, we have to just wait.)
         * @param accountId The account we want to wait for.
         */
        private synchronized void waitUntilNoActivity(final long accountId) {
            while (mPingHandlers.containsKey(accountId)) {
                final PingTask pingHandler = mPingHandlers.get(accountId);
                if (pingHandler != null) {
                    pingHandler.stop();
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
            for (final PingTask pingHandler : mPingHandlers.values()) {
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
        public synchronized void modifyPing(final boolean lastSyncHadError,
                final Account account) {
            // If a sync is currently running, it will start a ping when it's done, so there's no
            // need to do anything right now.
            if (isRunningSync(account.mId)) {
                return;
            }

            // Don't ping if we're on security hold.
            if ((account.mFlags & Account.FLAGS_SECURITY_HOLD) != 0) {
                return;
            }

            // Don't ping for accounts that haven't performed initial sync.
            if (EmailContent.isInitialSyncKey(account.mSyncKey)) {
                return;
            }

            // Determine if this account needs pushes. All of the following must be true:
            // - The account's sync interval must indicate that it wants push.
            // - At least one content type must be sync-enabled in the account manager.
            // - At least one mailbox of a sync-enabled type must have automatic sync enabled.
            final EmailSyncAdapterService service = EmailSyncAdapterService.this;
            final android.accounts.Account amAccount = new android.accounts.Account(
                            account.mEmailAddress, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE);
            boolean pushNeeded = false;
            if (account.mSyncInterval == Account.CHECK_INTERVAL_PUSH) {
                final HashSet<String> authsToSync = getAuthsToSync(amAccount);
                // If we have at least one sync-enabled content type, check for syncing mailboxes.
                if (!authsToSync.isEmpty()) {
                    final Cursor c = Mailbox.getMailboxesForPush(service.getContentResolver(),
                            account.mId);
                    if (c != null) {
                        try {
                            while (c.moveToNext()) {
                                final int mailboxType = c.getInt(Mailbox.CONTENT_TYPE_COLUMN);
                                if (authsToSync.contains(Mailbox.getAuthority(mailboxType))) {
                                    pushNeeded = true;
                                    break;
                                }
                            }
                        } finally {
                            c.close();
                        }
                    }
                }
            }

            // Stop, start, or restart the ping as needed, as well as the ping kicker periodic sync.
            final PingTask pingSyncHandler = mPingHandlers.get(account.mId);
            final Bundle extras = new Bundle(1);
            extras.putBoolean(Mailbox.SYNC_EXTRA_PUSH_ONLY, true);
            if (pushNeeded) {
                // First start or restart the ping as appropriate.
                if (pingSyncHandler != null) {
                    pingSyncHandler.restart();
                } else {
                    // Start a new ping.
                    // Note: unlike startSync, we CANNOT allow the caller to do the actual work.
                    // If we return before the ping starts, there's a race condition where another
                    // ping or sync might start first. It only works for startSync because sync is
                    // higher priority than ping (i.e. a ping can't start while a sync is pending)
                    // and only one sync can run at a time.
                    if (lastSyncHadError) {
                        // Schedule an alarm to set up the ping in 5 minutes
                        final Intent intent = new Intent(service, EmailSyncAdapterService.class);
                        intent.setAction(Eas.EXCHANGE_SERVICE_INTENT_ACTION);
                        intent.putExtra(EXTRA_START_PING, true);
                        intent.putExtra(EXTRA_PING_ACCOUNT, amAccount);
                        final PendingIntent pi = PendingIntent.getService(
                                EmailSyncAdapterService.this, 0, intent,
                                PendingIntent.FLAG_ONE_SHOT);
                        final AlarmManager am = (AlarmManager)getSystemService(
                                Context.ALARM_SERVICE);
                        final long atTime = SystemClock.elapsedRealtime() +
                                SYNC_ERROR_BACKOFF_MILLIS;
                        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, atTime, pi);
                    } else {
                        final PingTask pingHandler = new PingTask(service, account, amAccount,
                                this);
                        mPingHandlers.put(account.mId, pingHandler);
                        pingHandler.start();
                        // Whenever we have a running ping, make sure this service stays running.
                        service.startService(new Intent(service, EmailSyncAdapterService.class));
                    }
                }
                if (SCHEDULE_KICK) {
                    ContentResolver.addPeriodicSync(amAccount, EmailContent.AUTHORITY, extras,
                               KICK_SYNC_INTERVAL);
                }
            } else {
                if (pingSyncHandler != null) {
                    pingSyncHandler.stop();
                }
                if (SCHEDULE_KICK) {
                    ContentResolver.removePeriodicSync(amAccount, EmailContent.AUTHORITY, extras);
                }
            }
        }

        /**
         * Updates the synchronization bookkeeping when a sync is done.
         * @param account The account whose sync just finished.
         */
        public synchronized void syncComplete(final boolean lastSyncHadError,
                final Account account) {
            LogUtils.d(TAG, "syncComplete, err: " + lastSyncHadError);
            mPingHandlers.remove(account.mId);
            // Syncs can interrupt pings, so we should check if we need to start one now.
            // If the last sync had a fatal error, we will not immediately recreate the ping.
            // Instead, we'll set an alarm that will restart them in a few minutes. This prevents
            // a battery draining spin if there is some kind of protocol error or other
            // non-transient failure. (Actually, immediately pinging even for a transient error
            // isn't great)
            modifyPing(lastSyncHadError, account);
            stopServiceIfNoPings();
            notifyAll();
        }

        /**
         * Updates the synchronization bookkeeping when a ping is done. Also requests a ping-only
         * sync if necessary.
         * @param amAccount The {@link android.accounts.Account} for this account.
         * @param accountId The account whose ping just finished.
         * @param pingStatus The status value from {@link PingParser} for the last ping performed.
         *                   This cannot be one of the values that results in another ping, so this
         *                   function only needs to handle the terminal statuses.
         */
        public synchronized void pingComplete(final android.accounts.Account amAccount,
                final long accountId, final int pingStatus) {
            mPingHandlers.remove(accountId);

            // TODO: if (pingStatus == PingParser.STATUS_FAILED), notify UI.
            // TODO: if (pingStatus == PingParser.STATUS_REQUEST_TOO_MANY_FOLDERS), notify UI.

            // TODO: Should this just re-request ping if status < 0? This would do the wrong thing
            // for e.g. auth errors, though.
            if (pingStatus == EasOperation.RESULT_REQUEST_FAILURE ||
                    pingStatus == EasOperation.RESULT_OTHER_FAILURE) {
                // Request a new ping through the SyncManager. This will do the right thing if the
                // exception was due to loss of network connectivity, etc. (i.e. it will wait for
                // network to restore and then request it).
                EasPing.requestPing(amAccount);
            } else {
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
            return new EasFolderSync(EmailSyncAdapterService.this, hostAuth).validate();
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
                final Bundle extras = new Bundle(1);
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
                ContentResolver.requestSync(new android.accounts.Account(
                        emailAddress, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE),
                        EmailContent.AUTHORITY, extras);
            }
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
            LogUtils.d(TAG, "IEmailService.sendMeetingResponse: %d, %d", messageId, response);
            EasMeetingResponder.sendMeetingResponse(EmailSyncAdapterService.this, messageId,
                    response);
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
            // TODO: may need an explicit callback to replace the one to IEmailServiceCallback.
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
                        AccountCapabilities.SMART_REPLY |
                        AccountCapabilities.UNDO |
                        AccountCapabilities.DISCARD_CONVERSATION_DRAFTS;
            } else {
                return AccountCapabilities.SYNCABLE_FOLDERS |
                        AccountCapabilities.SMART_REPLY |
                        AccountCapabilities.UNDO |
                        AccountCapabilities.DISCARD_CONVERSATION_DRAFTS;
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
    private static final String PUSH_ACCOUNTS_SELECTION =
            AccountColumns.SYNC_INTERVAL + "=" + Integer.toString(Account.CHECK_INTERVAL_PUSH);
    private class RestartPingsTask extends AsyncTask<Void, Void, Void> {

        private final ContentResolver mContentResolver;
        private final SyncHandlerSynchronizer mSyncHandlerMap;
        private boolean mAnyAccounts;

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
                    mAnyAccounts = (c.getCount() != 0);
                    while (c.moveToNext()) {
                        final Account account = new Account();
                        account.restore(c);
                        mSyncHandlerMap.modifyPing(false, account);
                    }
                } finally {
                    c.close();
                }
            } else {
                mAnyAccounts = false;
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if (!mAnyAccounts) {
                LogUtils.d(TAG, "stopping for no accounts");
                EmailSyncAdapterService.this.stopSelf();
            }
        }
    }

    @Override
    public void onCreate() {
        LogUtils.v(TAG, "onCreate()");
        super.onCreate();
        startService(new Intent(this, EmailSyncAdapterService.class));
        // Restart push for all accounts that need it.
        new RestartPingsTask(getContentResolver(), mSyncHandlerMap).executeOnExecutor(
                AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    public void onDestroy() {
        LogUtils.v(TAG, "onDestroy()");
        super.onDestroy();
        for (PingTask task : mSyncHandlerMap.mPingHandlers.values()) {
            if (task != null) {
                task.stop();
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (intent.getAction().equals(Eas.EXCHANGE_SERVICE_INTENT_ACTION)) {
            return mBinder;
        }
        return super.onBind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null &&
                TextUtils.equals(Eas.EXCHANGE_SERVICE_INTENT_ACTION, intent.getAction())) {
            if (intent.getBooleanExtra(ServiceProxy.EXTRA_FORCE_SHUTDOWN, false)) {
                // We've been asked to forcibly shutdown. This happens if email accounts are
                // deleted, otherwise we can get errors if services are still running for
                // accounts that are now gone.
                // TODO: This is kind of a hack, it would be nicer if we could handle it correctly
                // if accounts disappear out from under us.
                LogUtils.d(TAG, "Forced shutdown, killing process");
                System.exit(-1);
            } else if (intent.getBooleanExtra(EXTRA_START_PING, false)) {
                LogUtils.d(TAG, "Restarting ping from alarm");
                // We've been woken up by an alarm to restart our ping. This happens if a sync
                // fails, rather that instantly starting the ping, we'll hold off for a few minutes.
                final android.accounts.Account account =
                        intent.getParcelableExtra(EXTRA_PING_ACCOUNT);
                EasPing.requestPing(account);
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected AbstractThreadedSyncAdapter getSyncAdapter() {
        synchronized (sSyncAdapterLock) {
            if (sSyncAdapter == null) {
                sSyncAdapter = new SyncAdapterImpl(this);
            }
            return sSyncAdapter;
        }
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
            if (LogUtils.isLoggable(TAG, Log.DEBUG)) {
                LogUtils.d(TAG, "onPerformSync: %s, %s", acct.toString(), extras.toString());
            } else {
                LogUtils.i(TAG, "onPerformSync: %s", extras.toString());
            }
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
                    LogUtils.w(TAG, "onPerformSync: could not load account");
                    return;
                }
                account = new Account();
                account.restore(accountCursor);
            } finally {
                accountCursor.close();
            }

            // Figure out what we want to sync, based on the extras and our account sync status.
            final boolean isInitialSync = EmailContent.isInitialSyncKey(account.mSyncKey);
            final long[] mailboxIds = Mailbox.getMailboxIdsFromBundle(extras);
            final int mailboxType = extras.getInt(Mailbox.SYNC_EXTRA_MAILBOX_TYPE,
                    Mailbox.TYPE_NONE);
            final boolean hasCallbackMethod =
                    extras.containsKey(EmailServiceStatus.SYNC_EXTRAS_CALLBACK_METHOD);
            if (hasCallbackMethod && mailboxIds != null) {
                for (long mailboxId : mailboxIds) {
                    EmailServiceStatus.syncMailboxStatus(cr, extras, mailboxId,
                            EmailServiceStatus.IN_PROGRESS, 0, UIProvider.LastSyncResult.SUCCESS);
                }
            }

            // Push only means this sync request should only refresh the ping (either because
            // settings changed, or we need to restart it for some reason).
            final boolean pushOnly = Mailbox.isPushOnlyExtras(extras);
            // Account only means just do a FolderSync.
            final boolean accountOnly = Mailbox.isAccountOnlyExtras(extras);

            // A "full sync" means that we didn't request a more specific type of sync.
            final boolean isFullSync = (!pushOnly && !accountOnly && mailboxIds == null &&
                    mailboxType == Mailbox.TYPE_NONE);

            // A FolderSync is necessary for full sync, initial sync, and account only sync.
            final boolean isFolderSync = (isFullSync || isInitialSync || accountOnly);

            // If we're just twiddling the push, we do the lightweight thing and bail early.
            if (pushOnly && !isFolderSync) {
                mSyncHandlerMap.modifyPing(false, account);
                LogUtils.d(TAG, "onPerformSync: mailbox push only");
                return;
            }

            // Do the bookkeeping for starting a sync, including stopping a ping if necessary.
            mSyncHandlerMap.startSync(account.mId);

            // Perform a FolderSync if necessary.
            // TODO: We permit FolderSync even during security hold, because it's necessary to
            // resolve some holds. Ideally we would only do it for the holds that require it.
            if (isFolderSync) {
                final EasFolderSync folderSync = new EasFolderSync(context, account);
                folderSync.doFolderSync(syncResult);
            }

            boolean lastSyncHadError = false;

            if ((account.mFlags & Account.FLAGS_SECURITY_HOLD) == 0) {
                // Perform email upsync for this account. Moves first, then state changes.
                if (!isInitialSync) {
                    EasMoveItems move = new EasMoveItems(context, account);
                    move.upsyncMovedMessages(syncResult);
                    // TODO: EasSync should eventually handle both up and down; for now, it's used
                    // purely for upsync.
                    EasSync upsync = new EasSync(context, account);
                    upsync.upsync(syncResult);
                }

                // TODO: Should we refresh account here? It may have changed while waiting for any
                // pings to stop. It may not matter since the things that may have been twiddled
                // might not affect syncing.

                if (mailboxIds != null) {
                    long numIoExceptions = 0;
                    long numAuthExceptions = 0;
                    // Sync the mailbox that was explicitly requested.
                    for (final long mailboxId : mailboxIds) {
                        final boolean success = syncMailbox(context, cr, acct, account, mailboxId,
                                extras, syncResult, null, true);
                        if (!success) {
                            lastSyncHadError = true;
                        }
                        if (hasCallbackMethod) {
                            final int result;
                            if (syncResult.hasError()) {
                                if (syncResult.stats.numIoExceptions > numIoExceptions) {
                                    result = UIProvider.LastSyncResult.CONNECTION_ERROR;
                                    numIoExceptions = syncResult.stats.numIoExceptions;
                                } else if (syncResult.stats.numAuthExceptions> numAuthExceptions) {
                                    result = UIProvider.LastSyncResult.AUTH_ERROR;
                                    numAuthExceptions= syncResult.stats.numAuthExceptions;
                                }  else {
                                    result = UIProvider.LastSyncResult.INTERNAL_ERROR;
                                }
                            } else {
                                result = UIProvider.LastSyncResult.SUCCESS;
                            }
                            EmailServiceStatus.syncMailboxStatus(
                                    cr, extras, mailboxId,EmailServiceStatus.SUCCESS, 0, result);
                        }
                    }
                } else if (!accountOnly && !pushOnly) {
                    // We have to sync multiple folders.
                    final Cursor c;
                    if (isFullSync) {
                        // Full account sync includes all mailboxes that participate in system sync.
                        c = Mailbox.getMailboxIdsForSync(cr, account.mId);
                    } else {
                        // Type-filtered sync should only get the mailboxes of a specific type.
                        c = Mailbox.getMailboxIdsForSyncByType(cr, account.mId, mailboxType);
                    }
                    if (c != null) {
                        try {
                            final HashSet<String> authsToSync = getAuthsToSync(acct);
                            while (c.moveToNext()) {
                                boolean success = syncMailbox(context, cr, acct, account,
                                        c.getLong(0), extras, syncResult, authsToSync, false);
                                if (!success) {
                                    lastSyncHadError = true;
                                }
                            }
                        } finally {
                            c.close();
                        }
                    }
                }
            }

            // Clean up the bookkeeping, including restarting ping if necessary.
            mSyncHandlerMap.syncComplete(lastSyncHadError, account);

            // TODO: It may make sense to have common error handling here. Two possible mechanisms:
            // 1) performSync return value can signal some useful info.
            // 2) syncResult can contain useful info.
            LogUtils.d(TAG, "onPerformSync: finished");
        }

        /**
         * Update the mailbox's sync status with the provider and, if we're finished with the sync,
         * write the last sync time as well.
         * @param context Our {@link Context}.
         * @param mailbox The mailbox whose sync status to update.
         * @param cv A {@link ContentValues} object to use for updating the provider.
         * @param syncStatus The status for the current sync.
         */
        private void updateMailbox(final Context context, final Mailbox mailbox,
                final ContentValues cv, final int syncStatus) {
            cv.put(Mailbox.UI_SYNC_STATUS, syncStatus);
            if (syncStatus == EmailContent.SYNC_STATUS_NONE) {
                cv.put(Mailbox.SYNC_TIME, System.currentTimeMillis());
            }
            mailbox.update(context, cv);
        }

        private boolean syncMailbox(final Context context, final ContentResolver cr,
                final android.accounts.Account acct, final Account account, final long mailboxId,
                final Bundle extras, final SyncResult syncResult, final HashSet<String> authsToSync,
                final boolean isMailboxSync) {
            final Mailbox mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
            if (mailbox == null) {
                return false;
            }

            if (mailbox.mAccountKey != account.mId) {
                LogUtils.e(TAG, "Mailbox does not match account: %s, %s", acct.toString(),
                        extras.toString());
                return false;
            }
            if (authsToSync != null && !authsToSync.contains(Mailbox.getAuthority(mailbox.mType))) {
                // We are asking for an account sync, but this mailbox type is not configured for
                // sync. Do NOT treat this as a sync error for ping backoff purposes.
                return true;
            }

            if (mailbox.mType == Mailbox.TYPE_DRAFTS) {
                // TODO: Because we don't have bidirectional sync working, trying to downsync
                // the drafts folder is confusing. b/11158759
                // For now, just disable all syncing of DRAFTS type folders.
                // Automatic syncing should always be disabled, but we also stop it here to ensure
                // that we won't sync even if the user attempts to force a sync from the UI.
                // Do NOT treat as a sync error for ping backoff purposes.
                LogUtils.d(TAG, "Skipping sync of DRAFTS folder");
                return true;
            }
            final boolean success;
            // Non-mailbox syncs are whole account syncs initiated by the AccountManager and are
            // treated as background syncs.
            // TODO: Push will be treated as "user" syncs, and probably should be background.
            final ContentValues cv = new ContentValues(2);
            updateMailbox(context, mailbox, cv, isMailboxSync ?
                    EmailContent.SYNC_STATUS_USER : EmailContent.SYNC_STATUS_BACKGROUND);
            if (mailbox.mType == Mailbox.TYPE_OUTBOX) {
                final EasOutboxSyncHandler outboxSyncHandler =
                        new EasOutboxSyncHandler(context, account, mailbox);
                outboxSyncHandler.performSync();
                success = true;
            } else if(mailbox.isSyncable()) {
                final EasSyncHandler syncHandler = EasSyncHandler.getEasSyncHandler(context, cr,
                        acct, account, mailbox, extras, syncResult);
                if (syncHandler != null) {
                    success = syncHandler.performSync(syncResult);
                } else {
                    success = false;
                }
            } else {
                success = false;
            }
            updateMailbox(context, mailbox, cv, EmailContent.SYNC_STATUS_NONE);

            if (syncResult.stats.numAuthExceptions > 0) {
                showAuthNotification(account.mId, account.mEmailAddress);
            }
            return success;
        }
    }
    private void showAuthNotification(long accountId, String accountName) {
        final PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                createAccountSettingsIntent(accountId, accountName),
                0);

        final Notification notification = new Builder(this)
                .setContentTitle(this.getString(string.auth_error_notification_title))
                .setContentText(this.getString(
                        string.auth_error_notification_text, accountName))
                .setSmallIcon(drawable.stat_notify_auth)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        final NotificationManager nm = (NotificationManager)
                this.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify("AuthError", 0, notification);
    }

    /**
     * Create and return an intent to display (and edit) settings for a specific account, or -1
     * for any/all accounts.  If an account name string is provided, a warning dialog will be
     * displayed as well.
     */
    public static Intent createAccountSettingsIntent(long accountId, String accountName) {
        final Uri.Builder builder = IntentUtilities.createActivityIntentUrlBuilder(
                IntentUtilities.PATH_SETTINGS);
        IntentUtilities.setAccountId(builder, accountId);
        IntentUtilities.setAccountName(builder, accountName);
        return new Intent(Intent.ACTION_EDIT, builder.build());
    }

    /**
     * Determine which content types are set to sync for an account.
     * @param account The account whose sync settings we're looking for.
     * @return The authorities for the content types we want to sync for account.
     */
    private static HashSet<String> getAuthsToSync(final android.accounts.Account account) {
        final HashSet<String> authsToSync = new HashSet();
        if (ContentResolver.getSyncAutomatically(account, EmailContent.AUTHORITY)) {
            authsToSync.add(EmailContent.AUTHORITY);
        }
        if (ContentResolver.getSyncAutomatically(account, CalendarContract.AUTHORITY)) {
            authsToSync.add(CalendarContract.AUTHORITY);
        }
        if (ContentResolver.getSyncAutomatically(account, ContactsContract.AUTHORITY)) {
            authsToSync.add(ContactsContract.AUTHORITY);
        }
        return authsToSync;
    }
}
