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
import android.text.format.DateUtils;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;

import org.apache.http.HttpEntity;

import java.io.IOException;

/**
 * Performs an Exchange Sync operation for one {@link Mailbox}.
 * TODO: Allow multiple mailboxes in one sync.
 */
public class EasSync extends EasOperation {

    protected final Mailbox mMailbox;
    protected boolean mInitialSync;

    public EasSync(final Context context, final Account account, final Mailbox mailbox) {
        super(context, account);
        mMailbox = mailbox;
        mInitialSync = false;
    }

    public static final int SYNC_RESULT_SUCCESS = 0;
    public static final int SYNC_RESULT_MORE_AVAILABLE = 1;

    @Override
    protected String getCommand() {
        return "Sync";
    }

    @Override
    protected HttpEntity getRequestEntity() throws IOException {
        return null;
    }


    @Override
    protected int handleResponse(final EasResponse response, final SyncResult syncResult)
            throws IOException {
        return 0;
    }

    @Override
    protected long getTimeout() {
        if (mInitialSync) {
            return 120 * DateUtils.SECOND_IN_MILLIS;
        }
        return super.getTimeout();
    }

    private final void addOneCollectionToRequest(final Serializer s, final Mailbox mailbox)
            throws IOException {
        s.start(Tags.SYNC_COLLECTION);
        if (getProtocolVersion() < Eas.SUPPORTED_PROTOCOL_EX2007_SP1_DOUBLE) {
            s.data(Tags.SYNC_CLASS, Eas.getFolderClass(mailbox.mType));
        }
        s.end();
    }

    private final String getSyncKeyForMailbox(final Mailbox mailbox) {
        switch (mailbox.mType) {

        }
        return null;
    }
}
