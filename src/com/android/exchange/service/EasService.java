/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SyncResult;
import android.database.Cursor;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.CalendarContract;
import android.provider.ContactsContract;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.SearchParams;
import com.android.exchange.Eas;
import com.android.exchange.eas.EasFolderSync;
import com.android.exchange.eas.EasOperation;
import com.android.mail.utils.LogUtils;

import java.util.HashSet;
import java.util.Set;

/**
 * Service to handle all communication with the EAS server. Note that this is completely decoupled
 * from the sync adapters; sync adapters should make blocking calls on this service to actually
 * perform any operations.
 */
public class EasService extends Service {

    private static final String TAG = Eas.LOG_TAG;

    /**
     * The content authorities that can be synced for EAS accounts. Initialization must wait until
     * after we have a chance to call {@link EmailContent#init} (and, for future content types,
     * possibly other initializations) because that's how we can know what the email authority is.
     */
    private static String[] AUTHORITIES_TO_SYNC;

    private final PingSyncSynchronizer mSynchronizer;

    private final IEmailService.Stub mBinder = new IEmailService.Stub() {
        @Override
        public void sendMail(final long accountId) {}

        @Override
        public void loadAttachment(final IEmailServiceCallback callback, final long attachmentId,
                final boolean background) {
            LogUtils.d(TAG, "IEmailService.loadAttachment: %d", attachmentId);
        }

        @Override
        public void updateFolderList(final long accountId) {
            final EasFolderSync operation = new EasFolderSync(EasService.this, accountId);
            doOperation(operation, null, "IEmailService.updateFolderList");
        }

        @Override
        public Bundle validate(final HostAuth hostAuth) {
            final EasFolderSync operation = new EasFolderSync(EasService.this, hostAuth);
            doOperation(operation, null, "IEmailService.validate");
            return operation.getValidationResult();
        }

        @Override
        public int searchMessages(final long accountId, final SearchParams searchParams,
                final long destMailboxId) {
            LogUtils.d(TAG, "IEmailService.searchMessages");
            return 0;
        }

        @Override
        public void sendMeetingResponse(final long messageId, final int response) {
            LogUtils.d(TAG, "IEmailService.sendMeetingResponse: %d, %d", messageId, response);
        }

        @Override
        public Bundle autoDiscover(final String username, final String password) {
            LogUtils.d(TAG, "IEmailService.autoDiscover");
            return null;
        }

        @Override
        public void setLogging(final int flags) {
            LogUtils.d(TAG, "IEmailService.setLogging");
        }

        @Override
        public void deleteAccountPIMData(final String emailAddress) {
            LogUtils.d(TAG, "IEmailService.deleteAccountPIMData");
        }
    };

    public EasService() {
        super();
        mSynchronizer = new PingSyncSynchronizer(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EmailContent.init(this);
        AUTHORITIES_TO_SYNC = new String[] {
                EmailContent.AUTHORITY,
                CalendarContract.AUTHORITY,
                ContactsContract.AUTHORITY
        };
        // TODO: Restart all pings that are needed.
    }

    @Override
    public void onDestroy() {
        // TODO: Stop all running pings.
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return mBinder;
    }

    public int doOperation(final EasOperation operation, final SyncResult syncResult,
            final String loggingName) {
        final long accountId = operation.getAccountId();
        LogUtils.d(TAG, "%s: %d", loggingName, accountId);
        mSynchronizer.syncStart(accountId);
        // TODO: Do we need a wakelock here? For RPC coming from sync adapters, no -- the SA
        // already has one. But for others, maybe? Not sure what's guaranteed for AIDL calls.
        // If we add a wakelock (or anything else for that matter) here, must remember to undo
        // it in the finally block below.
        // On the other hand, even for SAs, it doesn't hurt to get a wakelock here.
        try {
            return operation.performOperation(syncResult);
        } finally {
            mSynchronizer.syncEnd(accountId);
        }
    }

    /**
     * Determine whether this account is configured with folders that are ready for push
     * notifications.
     * @param account The {@link Account} that we're interested in.
     * @return Whether this account needs to ping.
     */
    public boolean pingNeededForAccount(final Account account) {
        // Check account existence.
        if (account == null || account.mId == Account.NO_ACCOUNT) {
            LogUtils.d(TAG, "Do not ping: Account not found or not valid");
            return false;
        }

        // Check if account is configured for a push sync interval.
        if (account.mSyncInterval != Account.CHECK_INTERVAL_PUSH) {
            LogUtils.d(TAG, "Do not ping: Account %d not configured for push", account.mId);
            return false;
        }

        // Check security hold status of the account.
        if ((account.mFlags & Account.FLAGS_SECURITY_HOLD) != 0) {
            LogUtils.d(TAG, "Do not ping: Account %d is on security hold", account.mId);
            return false;
        }

        // Check if the account has performed at least one sync so far (accounts must perform
        // the initial sync before push is possible).
        if (EmailContent.isInitialSyncKey(account.mSyncKey)) {
            LogUtils.d(TAG, "Do not ping: Account %d has not done initial sync", account.mId);
            return false;
        }

        // Check that there's at least one mailbox that is both configured for push notifications,
        // and whose content type is enabled for sync in the account manager.
        final android.accounts.Account amAccount = new android.accounts.Account(
                        account.mEmailAddress, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE);

        final Set<String> authsToSync = getAuthoritiesToSync(amAccount, AUTHORITIES_TO_SYNC);
        // If we have at least one sync-enabled content type, check for syncing mailboxes.
        if (!authsToSync.isEmpty()) {
            final Cursor c = Mailbox.getMailboxesForPush(getContentResolver(), account.mId);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        final int mailboxType = c.getInt(Mailbox.CONTENT_TYPE_COLUMN);
                        if (authsToSync.contains(Mailbox.getAuthority(mailboxType))) {
                            return true;
                        }
                    }
                } finally {
                    c.close();
                }
            }
        }
        LogUtils.d(TAG, "Do not ping: Account %d has no folders configured for push", account.mId);
        return false;
    }

    /**
     * Determine which content types are set to sync for an account.
     * @param account The account whose sync settings we're looking for.
     * @param authorities All possible authorities we could care about.
     * @return The authorities for the content types we want to sync for account.
     */
    private static Set<String> getAuthoritiesToSync(final android.accounts.Account account,
            final String[] authorities) {
        final HashSet<String> authsToSync = new HashSet();
        for (final String authority : authorities) {
            if (ContentResolver.getSyncAutomatically(account, authority)) {
                authsToSync.add(authority);
            }
        }
        return authsToSync;
    }
}
