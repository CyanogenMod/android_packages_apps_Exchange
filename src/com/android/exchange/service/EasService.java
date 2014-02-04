/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.app.Service;
import android.content.Intent;
import android.content.SyncResult;
import android.os.Bundle;
import android.os.IBinder;

import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.SearchParams;
import com.android.exchange.Eas;
import com.android.exchange.eas.EasFolderSync;
import com.android.exchange.eas.EasOperation;
import com.android.mail.utils.LogUtils;

/**
 * Service to handle all communication with the EAS server. Note that this is completely decoupled
 * from the sync adapters; sync adapters should make blocking calls on this service to actually
 * perform any operations.
 */
public class EasService extends Service {

    private static final String TAG = Eas.LOG_TAG;

    private final PingSyncSynchronizer mSynchronizer;

    private final IEmailService.Stub mBinder = new IEmailService.Stub() {
        @Override
        public void sendMail(final long accountId) {}

        @Override
        public void loadAttachment(final IEmailServiceCallback callback, final long attachmentId,
                final boolean background) {
            LogUtils.d(TAG, "IEmailService.loadAttachment: %d", attachmentId);
        }

        @Override
        public void updateFolderList(final long accountId) {
            final EasFolderSync operation = new EasFolderSync(EasService.this, accountId);
            doOperation(operation, null, "IEmailService.updateFolderList");
        }

        @Override
        public Bundle validate(final HostAuth hostAuth) {
            final EasFolderSync operation = new EasFolderSync(EasService.this, hostAuth);
            doOperation(operation, null, "IEmailService.validate");
            return operation.getValidationResult();
        }

        @Override
        public int searchMessages(final long accountId, final SearchParams searchParams,
                final long destMailboxId) {
            LogUtils.d(TAG, "IEmailService.searchMessages");
            return 0;
        }

        @Override
        public void sendMeetingResponse(final long messageId, final int response) {
            LogUtils.d(TAG, "IEmailService.sendMeetingResponse: %d, %d", messageId, response);
        }

        @Override
        public Bundle autoDiscover(final String username, final String password) {
            LogUtils.d(TAG, "IEmailService.autoDiscover");
            return null;
        }

        @Override
        public void setLogging(final int flags) {
            LogUtils.d(TAG, "IEmailService.setLogging");
        }

        @Override
        public void deleteAccountPIMData(final String emailAddress) {
            LogUtils.d(TAG, "IEmailService.deleteAccountPIMData");
        }
    };

    public EasService() {
        super();
        mSynchronizer = new PingSyncSynchronizer(this);
    }

    @Override
    public void onCreate() {
        // TODO: Restart all pings that are needed.
    }

    @Override
    public void onDestroy() {
        // TODO: Stop all running pings.
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return mBinder;
    }

    public int doOperation(final EasOperation operation, final SyncResult syncResult,
            final String loggingName) {
        final long accountId = operation.getAccountId();
        LogUtils.d(TAG, "%s: %d", loggingName, accountId);
        mSynchronizer.syncStart(accountId);
        // TODO: Do we need a wakelock here? For RPC coming from sync adapters, no -- the SA
        // already has one. But for others, maybe? Not sure what's guaranteed for AIDL calls.
        // If we add a wakelock (or anything else for that matter) here, must remember to undo
        // it in the finally block below.
        // On the other hand, even for SAs, it doesn't hurt to get a wakelock here.
        try {
            return operation.performOperation(syncResult);
        } finally {
            // TODO: Fix pushEnabled param
            mSynchronizer.syncEnd(accountId, false);
        }
    }
}
