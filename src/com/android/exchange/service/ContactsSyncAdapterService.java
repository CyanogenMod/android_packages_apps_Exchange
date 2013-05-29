/*
 * Copyright (C) 2009 The Android Open Source Project
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
import com.android.exchange.Eas;
import com.android.exchange.ExchangeService;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract.Groups;
import android.provider.ContactsContract.RawContacts;
import android.util.Log;

public class ContactsSyncAdapterService extends AbstractSyncAdapterService {
    private static final String TAG = "EAS ContactsSyncAdapterService";
    private static final String ACCOUNT_AND_TYPE_CONTACTS =
        MailboxColumns.ACCOUNT_KEY + "=? AND " + MailboxColumns.TYPE + '=' + Mailbox.TYPE_CONTACTS;

    public ContactsSyncAdapterService() {
        super();
    }

    @Override
    protected AbstractThreadedSyncAdapter newSyncAdapter() {
        return new SyncAdapterImpl(this);
    }

    private static class SyncAdapterImpl extends AbstractThreadedSyncAdapter {
        public SyncAdapterImpl(Context context) {
            super(context, true /* autoInitialize */);
        }

        @Override
        public void onPerformSync(Account account, Bundle extras,
                String authority, ContentProviderClient provider, SyncResult syncResult) {
            ContactsSyncAdapterService.performSync(getContext(), account, extras, authority,
                    provider, syncResult);
        }
    }

    private static boolean hasDirtyRows(ContentResolver resolver, Uri uri, String dirtyColumn) {
        Cursor c = resolver.query(uri, EmailContent.ID_PROJECTION, dirtyColumn + "=1", null, null);
        try {
            return c.getCount() > 0;
        } finally {
            c.close();
        }
    }

    /**
     * Partial integration with system SyncManager; we tell our EAS ExchangeService to start a
     * contacts sync when we get the signal from SyncManager.
     * The missing piece at this point is integration with the push/ping mechanism in EAS; this will
     * be put in place at a later time.
     */
    private static void performSync(Context context, Account account, Bundle extras,
            String authority, ContentProviderClient provider, SyncResult syncResult) {
        ContentResolver cr = context.getContentResolver();

        // If we've been asked to do an upload, make sure we've got work to do
        if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD)) {
            Uri uri = RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
                .appendQueryParameter(RawContacts.ACCOUNT_TYPE, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE)
                .build();
            // See if we've got dirty contacts or dirty groups containing our contacts
            boolean changed = hasDirtyRows(cr, uri, RawContacts.DIRTY);
            if (!changed) {
                uri = Groups.CONTENT_URI.buildUpon()
                    .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
                    .appendQueryParameter(RawContacts.ACCOUNT_TYPE,
                            Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE)
                    .build();
                changed = hasDirtyRows(cr, uri, Groups.DIRTY);
            }
            if (!changed) {
                Log.i(TAG, "Upload sync; no changes");
                return;
            }
        }

        // Find the (EmailProvider) account associated with this email address
        final Cursor accountCursor =
            cr.query(com.android.emailcommon.provider.Account.CONTENT_URI,
                    com.android.emailcommon.provider.Account.ID_PROJECTION,
                    AccountColumns.EMAIL_ADDRESS + "=?",
                    new String[] {account.name}, null);
        if (accountCursor == null) {
            Log.e(TAG, "null account cursor in ContactsSyncAdapterService");
            return;
        }

        try {
            if (accountCursor.moveToFirst()) {
                final long accountId = accountCursor.getLong(
                        com.android.emailcommon.provider.Account.ID_PROJECTION_COLUMN);
                // Now, find the contacts mailbox associated with the account
                final Cursor mailboxCursor = cr.query(Mailbox.CONTENT_URI, Mailbox.ID_PROJECTION,
                        ACCOUNT_AND_TYPE_CONTACTS, new String[] {Long.toString(accountId)}, null);
                try {
                     if (mailboxCursor.moveToFirst()) {
                        Log.i(TAG, "Contact sync requested for " + account.name);
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
