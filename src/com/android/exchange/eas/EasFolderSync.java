/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.exchange.eas;

import android.content.Context;
import android.content.SyncResult;
import android.os.Bundle;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.exchange.CommandStatusException;
import com.android.exchange.EasResponse;
import com.android.exchange.adapter.FolderSyncParser;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;
import com.android.mail.utils.LogUtils;

import org.apache.http.HttpEntity;

import java.io.IOException;

/**
 * Implements the EAS FolderSync command. We use this both to actually do a folder sync, and also
 * during account adding flow as a convenient command to validate the account settings (e.g. since
 * it needs to login and will tell us about provisioning requirements).
 * TODO: Doing validation here is kind of wonky. There must be a better way.
 *
 * See http://msdn.microsoft.com/en-us/library/ee237648(v=exchg.80).aspx for more details.
 */
public class EasFolderSync extends EasOperation {

    /** Result code indicating the sync completed correctly. */
    public static final int RESULT_OK = 1;
    /**
     * Result code indicating that this object was constructed for sync and was asked to validate,
     * or vice versa.
     */
    public static final int RESULT_WRONG_OPERATION = 2;

    // TODO: Eliminate the need for mAccount (requires FolderSyncParser changes).
    private final Account mAccount;

    /** Indicates whether this object is for validation rather than sync. */
    private final boolean mStatusOnly;

    /**
     * Constructor for actually doing folder sync.
     * @param context
     * @param account
     */
    public EasFolderSync(final Context context, final Account account) {
        super(context, account);
        mAccount = account;
        mStatusOnly = false;
    }

    /**
     * Constructor for account validation.
     * @param context
     * @param hostAuth
     */
    public EasFolderSync(final Context context, final HostAuth hostAuth) {
        this(context, new Account(), hostAuth);
    }

    private EasFolderSync(final Context context, final Account account, final HostAuth hostAuth) {
        super(context, account, hostAuth);
        mAccount = account;
        mAccount.mEmailAddress = hostAuth.mLogin;
        mStatusOnly = true;
    }

    /**
     * Perform a folder sync.
     * @param syncResult The {@link SyncResult} object for this sync operation.
     * @return A result code, either from above or from the base class.
     */
    public int doFolderSync(final SyncResult syncResult) {
        if (mStatusOnly) {
            return RESULT_WRONG_OPERATION;
        }
        LogUtils.i(LOG_TAG, "Performing sync for account %d", mAccount.mId);
        return performOperation(syncResult);
    }

    /**
     * Perform account validation.
     * TODO: Implement correctly.
     * @param bundle The {@link Bundle} to provide the results of validation to the UI.
     * @return A result code, either from above or from the base class.
     */
    public int validate(final Bundle bundle) {
        if (!mStatusOnly || bundle == null) {
            return RESULT_WRONG_OPERATION;
        }
        LogUtils.i(LOG_TAG, "Performing validation");
        final int result = performOperation(null);
        return RESULT_OK;
    }

    @Override
    protected String getCommand() {
        return "FolderSync";
    }

    @Override
    protected HttpEntity getRequestEntity() throws IOException {
        final String syncKey = mAccount.mSyncKey != null ? mAccount.mSyncKey : "0";
        final Serializer s = new Serializer();
        s.start(Tags.FOLDER_FOLDER_SYNC).start(Tags.FOLDER_SYNC_KEY).text(syncKey)
            .end().end().done();
        return makeEntity(s);
    }

    @Override
    protected int handleResponse(final EasResponse response, final SyncResult syncResult)
            throws IOException {
        if (!response.isEmpty()) {
            try {
                new FolderSyncParser(mContext, mContext.getContentResolver(),
                        response.getInputStream(), mAccount, mStatusOnly).parse();
            } catch (final CommandStatusException e) {
                final int status = e.mStatus;
                if (CommandStatusException.CommandStatus.isNeedsProvisioning(status)) {
                    return RESULT_PROVISIONING_ERROR;
                }
                if (CommandStatusException.CommandStatus.isDeniedAccess(status)) {
                    return RESULT_FORBIDDEN;
                }
                return RESULT_OTHER_FAILURE;
            }
        }

        return RESULT_OK;
    }

    @Override
    protected boolean handleForbidden() {
        return mStatusOnly;
    }
}
