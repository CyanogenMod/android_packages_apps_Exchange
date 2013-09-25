package com.android.exchange.service;

import android.content.Context;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;


/**
 * Performs an Exchange Account sync, which includes folder sync.
 */
public class EasAccountSyncHandler extends EasAccountValidator {
    public EasAccountSyncHandler(final Context context, final Account account) {
        super(context, account, HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv));
    }

    public void performSync() {
        doValidationOrSync(null);
    }
}
