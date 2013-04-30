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

import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailsync.AbstractSyncService;
import com.android.emailsync.SyncManager;
import com.android.exchange.EasSyncService;

import android.accounts.Account;
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

public class EmailSyncAdapterService extends AbstractSyncAdapterService {
    private static final String TAG = "EAS EmailSyncAdapterService";
    private static final String[] ID_PROJECTION = new String[] {EmailContent.RECORD_ID};
    private static final String ACCOUNT_AND_TYPE_INBOX =
        MailboxColumns.ACCOUNT_KEY + "=? AND " + MailboxColumns.TYPE + '=' + Mailbox.TYPE_INBOX;

    private SyncAdapterImpl mSyncAdapter = null;

    public EmailSyncAdapterService() {
        super();
    }

    private static class SyncAdapterImpl extends AbstractThreadedSyncAdapter {
        public SyncAdapterImpl(Context context) {
            super(context, true /* autoInitialize */);
        }

        @Override
        public void onPerformSync(Account account, Bundle extras,
                String authority, ContentProviderClient provider, SyncResult syncResult) {
            EmailSyncAdapterService.performSync(getContext(), account, extras, authority,
                    provider, syncResult);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mSyncAdapter = new SyncAdapterImpl(getApplicationContext());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mSyncAdapter.getSyncAdapterBinder();
    }

    /**
     * Partial integration with system SyncManager; we tell our EAS ExchangeService to start an
     * inbox sync when we get the signal from the system SyncManager.
     * This is the path for all syncs other than push.
     * TODO: Make push use this as well.
     */
    private static void performSync(Context context, Account account, Bundle extras,
            String authority, ContentProviderClient provider, SyncResult syncResult) {
        Log.i(TAG, "performSync");

        Mailbox mailbox = null;
        final long mailboxId = extras.getLong(Mailbox.SYNC_EXTRA_MAILBOX_ID, Mailbox.NO_MAILBOX);
        if (mailboxId != Mailbox.NO_MAILBOX) {
            mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
        } else {
            // No mailbox id means we should sync the inbox.
            // TODO: Or the account?
            // Find the (EmailProvider) account associated with this email address
            ContentResolver cr = context.getContentResolver();
            final Cursor accountCursor =
                    cr.query(com.android.emailcommon.provider.Account.CONTENT_URI, ID_PROJECTION,
                            AccountColumns.EMAIL_ADDRESS + "=?", new String[] {account.name}, null);
            try {
                if (accountCursor.moveToFirst()) {
                    final long accountId = accountCursor.getLong(0);
                    // Now, find the inbox associated with the account
                    final Cursor mailboxCursor = cr.query(Mailbox.CONTENT_URI, ID_PROJECTION,
                            ACCOUNT_AND_TYPE_INBOX, new String[] {Long.toString(accountId)}, null);
                    try {
                         if (mailboxCursor.moveToFirst()) {
                            Log.i(TAG, "Mail sync requested for " + account.name);
                            mailbox = Mailbox.restoreMailboxOfType(context, accountId,
                                    Mailbox.TYPE_INBOX);
                        }
                    } finally {
                        mailboxCursor.close();
                    }
                }
            } finally {
                accountCursor.close();
            }
        }
        if (mailbox == null) {
            // TODO: Better error handling?
            return;
        }
        // TODO: This currently excludes Outbox, but that's still being included in the push
        // mailbox loop (SyncManager.checkMailboxes). So this works for now, but eventually it
        // won't.
        if (!SyncManager.isSyncable(mailbox)) {
            // TODO: fix the UI callbacks.
            //sCallbackProxy.syncMailboxStatus(mailboxId, EmailServiceStatus.IN_PROGRESS, 0);
            //sCallbackProxy.syncMailboxStatus(mailboxId, EmailServiceStatus.SUCCESS, 0);
            return;
        }
        // TODO: Clean this up further.
        final AbstractSyncService service = EasSyncService.getServiceForMailbox(context, mailbox);
        service.mSyncReason = SyncManager.SYNC_KICK;
        service.run();
    }
}
