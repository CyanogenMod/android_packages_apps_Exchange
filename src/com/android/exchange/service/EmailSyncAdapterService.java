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

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceStatus;

import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;

public class EmailSyncAdapterService extends AbstractSyncAdapterService {
    public EmailSyncAdapterService() {
        super();
    }

    @Override
    protected AbstractThreadedSyncAdapter newSyncAdapter() {
        return new SyncAdapterImpl(this);
    }

    private static class SyncAdapterImpl extends AbstractThreadedSyncAdapter {
        private static final String TAG = "EAS EmailSyncAdapterService";

        public SyncAdapterImpl(Context context) {
            super(context, true /* autoInitialize */);
        }

        @Override
        public void onPerformSync(android.accounts.Account acct, Bundle extras,
                String authority, ContentProviderClient provider, SyncResult syncResult) {

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

            final long mailboxId = extras.getLong(Mailbox.SYNC_EXTRA_MAILBOX_ID,
                    Mailbox.NO_MAILBOX);
            if (mailboxId == Mailbox.NO_MAILBOX) {
                // If no mailbox is specified, this is an account sync. This means we should both
                // sync the account (to get folders, etc.) as well as the inbox.
                // TODO: Why does the "account mailbox" even exist?
                final Mailbox accountMailbox = Mailbox.restoreMailboxOfType(context, account.mId,
                        Mailbox.TYPE_EAS_ACCOUNT_MAILBOX);
                final EasSyncHandler accountSyncHandler = EasSyncHandler.getEasSyncHandler(
                        context, cr, account, accountMailbox, extras, syncResult);

                if (accountSyncHandler == null) {
                    // TODO: Account box does not exist, add proper error handling.
                } else {
                    accountSyncHandler.performSync();
                    final Mailbox inbox = Mailbox.restoreMailboxOfType(context, account.mId,
                            Mailbox.TYPE_INBOX);
                    final EasSyncHandler inboxSyncHandler = EasSyncHandler.getEasSyncHandler(
                            context, cr, account, accountMailbox, extras, syncResult);
                    if (inboxSyncHandler == null) {
                        // TODO: Inbox does not exist for this account, add proper error handling.
                    } else {
                        inboxSyncHandler.performSync();
                    }
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
            // TODO: It may make sense to have common error handling here. Two possible mechanisms:
            // 1) performSync return value can signal some useful info.
            // 2) syncResult can contain useful info.
        }
    }
}
