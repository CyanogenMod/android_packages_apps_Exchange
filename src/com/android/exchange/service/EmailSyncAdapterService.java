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

import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.ServiceProxy;
import com.android.emailcommon.utility.IntentUtilities;
import com.android.exchange.Eas;
import com.android.exchange.R;
import com.android.exchange.eas.EasFolderSync;
import com.android.exchange.eas.EasMoveItems;
import com.android.exchange.eas.EasOperation;
import com.android.exchange.eas.EasPing;
import com.android.exchange.eas.EasSync;
import com.android.mail.utils.LogUtils;

/**
 * TODO: Most of this is deprecated.
 * This is needed to handle syncs, but pings and IEmailService functionality will be in EasService.
 * Also, we need to refactor sync functionality into some common class so that it can be used by
 * this, and ContactsSyncAdapterService and CalendarSyncAdapterService.
 *
 * Service for communicating with Exchange servers. There are three main parts of this class:
 * TODO: Flesh out these comments.
 * 1) An {@link AbstractThreadedSyncAdapter} to handle actually performing syncs.
 * 2) Bookkeeping for running Ping requests, which handles push notifications.
 * 3) An {@link IEmailService} Stub to handle RPC from the UI.
 */
public class EmailSyncAdapterService extends AbstractSyncAdapterService {

    private static final String TAG = Eas.LOG_TAG;

    private IEmailService mEasService;
    private ServiceConnection mConnection;

    private static final String EXTRA_START_PING = "START_PING";
    private static final String EXTRA_PING_ACCOUNT = "PING_ACCOUNT";

    // TODO: Do we need to use this?
    private static final long SYNC_ERROR_BACKOFF_MILLIS = 5 * DateUtils.MINUTE_IN_MILLIS;

    /**
     * TODO: restore this functionality.
     * The amount of time between periodic syncs intended to ensure that push hasn't died.
     */
    private static final long KICK_SYNC_INTERVAL =
            DateUtils.HOUR_IN_MILLIS / DateUtils.SECOND_IN_MILLIS;
    /** Controls whether we do a periodic "kick" to restart the ping. */
    private static final boolean SCHEDULE_KICK = true;

    private static final Object sSyncAdapterLock = new Object();
    private static AbstractThreadedSyncAdapter sSyncAdapter = null;

    public EmailSyncAdapterService() {
        super();
    }

    /**
     * {@link AsyncTask} for restarting pings for all accounts that need it.
     */
    private static final String[] PUSH_ACCOUNTS_PROJECTION = new String[] {AccountColumns._ID};
    private static final String PUSH_ACCOUNTS_SELECTION =
            AccountColumns.SYNC_INTERVAL + "=" + Integer.toString(Account.CHECK_INTERVAL_PUSH);
    private class RestartPingsTask extends AsyncTask<Void, Void, Void> {

        private final ContentResolver mContentResolver;
        private final IEmailService mEasService;
        private boolean mAnyAccounts;

        public RestartPingsTask(final ContentResolver contentResolver,
                                final IEmailService easService) {
            mContentResolver = contentResolver;
            mEasService = easService;
        }

        @Override
        protected Void doInBackground(Void... params) {
            final Cursor c = mContentResolver.query(Account.CONTENT_URI,
                    PUSH_ACCOUNTS_PROJECTION, PUSH_ACCOUNTS_SELECTION, null, null);
            if (c != null) {
                try {
                    mAnyAccounts = (c.getCount() != 0);
                    while (c.moveToNext()) {
                        try {
                            mEasService.pushModify(c.getLong(0));
                        } catch (RemoteException re) {
                            LogUtils.wtf(TAG, re, "While trying to pushModify in RestartPingsTask");
                            // TODO: how to handle this?
                        }
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
        mConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name,  IBinder binder) {
                mEasService = IEmailService.Stub.asInterface(binder);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                mEasService = null;
            }
        };
        bindService(new Intent(this, EasService.class), mConnection, Context.BIND_AUTO_CREATE);
        // Start up the initial pings.
        new RestartPingsTask(getContentResolver(), mEasService).executeOnExecutor(
                AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    public void onDestroy() {
        LogUtils.v(TAG, "onDestroy()");
        super.onDestroy();
        unbindService(mConnection);
    }

    @Override
    public IBinder onBind(Intent intent) {
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
            // FLAG: Do we actually need to do this? I don't think the sync manager will invoke
            // a sync if we don't have good network.

            final Context context = getContext();
            final ContentResolver cr = context.getContentResolver();

            // Get the EmailContent Account
            // TODO shouldn't this functionality live in Account, not here?
            final Account account;
            final Cursor accountCursor = cr.query(Account.CONTENT_URI, Account.CONTENT_PROJECTION,
                    AccountColumns.EMAIL_ADDRESS + "=?", new String[] {acct.name}, null);
            try {
                if (accountCursor == null || !accountCursor.moveToFirst()) {
                    // Could not load account.
                    // TODO: improve error handling.
                    LogUtils.w(TAG, "onPerformSync: could not load account");
                    return;
                }
                account = new Account();
                account.restore(accountCursor);
            } finally {
                if (accountCursor != null) {
                    accountCursor.close();
                }
            }

            // Push only means this sync request should only refresh the ping (either because
            // settings changed, or we need to restart it for some reason).
            final boolean pushOnly = Mailbox.isPushOnlyExtras(extras);

            if (pushOnly) {
                LogUtils.d(TAG, "onPerformSync: mailbox push only");
                if (mEasService != null) {
                    try {
                        mEasService.pushModify(account.mId);
                        return;
                    } catch (final RemoteException re) {
                        LogUtils.e(TAG, re, "While trying to pushModify within onPerformSync");
                        // TODO: how to handle this?
                    }
                }
                return;
            } else {
                try {
                    final int result = mEasService.sync(account.mId, extras);
                    writeResultToSyncResult(result, syncResult);
                    if (syncResult.stats.numAuthExceptions > 0 &&
                            result != EmailServiceStatus.PROVISIONING_ERROR) {
                        showAuthNotification(account.mId, account.mEmailAddress);
                    }
                } catch (RemoteException e) {
                     LogUtils.e(TAG, e, "While trying to pushModify within onPerformSync");
                }
            }

            LogUtils.d(TAG, "onPerformSync: finished");
        }
    }

    private void showAuthNotification(long accountId, String accountName) {
        final PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                createAccountSettingsIntent(accountId, accountName),
                0);

        final Notification notification = new Builder(this)
                .setContentTitle(this.getString(R.string.auth_error_notification_title))
                .setContentText(this.getString(
                        R.string.auth_error_notification_text, accountName))
                .setSmallIcon(R.drawable.stat_notify_auth)
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
     * Interpret a result code from an {@link IEmailService.sync()} and, if it's an error, write it to
     * the appropriate field in {@link SyncResult}.
     * @param result
     * @param syncResult
     * @return Whether an error code was written to syncResult.
     */
    public static boolean writeResultToSyncResult(final int result, final SyncResult syncResult) {
        switch (result) {
            case EmailServiceStatus.SUCCESS:
                return false;

            case EmailServiceStatus.REMOTE_EXCEPTION:
            case EmailServiceStatus.LOGIN_FAILED:
            case EmailServiceStatus.SECURITY_FAILURE:
            case EmailServiceStatus.CLIENT_CERTIFICATE_ERROR:
            case EmailServiceStatus.ACCESS_DENIED:
                    syncResult.stats.numAuthExceptions = 1;
                return true;

            case EmailServiceStatus.HARD_DATA_ERROR:
            case EmailServiceStatus.INTERNAL_ERROR:
                syncResult.databaseError = true;
                return true;

            case EmailServiceStatus.CONNECTION_ERROR:
            case EmailServiceStatus.IO_ERROR:
                syncResult.stats.numIoExceptions = 1;
                return true;

            case EmailServiceStatus.TOO_MANY_REDIRECTS:
                syncResult.tooManyRetries = true;
                return true;

            case EmailServiceStatus.IN_PROGRESS:
            case EmailServiceStatus.MESSAGE_NOT_FOUND:
            case EmailServiceStatus.ATTACHMENT_NOT_FOUND:
            case EmailServiceStatus.FOLDER_NOT_DELETED:
            case EmailServiceStatus.FOLDER_NOT_RENAMED:
            case EmailServiceStatus.FOLDER_NOT_CREATED:
            case EmailServiceStatus.ACCOUNT_UNINITIALIZED:
            case EmailServiceStatus.PROTOCOL_ERROR:
                LogUtils.e(TAG, "Unexpected sync result %d", result);
                return false;
        }
        return false;
    }
}
