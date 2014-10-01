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

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract.Groups;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.Settings;
import android.util.Log;

import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.exchange.Eas;
import com.android.mail.utils.LogUtils;

public class ContactsSyncAdapterService extends AbstractSyncAdapterService {
    private static final String TAG = Eas.LOG_TAG;
    private static final String ACCOUNT_AND_TYPE_CONTACTS =
        MailboxColumns.ACCOUNT_KEY + "=? AND " + MailboxColumns.TYPE + '=' + Mailbox.TYPE_CONTACTS;

    private static final Object sSyncAdapterLock = new Object();
    private static AbstractThreadedSyncAdapter sSyncAdapter = null;

    public ContactsSyncAdapterService() {
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
            if (LogUtils.isLoggable(TAG, Log.DEBUG)) {
                LogUtils.d(TAG, "onPerformSync Contacts starting %s, %s", account.toString(),
                        extras.toString());
            } else {
                LogUtils.i(TAG, "onPerformSync Contacts starting %s", extras.toString());
            }
            ContactsSyncAdapterService.performSync(getContext(), account, extras);
            ContactsSyncAdapterService.maybeSetAccountContactsVisibility(getContext(), account);
            LogUtils.d(TAG, "onPerformSync Contacts finished");
        }
    }

    private static void maybeSetAccountContactsVisibility(Context context, Account account) {
        String[] projection = new String[]{
                Settings.ACCOUNT_NAME,
                Settings.ACCOUNT_TYPE
        };
        String selection = Settings.ACCOUNT_NAME + "=? AND " +
                Settings.ACCOUNT_TYPE + "=?";
        String[] selectionArgs = new String[]{
                account.name,
                account.type
        };
        ContentResolver resolver = context.getContentResolver();
        Cursor c = resolver.query(Settings.CONTENT_URI,
                projection, selection, selectionArgs, null);
        if (c != null) {
            if (c.getCount() == 0) {
                LogUtils.d(TAG, "setting account visibility true for account: " + account.name);
                setAccountContactsVisibility(context, account, true);
            }
            c.close();
        }
    }

    private static void setAccountContactsVisibility(Context context, Account account,
                                                     boolean visible) {
        ContentValues values = new ContentValues();
        values.put(RawContacts.ACCOUNT_NAME, account.name);
        values.put(RawContacts.ACCOUNT_TYPE, account.type);
        values.put(Settings.UNGROUPED_VISIBLE, visible ? 1 : 0);

        context.getContentResolver().insert(Settings.CONTENT_URI, values);
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
    private static void performSync(Context context, Account account, Bundle extras) {
        if (extras.getBoolean(Mailbox.SYNC_EXTRA_NOOP, false)) {
            LogUtils.d(TAG, "No-op sync requested, done");
            return;
        }
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
                LogUtils.d(TAG, "Upload sync; no changes");
                return;
            }
        }

        // Forward the sync request to the EmailSyncAdapterService.
        long [] mailboxIds = Mailbox.getMailboxIdsFromBundle(extras);
        final Bundle mailExtras;
        if (mailboxIds == null) {
            // We weren't given any particular mailboxId, specify a sync for all contacts.
            mailExtras = new Bundle();
            mailExtras.putInt(Mailbox.SYNC_EXTRA_MAILBOX_TYPE, Mailbox.TYPE_CONTACTS);
        } else {
            // Otherwise, add all of the mailboxes specified in the original sync extras.
            mailExtras = Mailbox.createSyncBundle(mailboxIds);
        }
        mailExtras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        mailExtras.putBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, true);
        if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false)) {
            mailExtras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        }
        ContentResolver.requestSync(account, EmailContent.AUTHORITY, mailExtras);
        LogUtils.d(TAG, "requestSync ContactsSyncAdapter %s", mailExtras.toString());
    }
}
