package com.android.exchange.service;

import android.content.Context;

import com.android.emailcommon.provider.Account;


/**
 * Performs an Exchange Account sync, which includes folder sync.
 */
public class EasAccountSyncHandler extends EasAccountValidator {
    public EasAccountSyncHandler(final Context context, final Account account) {
        super(context, account);
    }

    public void performSync() {
        doValidationOrSync(null);
    }
}
