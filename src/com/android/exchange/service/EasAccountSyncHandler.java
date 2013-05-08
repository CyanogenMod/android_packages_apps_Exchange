package com.android.exchange.service;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SyncResult;
import android.os.Bundle;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.HostAuthColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.exchange.CommandStatusException;
import com.android.exchange.CommandStatusException.CommandStatus;
import com.android.exchange.EasResponse;
import com.android.exchange.adapter.FolderSyncParser;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;

import org.apache.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;

/**
 * Performs an Exchange Account sync, which includes folder sync.
 */
public class EasAccountSyncHandler extends EasSyncHandler {


    public EasAccountSyncHandler(final Context context, final ContentResolver contentResolver,
            final Account account, final Mailbox mailbox, final Bundle syncExtras,
            final SyncResult syncResult) {
        super(context, contentResolver, account, mailbox, syncExtras, syncResult);
    }

    @Override
    public SyncStatus performSync() {
        boolean needsResync;
        do {
            needsResync = false;
            final EasResponse resp;
            try {
                final Serializer s = new Serializer();
                s.start(Tags.FOLDER_FOLDER_SYNC).start(Tags.FOLDER_SYNC_KEY)
                    .text(mAccount.mSyncKey).end().end().done();
                resp = sendHttpClientPost("FolderSync", s.toByteArray());
            } catch (final IOException e) {
                return SyncStatus.FAILURE_IO;
            }
            try {
                final int code = resp.getStatus();
                if (code == HttpStatus.SC_OK) {
                    if (!resp.isEmpty()) {
                        final InputStream is = resp.getInputStream();
                        try {
                            // Returns true if we need to sync again
                            // TODO: FolderSyncParser needs to be cleaned up to remove dependency
                            // on AbstractSyncAdapter.
                            if (new FolderSyncParser(mContext, mContentResolver, is, mAccount,
                                    mMailbox).parse()) {
                                needsResync = true;
                            }
                        } catch (final IOException e) {
                            return SyncStatus.FAILURE_IO;
                        } catch (final CommandStatusException e) {
                            final int status = e.mStatus;
                            if (CommandStatus.isNeedsProvisioning(status)) {
                                // TODO: Attempt provisioning.
                                return SyncStatus.FAILURE_SECURITY;
                            }
                            return SyncStatus.FAILURE_OTHER;
                        }
                    }
                } else if (EasResponse.isProvisionError(code)) {
                    // TODO: Attempt provisioning.
                    return SyncStatus.FAILURE_SECURITY;
                } else if (EasResponse.isAuthError(code)) {
                    return SyncStatus.FAILURE_LOGIN;
                } else if (EasResponse.isRedirectError(code)) {
                    if (redirectHostAuth(resp.getRedirectAddress())) {
                        needsResync = true;
                    } else {
                        // TODO: Perhaps a more severe error here?
                        return SyncStatus.FAILURE_LOGIN;
                    }
                }
            } finally {
                resp.close();
            }
        } while (needsResync);

        return SyncStatus.SUCCESS;
    }

    /**
     * Reset our local HostAuth's address and save the change to the DB.
     * @param newAddress The new address for the HostAuth.
     * @return Whether the redirect was processed.
     */
    private boolean redirectHostAuth(final String newAddress) {
        if (newAddress != null) {
            mHostAuth.mAddress = newAddress;
            final ContentValues haValues = new ContentValues(1);
            haValues.put(HostAuthColumns.ADDRESS, newAddress);
            mHostAuth.update(mContext, haValues);
            // TODO: When we start caching connections, make sure to uncache here.
        }
        return newAddress != null;
    }

}
