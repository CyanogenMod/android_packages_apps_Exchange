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

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.CalendarContract.Events;

import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.exchange.Eas;
import com.android.mail.utils.LogUtils;

public class CalendarSyncAdapterService extends AbstractSyncAdapterService {
    private static final String TAG = "EASCalSyncAdaptSvc";
    private static final String ACCOUNT_AND_TYPE_CALENDAR =
        MailboxColumns.ACCOUNT_KEY + "=? AND " + MailboxColumns.TYPE + '=' + Mailbox.TYPE_CALENDAR;
    private static final String DIRTY_IN_ACCOUNT =
        Events.DIRTY + "=1 AND " + Events.ACCOUNT_NAME + "=?";

    private static final Object sSyncAdapterLock = new Object();
    private static AbstractThreadedSyncAdapter sSyncAdapter = null;

    public CalendarSyncAdapterService() {
        super();
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

    private static class SyncAdapterImpl extends AbstractThreadedSyncAdapter {
        public SyncAdapterImpl(Context context) {
            super(context, true /* autoInitialize */);
        }

        @Override
        public void onPerformSync(Account account, Bundle extras,
                String authority, ContentProviderClient provider, SyncResult syncResult) {
            CalendarSyncAdapterService.performSync(getContext(), account, extras);
        }
    }

    /**
     * Partial integration with system SyncManager; we tell our EAS ExchangeService to start a
     * calendar sync when we get the signal from SyncManager.
     * The missing piece at this point is integration with the push/ping mechanism in EAS; this will
     * be put in place at a later time.
     */
    private static void performSync(Context context, Account account, Bundle extras) {
        final ContentResolver cr = context.getContentResolver();
        final boolean logging = Eas.USER_LOG;
        if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD)) {
            final Cursor c = cr.query(Events.CONTENT_URI,
                    new String[] {Events._ID}, DIRTY_IN_ACCOUNT, new String[] {account.name}, null);
            if (c == null) {
                LogUtils.e(TAG, "Null changes cursor in CalendarSyncAdapterService");
                return;
            }
            try {
                if (!c.moveToFirst()) {
                    if (logging) {
                        LogUtils.d(TAG, "No changes for " + account.name);
                    }
                    return;
                }
            } finally {
                c.close();
            }
        }

        // Find the (EmailProvider) account associated with this email address
        final Cursor accountCursor =
            cr.query(com.android.emailcommon.provider.Account.CONTENT_URI,
                    EmailContent.ID_PROJECTION, AccountColumns.EMAIL_ADDRESS + "=?",
                    new String[] {account.name}, null);
        if (accountCursor == null) {
            LogUtils.e(TAG, "Null account cursor in CalendarSyncAdapterService");
            return;
        }

        try {
            if (accountCursor.moveToFirst()) {
                final long accountId = accountCursor.getLong(0);
                // Now, find the calendar mailbox associated with the account
                final Cursor mailboxCursor = cr.query(Mailbox.CONTENT_URI, Mailbox.ID_PROJECTION,
                        ACCOUNT_AND_TYPE_CALENDAR, new String[] {Long.toString(accountId)}, null);
                try {
                    if (mailboxCursor.moveToFirst()) {
                        if (logging) {
                            LogUtils.d(TAG, "Upload sync requested for " + account.name);
                        }
                        // TODO: Currently just bouncing this to Email sync; eventually streamline.
                        final long mailboxId = mailboxCursor.getLong(Mailbox.ID_PROJECTION_COLUMN);
                        // TODO: Should we be using the existing extras and just adding our bits?
                        final Bundle mailboxExtras = new Bundle(4);
                        mailboxExtras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
                        mailboxExtras.putBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, true);
                        mailboxExtras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
                        mailboxExtras.putLong(Mailbox.SYNC_EXTRA_MAILBOX_ID, mailboxId);
                        ContentResolver.requestSync(account, EmailContent.AUTHORITY, mailboxExtras);
                    }
                } finally {
                    mailboxCursor.close();
                }
            }
        } finally {
            accountCursor.close();
        }
    }
}
