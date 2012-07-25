/*
 * Copyright (C) 2008-2009 Marc Blank
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

package com.android.exchange;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.util.Log;

import com.android.emailcommon.Api;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.MailboxUtilities;
import com.android.emailcommon.provider.ProviderUnavailableException;
import com.android.emailcommon.service.AccountServiceProxy;
import com.android.emailcommon.service.EmailServiceCallback;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.IEmailServiceCallback.Stub;
import com.android.emailcommon.service.SearchParams;
import com.android.emailsync.AbstractSyncService;
import com.android.emailsync.PartRequest;
import com.android.emailsync.SyncManager;
import com.android.exchange.adapter.CalendarSyncAdapter;
import com.android.exchange.adapter.ContactsSyncAdapter;
import com.android.exchange.adapter.Search;
import com.android.exchange.utility.FileLogger;
import com.android.mail.providers.UIProvider.AccountCapabilities;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ExchangeService handles all aspects of starting, maintaining, and stopping the various sync
 * adapters used by Exchange.  However, it is capable of handing any kind of email sync, and it
 * would be appropriate to use for IMAP push, when that functionality is added to the Email
 * application.
 *
 * The Email application communicates with EAS sync adapters via ExchangeService's binder interface,
 * which exposes UI-related functionality to the application (see the definitions below)
 *
 * ExchangeService uses ContentObservers to detect changes to accounts, mailboxes, and messages in
 * order to maintain proper 2-way syncing of data.  (More documentation to follow)
 *
 */
public class ExchangeService extends SyncManager {

    private static final String TAG = "ExchangeService";

    private static final String WHERE_PUSH_OR_PING_NOT_ACCOUNT_MAILBOX =
        MailboxColumns.ACCOUNT_KEY + "=? and " + MailboxColumns.TYPE + "!=" +
        Mailbox.TYPE_EAS_ACCOUNT_MAILBOX + " and " + MailboxColumns.SYNC_INTERVAL +
        " IN (" + Mailbox.CHECK_INTERVAL_PING + ',' + Mailbox.CHECK_INTERVAL_PUSH + ')';
    private static final String WHERE_MAILBOX_KEY = Message.MAILBOX_KEY + "=?";
    private static final String WHERE_CALENDAR_ID = Events.CALENDAR_ID + "=?";
    private static final String ACCOUNT_KEY_IN = MailboxColumns.ACCOUNT_KEY + " in (";

    // Offsets into the syncStatus data for EAS that indicate type, exit status, and change count
    // The format is S<type_char>:<exit_char>:<change_count>
    public static final int STATUS_TYPE_CHAR = 1;
    public static final int STATUS_EXIT_CHAR = 3;
    public static final int STATUS_CHANGE_COUNT_OFFSET = 5;

    private static final int EAS_12_CAPABILITIES =
            AccountCapabilities.SYNCABLE_FOLDERS |
            AccountCapabilities.SERVER_SEARCH |
            AccountCapabilities.FOLDER_SERVER_SEARCH |
            AccountCapabilities.SANITIZED_HTML |
            AccountCapabilities.SMART_REPLY |
            AccountCapabilities.SERVER_SEARCH |
            AccountCapabilities.UNDO;

    private static final int EAS_2_CAPABILITIES =
            AccountCapabilities.SYNCABLE_FOLDERS |
            AccountCapabilities.SANITIZED_HTML |
            AccountCapabilities.SMART_REPLY |
            AccountCapabilities.UNDO;

    // We synchronize on this for all actions affecting the service and error maps
    private static final Object sSyncLock = new Object();
    private String mEasAccountSelector;

    // Concurrent because CalendarSyncAdapter can modify the map during a wipe
    private final ConcurrentHashMap<Long, CalendarObserver> mCalendarObservers =
            new ConcurrentHashMap<Long, CalendarObserver>();

    // Callbacks as set up via setCallback
    private static final RemoteCallbackList<IEmailServiceCallback> mCallbackList =
            new RemoteCallbackList<IEmailServiceCallback>();

    static private final EmailServiceCallback sCallbackProxy =
            new EmailServiceCallback(mCallbackList);

    /**
     * Create our EmailService implementation here.
     */
    private final IEmailService.Stub mBinder = new IEmailService.Stub() {

        @Override
        public int getApiLevel() {
            return Api.LEVEL;
        }

        @Override
        public Bundle validate(HostAuth hostAuth) throws RemoteException {
            return AbstractSyncService.validate(EasSyncService.class,
                    hostAuth, ExchangeService.this);
        }

        @Override
        public Bundle autoDiscover(String userName, String password) throws RemoteException {
            return new EasSyncService().tryAutodiscover(userName, password);
        }

        @Override
        public void startSync(long mailboxId, boolean userRequest) throws RemoteException {
            SyncManager exchangeService = INSTANCE;
            if (exchangeService == null) return;
            checkExchangeServiceServiceRunning();
            Mailbox m = Mailbox.restoreMailboxWithId(exchangeService, mailboxId);
            if (m == null) return;
            Account acct = Account.restoreAccountWithId(exchangeService, m.mAccountKey);
            if (acct == null) return;
            // If this is a user request and we're being held, release the hold; this allows us to
            // try again (the hold might have been specific to this account and released already)
            if (userRequest) {
                if (onSyncDisabledHold(acct)) {
                    releaseSyncHolds(exchangeService, AbstractSyncService.EXIT_ACCESS_DENIED, acct);
                    log("User requested sync of account in sync disabled hold; releasing");
                } else if (onSecurityHold(acct)) {
                    releaseSyncHolds(exchangeService, AbstractSyncService.EXIT_SECURITY_FAILURE,
                            acct);
                    log("User requested sync of account in security hold; releasing");
                }
                if (sConnectivityHold) {
                    // UI is expecting the callbacks....
                    sCallbackProxy.syncMailboxStatus(mailboxId, EmailServiceStatus.IN_PROGRESS,
                            0);
                    sCallbackProxy.syncMailboxStatus(mailboxId,
                            EmailServiceStatus.CONNECTION_ERROR, 0);
                    return;
                }
            }
            if (m.mType == Mailbox.TYPE_OUTBOX) {
                // We're using SERVER_ID to indicate an error condition (it has no other use for
                // sent mail)  Upon request to sync the Outbox, we clear this so that all messages
                // are candidates for sending.
                ContentValues cv = new ContentValues();
                cv.put(SyncColumns.SERVER_ID, 0);
                exchangeService.getContentResolver().update(Message.CONTENT_URI,
                    cv, WHERE_MAILBOX_KEY, new String[] {Long.toString(mailboxId)});
                // Clear the error state; the Outbox sync will be started from checkMailboxes
                exchangeService.mSyncErrorMap.remove(mailboxId);
                kick("start outbox");
                // Outbox can't be synced in EAS
                return;
            } else if (!isSyncable(m)) {
                // UI may be expecting the callbacks, so send them
                sCallbackProxy.syncMailboxStatus(mailboxId, EmailServiceStatus.IN_PROGRESS, 0);
                sCallbackProxy.syncMailboxStatus(mailboxId, EmailServiceStatus.SUCCESS, 0);
                return;
            }
            startManualSync(mailboxId, userRequest ? ExchangeService.SYNC_UI_REQUEST :
                ExchangeService.SYNC_SERVICE_START_SYNC, null);
        }

        @Override
        public void stopSync(long mailboxId) throws RemoteException {
            stopManualSync(mailboxId);
        }

        @Override
        public void loadAttachment(long attachmentId, boolean background) throws RemoteException {
            Attachment att = Attachment.restoreAttachmentWithId(ExchangeService.this, attachmentId);
            log("loadAttachment " + attachmentId + ": " + att.mFileName);
            sendMessageRequest(new PartRequest(att, null, null));
        }

        @Override
        public void updateFolderList(long accountId) throws RemoteException {
            reloadFolderList(ExchangeService.this, accountId, false);
        }

        @Override
        public void hostChanged(long accountId) throws RemoteException {
            SyncManager exchangeService = INSTANCE;
            if (exchangeService == null) return;
            ConcurrentHashMap<Long, SyncError> syncErrorMap = exchangeService.mSyncErrorMap;
            // Go through the various error mailboxes
            for (long mailboxId: syncErrorMap.keySet()) {
                SyncError error = syncErrorMap.get(mailboxId);
                // If it's a login failure, look a little harder
                Mailbox m = Mailbox.restoreMailboxWithId(exchangeService, mailboxId);
                // If it's for the account whose host has changed, clear the error
                // If the mailbox is no longer around, remove the entry in the map
                if (m == null) {
                    syncErrorMap.remove(mailboxId);
                } else if (error != null && m.mAccountKey == accountId) {
                    error.fatal = false;
                    error.holdEndTime = 0;
                }
            }
            // Stop any running syncs
            exchangeService.stopAccountSyncs(accountId, true);
            // Kick ExchangeService
            kick("host changed");
        }

        @Override
        public void setLogging(int flags) throws RemoteException {
            // Protocol logging
            Eas.setUserDebug(flags);
            // Sync logging
            setUserDebug(flags);
        }

        @Override
        public void sendMeetingResponse(long messageId, int response) throws RemoteException {
            sendMessageRequest(new MeetingResponseRequest(messageId, response));
        }

        @Override
        public void loadMore(long messageId) throws RemoteException {
        }

        // The following three methods are not implemented in this version
        @Override
        public boolean createFolder(long accountId, String name) throws RemoteException {
            return false;
        }

        @Override
        public boolean deleteFolder(long accountId, String name) throws RemoteException {
            return false;
        }

        @Override
        public boolean renameFolder(long accountId, String oldName, String newName)
                throws RemoteException {
            return false;
        }

        @Override
        public void setCallback(IEmailServiceCallback cb) throws RemoteException {
            mCallbackList.register(cb);
        }

        /**
         * Delete PIM (calendar, contacts) data for the specified account
         *
         * @param accountId the account whose data should be deleted
         * @throws RemoteException
         */
        @Override
        public void deleteAccountPIMData(long accountId) throws RemoteException {
            SyncManager exchangeService = INSTANCE;
            if (exchangeService == null) return;
            // Stop any running syncs
            ExchangeService.stopAccountSyncs(accountId);
            // Delete the data
            ExchangeService.deleteAccountPIMData(accountId);
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
        }

        @Override
        public int searchMessages(long accountId, SearchParams searchParams, long destMailboxId) {
            SyncManager exchangeService = INSTANCE;
            if (exchangeService == null) return 0;
            return Search.searchMessages(exchangeService, accountId, searchParams,
                    destMailboxId);
        }

        @Override
        public void sendMail(long accountId) throws RemoteException {
        }

        @Override
        public int getCapabilities(Account acct) throws RemoteException {
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
                return EAS_12_CAPABILITIES;
            } else {
                return EAS_2_CAPABILITIES;
            }
        }
    };

    /**
     * Return a list of all Accounts in EmailProvider.  Because the result of this call may be used
     * in account reconciliation, an exception is thrown if the result cannot be guaranteed accurate
     * @param context the caller's context
     * @param accounts a list that Accounts will be added into
     * @return the list of Accounts
     * @throws ProviderUnavailableException if the list of Accounts cannot be guaranteed valid
     */
    public AccountList collectAccounts(Context context, AccountList accounts) {
        ContentResolver resolver = context.getContentResolver();
        Cursor c = resolver.query(Account.CONTENT_URI, Account.CONTENT_PROJECTION, null, null,
                null);
        // We must throw here; callers might use the information we provide for reconciliation, etc.
        if (c == null) throw new ProviderUnavailableException();
        try {
            ContentValues cv = new ContentValues();
            while (c.moveToNext()) {
                long hostAuthId = c.getLong(Account.CONTENT_HOST_AUTH_KEY_RECV_COLUMN);
                if (hostAuthId > 0) {
                    HostAuth ha = HostAuth.restoreHostAuthWithId(context, hostAuthId);
                    if (ha != null && ha.mProtocol.equals("eas")) {
                        Account account = new Account();
                        account.restore(c);
                        // Cache the HostAuth
                        account.mHostAuthRecv = ha;
                        accounts.add(account);
                        // Fixup flags for inbox (should accept moved mail)
                        Mailbox inbox = Mailbox.restoreMailboxOfType(context, account.mId,
                                Mailbox.TYPE_INBOX);
                        if (inbox != null &&
                                ((inbox.mFlags & Mailbox.FLAG_ACCEPTS_MOVED_MAIL) == 0)) {
                            cv.put(MailboxColumns.FLAGS,
                                    inbox.mFlags | Mailbox.FLAG_ACCEPTS_MOVED_MAIL);
                            resolver.update(
                                    ContentUris.withAppendedId(Mailbox.CONTENT_URI, inbox.mId), cv,
                                    null, null);
                        }
                    }
                }
            }
        } finally {
            c.close();
        }
        return accounts;
    }

    static public IEmailServiceCallback callback() {
        return sCallbackProxy;
    }

    public static void deleteAccountPIMData(long accountId) {
        SyncManager exchangeService = INSTANCE;
        if (exchangeService == null) return;
        Mailbox mailbox =
            Mailbox.restoreMailboxOfType(exchangeService, accountId, Mailbox.TYPE_CONTACTS);
        if (mailbox != null) {
            EasSyncService service = EasSyncService.getServiceForMailbox(exchangeService, mailbox);
            ContactsSyncAdapter adapter = new ContactsSyncAdapter(service);
            adapter.wipe();
        }
        mailbox =
            Mailbox.restoreMailboxOfType(exchangeService, accountId, Mailbox.TYPE_CALENDAR);
        if (mailbox != null) {
            EasSyncService service = EasSyncService.getServiceForMailbox(exchangeService, mailbox);
            CalendarSyncAdapter adapter = new CalendarSyncAdapter(service);
            adapter.wipe();
        }
    }

    private boolean onSecurityHold(Account account) {
        return (account.mFlags & Account.FLAGS_SECURITY_HOLD) != 0;
    }

    private boolean onSyncDisabledHold(Account account) {
        return (account.mFlags & Account.FLAGS_SYNC_DISABLED) != 0;
    }

    /**
     * Unregister all CalendarObserver's
     */
    static public void unregisterCalendarObservers() {
        ExchangeService exchangeService = (ExchangeService)INSTANCE;
        if (exchangeService == null) return;
        ContentResolver resolver = exchangeService.mResolver;
        for (CalendarObserver observer: exchangeService.mCalendarObservers.values()) {
            resolver.unregisterContentObserver(observer);
        }
        exchangeService.mCalendarObservers.clear();
    }

    private class CalendarObserver extends ContentObserver {
        long mAccountId;
        long mCalendarId;
        long mSyncEvents;
        String mAccountName;

        public CalendarObserver(Handler handler, Account account) {
            super(handler);
            mAccountId = account.mId;
            mAccountName = account.mEmailAddress;

            // Find the Calendar for this account
            Cursor c = mResolver.query(Calendars.CONTENT_URI,
                    new String[] {Calendars._ID, Calendars.SYNC_EVENTS},
                    CalendarSyncAdapter.CALENDAR_SELECTION,
                    new String[] {account.mEmailAddress, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE},
                    null);
            if (c != null) {
                // Save its id and its sync events status
                try {
                    if (c.moveToFirst()) {
                        mCalendarId = c.getLong(0);
                        mSyncEvents = c.getLong(1);
                    }
                } finally {
                    c.close();
                }
            }
        }

        @Override
        public synchronized void onChange(boolean selfChange) {
            // See if the user has changed syncing of our calendar
            if (!selfChange) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Cursor c = mResolver.query(Calendars.CONTENT_URI,
                                    new String[] {Calendars.SYNC_EVENTS}, Calendars._ID + "=?",
                                    new String[] {Long.toString(mCalendarId)}, null);
                            if (c == null) return;
                            // Get its sync events; if it's changed, we've got work to do
                            try {
                                if (c.moveToFirst()) {
                                    long newSyncEvents = c.getLong(0);
                                    if (newSyncEvents != mSyncEvents) {
                                        log("_sync_events changed for calendar in " + mAccountName);
                                        Mailbox mailbox = Mailbox.restoreMailboxOfType(INSTANCE,
                                                mAccountId, Mailbox.TYPE_CALENDAR);
                                        // Sanity check for mailbox deletion
                                        if (mailbox == null) return;
                                        ContentValues cv = new ContentValues();
                                        if (newSyncEvents == 0) {
                                            // When sync is disabled, we're supposed to delete
                                            // all events in the calendar
                                            log("Deleting events and setting syncKey to 0 for " +
                                                    mAccountName);
                                            // First, stop any sync that's ongoing
                                            stopManualSync(mailbox.mId);
                                            // Set the syncKey to 0 (reset)
                                            EasSyncService service =
                                                EasSyncService.getServiceForMailbox(
                                                        INSTANCE, mailbox);
                                            CalendarSyncAdapter adapter =
                                                new CalendarSyncAdapter(service);
                                            try {
                                                adapter.setSyncKey("0", false);
                                            } catch (IOException e) {
                                                // The provider can't be reached; nothing to be done
                                            }
                                            // Reset the sync key locally and stop syncing
                                            cv.put(Mailbox.SYNC_KEY, "0");
                                            cv.put(Mailbox.SYNC_INTERVAL,
                                                    Mailbox.CHECK_INTERVAL_NEVER);
                                            mResolver.update(ContentUris.withAppendedId(
                                                    Mailbox.CONTENT_URI, mailbox.mId), cv, null,
                                                    null);
                                            // Delete all events using the sync adapter
                                            // parameter so that the deletion is only local
                                            Uri eventsAsSyncAdapter =
                                                CalendarSyncAdapter.asSyncAdapter(
                                                    Events.CONTENT_URI,
                                                    mAccountName,
                                                    Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE);
                                            mResolver.delete(eventsAsSyncAdapter, WHERE_CALENDAR_ID,
                                                    new String[] {Long.toString(mCalendarId)});
                                        } else {
                                            // Make this a push mailbox and kick; this will start
                                            // a resync of the Calendar; the account mailbox will
                                            // ping on this during the next cycle of the ping loop
                                            cv.put(Mailbox.SYNC_INTERVAL,
                                                    Mailbox.CHECK_INTERVAL_PUSH);
                                            mResolver.update(ContentUris.withAppendedId(
                                                    Mailbox.CONTENT_URI, mailbox.mId), cv, null,
                                                    null);
                                            kick("calendar sync changed");
                                        }

                                        // Save away the new value
                                        mSyncEvents = newSyncEvents;
                                    }
                                }
                            } finally {
                                c.close();
                            }
                        } catch (ProviderUnavailableException e) {
                            Log.w(TAG, "Observer failed; provider unavailable");
                        }
                    }}, "Calendar Observer").start();
            }
        }
    }

    /**
     * Blocking call to the account reconciler
     */
    public void runAccountReconcilerSync(Context context) {
        alwaysLog("Reconciling accounts...");
        new AccountServiceProxy(context).reconcileAccounts(
                Eas.PROTOCOL, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE);
    }

    public static void log(String str) {
        log(TAG, str);
    }

    public static void log(String tag, String str) {
        if (Eas.USER_LOG) {
            Log.d(tag, str);
            if (Eas.FILE_LOG) {
                FileLogger.log(tag, str);
            }
        }
    }

    public static void alwaysLog(String str) {
        if (!Eas.USER_LOG) {
            Log.d(TAG, str);
        } else {
            log(str);
        }
    }

    /**
     * EAS requires a unique device id, so that sync is possible from a variety of different
     * devices (e.g. the syncKey is specific to a device)  If we're on an emulator or some other
     * device that doesn't provide one, we can create it as "device".
     * This would work on a real device as well, but it would be better to use the "real" id if
     * it's available
     */
    static public String getDeviceId(Context context) throws IOException {
        if (sDeviceId == null) {
            sDeviceId = new AccountServiceProxy(context).getDeviceId();
            alwaysLog("Received deviceId from Email app: " + sDeviceId);
        }
        return sDeviceId;
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return mBinder;
    }

    static private void reloadFolderListFailed(long accountId) {
        try {
            callback().syncMailboxListStatus(accountId,
                    EmailServiceStatus.ACCOUNT_UNINITIALIZED, 0);
        } catch (RemoteException e1) {
            // Don't care if this fails
        }
    }

    static public void reloadFolderList(Context context, long accountId, boolean force) {
        SyncManager exchangeService = INSTANCE;
        if (exchangeService == null) return;
        Cursor c = context.getContentResolver().query(Mailbox.CONTENT_URI,
                Mailbox.CONTENT_PROJECTION, MailboxColumns.ACCOUNT_KEY + "=? AND " +
                MailboxColumns.TYPE + "=?",
                new String[] {Long.toString(accountId),
                    Long.toString(Mailbox.TYPE_EAS_ACCOUNT_MAILBOX)}, null);
        try {
            if (c.moveToFirst()) {
                synchronized(sSyncLock) {
                    Mailbox mailbox = new Mailbox();
                    mailbox.restore(c);
                    Account acct = Account.restoreAccountWithId(context, accountId);
                    if (acct == null) {
                        reloadFolderListFailed(accountId);
                        return;
                    }
                    String syncKey = acct.mSyncKey;
                    // No need to reload the list if we don't have one
                    if (!force && (syncKey == null || syncKey.equals("0"))) {
                        reloadFolderListFailed(accountId);
                        return;
                    }

                    // Change all ping/push boxes to push/hold
                    ContentValues cv = new ContentValues();
                    cv.put(Mailbox.SYNC_INTERVAL, Mailbox.CHECK_INTERVAL_PUSH_HOLD);
                    context.getContentResolver().update(Mailbox.CONTENT_URI, cv,
                            WHERE_PUSH_OR_PING_NOT_ACCOUNT_MAILBOX,
                            new String[] {Long.toString(accountId)});
                    log("Set push/ping boxes to push/hold");

                    long id = mailbox.mId;
                    AbstractSyncService svc = exchangeService.mServiceMap.get(id);
                    // Tell the service we're done
                    if (svc != null) {
                        synchronized (svc.getSynchronizer()) {
                            svc.stop();
                            // Interrupt the thread so that it can stop
                            Thread thread = svc.mThread;
                            if (thread != null) {
                                thread.setName(thread.getName() + " (Stopped)");
                                thread.interrupt();
                            }
                        }
                        // Abandon the service
                        exchangeService.releaseMailbox(id);
                        // And have it start naturally
                        kick("reload folder list");
                    }
                }
            }
        } finally {
            c.close();
        }
    }

    /**
     * Informs ExchangeService that an account has a new folder list; as a result, any existing
     * folder might have become invalid.  Therefore, we act as if the account has been deleted, and
     * then we reinitialize it.
     *
     * @param acctId
     */
    static public void stopNonAccountMailboxSyncsForAccount(long acctId) {
        SyncManager exchangeService = INSTANCE;
        if (exchangeService != null) {
            exchangeService.stopAccountSyncs(acctId, false);
            kick("reload folder list");
        }
    }

    /**
     * Start up the ExchangeService service if it's not already running
     * This is a stopgap for cases in which ExchangeService died (due to a crash somewhere in
     * com.android.email) and hasn't been restarted. See the comment for onCreate for details
     */
    static void checkExchangeServiceServiceRunning() {
        SyncManager exchangeService = INSTANCE;
        if (exchangeService == null) return;
        if (sServiceThread == null) {
            log("!!! checkExchangeServiceServiceRunning; starting service...");
            exchangeService.startService(new Intent(exchangeService, ExchangeService.class));
        }
    }

    @Override
    public AccountObserver getAccountObserver(
            Handler handler) {
        return new AccountObserver(handler) {
            @Override
            public void newAccount(long acctId) {
                Account acct = Account.restoreAccountWithId(getContext(), acctId);
                Mailbox main = new Mailbox();
                main.mDisplayName = Eas.ACCOUNT_MAILBOX_PREFIX;
                main.mServerId = Eas.ACCOUNT_MAILBOX_PREFIX + System.nanoTime();
                main.mAccountKey = acct.mId;
                main.mType = Mailbox.TYPE_EAS_ACCOUNT_MAILBOX;
                main.mSyncInterval = Mailbox.CHECK_INTERVAL_PUSH;
                main.mFlagVisible = false;
                main.save(getContext());
                log("Initializing account: " + acct.mDisplayName);
            }
        };
    }

    @Override
    public void onStartup() {
        // Do any required work to clean up our Mailboxes (this serves to upgrade
        // mailboxes that existed prior to EmailProvider database version 17)
        MailboxUtilities.fixupUninitializedParentKeys(this, getAccountsSelector());
    }

    @Override
    public AbstractSyncService getServiceForMailbox(Context context,
            Mailbox m) {
        switch(m.mType) {
            case Mailbox.TYPE_EAS_ACCOUNT_MAILBOX:
                return new EasAccountService(context, m);
            case Mailbox.TYPE_OUTBOX:
                return new EasOutboxService(context, m);
            default:
                return new EasSyncService(context, m);
        }
    }
    
    @Override
    public String getAccountsSelector() {
        if (mEasAccountSelector == null) {
            StringBuilder sb = new StringBuilder(ACCOUNT_KEY_IN);
            boolean first = true;
            synchronized (mAccountList) {
                for (Account account : mAccountList) {
                    if (!first) {
                        sb.append(',');
                    } else {
                        first = false;
                    }
                    sb.append(account.mId);
                }
            }
            sb.append(')');
            mEasAccountSelector = sb.toString();
        }
        return mEasAccountSelector;
    }

    @Override
    public String getAccountManagerType() {
        return Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE;
    }

    @Override
    public String getServiceIntentAction() {
        return Eas.EXCHANGE_SERVICE_INTENT_ACTION;
    }

    @Override
    public Stub getCallbackProxy() {
        return sCallbackProxy;
    }
}
