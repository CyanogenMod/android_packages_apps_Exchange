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

package com.android.exchange.service;

import android.content.Context;
import android.os.AsyncTask;

import com.android.emailcommon.provider.Account;
import com.android.exchange.adapter.PingParser;
import com.android.exchange.eas.EasPing;

/**
 * Thread management class for Ping operations.
 */
public class PingTask extends AsyncTask<Void, Void, Void> {
    private final EasPing mOperation;
    private final EmailSyncAdapterService.SyncHandlerSynchronizer mSyncHandlerMap;

    public PingTask(final Context context, final Account account,
            final EmailSyncAdapterService.SyncHandlerSynchronizer syncHandlerMap) {
        mOperation = new EasPing(context, account);
        mSyncHandlerMap = syncHandlerMap;
    }

    /** Start the ping loop. */
    public void start() {
        executeOnExecutor(THREAD_POOL_EXECUTOR);
    }

    /** Abort the ping loop (used when another operation interrupts the ping). */
    public void stop() {
        mOperation.abort();
    }

    /** Restart the ping loop (used when a ping request happens during a ping). */
    public void restart() {
        mOperation.restart();
    }

    @Override
    protected Void doInBackground(Void... params) {
        int pingStatus;
        do {
            pingStatus = mOperation.doPing();
        } while (PingParser.shouldPingAgain(pingStatus));

        mSyncHandlerMap.pingComplete(mOperation.getAmAccount(), mOperation.getAccountId(),
                pingStatus);
        return null;
    }
}
