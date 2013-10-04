// Copyright 2013 Google Inc. All Rights Reserved.

package com.android.exchange;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.Mailbox;
import com.android.exchange.R.string;
import com.android.mail.utils.LogUtils;

public class ExchangeBroadcastReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final Account[] accounts = AccountManager.get(context)
                .getAccountsByType(context.getString(string.account_manager_type_exchange));
        LogUtils.i(Eas.LOG_TAG, "Accounts changed - requesting account sync for all accounts");
        for (Account account : accounts) {
            final Bundle bundle = new Bundle();
            bundle.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true);
            bundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
            bundle.putLong(
                    Mailbox.SYNC_EXTRA_MAILBOX_ID, Mailbox.SYNC_EXTRA_MAILBOX_ID_ACCOUNT_ONLY);
            ContentResolver.requestSync(account, EmailContent.AUTHORITY, bundle);
        }
    }
}
