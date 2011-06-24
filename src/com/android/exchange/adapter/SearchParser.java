/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.exchange.adapter;

import android.content.ContentProviderOperation;
import android.content.OperationApplicationException;
import android.os.RemoteException;
import android.util.Log;

import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.utility.TextUtilities;
import com.android.exchange.EasSyncService;
import com.android.exchange.adapter.EmailSyncAdapter.EasEmailSyncParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Parse the result of a Search command
 */
public class SearchParser extends Parser {
    private final EasSyncService mService;
    private final String mQuery;

    public SearchParser(InputStream in, EasSyncService service, String query) throws IOException {
        super(in);
        mService = service;
        mQuery = query;
    }

    @Override
    public boolean parse() throws IOException {
        boolean res = false;
        if (nextTag(START_DOCUMENT) != Tags.SEARCH_SEARCH) {
            throw new IOException();
        }
        while (nextTag(START_DOCUMENT) != END_DOCUMENT) {
            if (tag == Tags.SEARCH_STATUS) {
                Log.d(Logging.LOG_TAG, "Search status: " + getValue());
            } else if (tag == Tags.SEARCH_RESPONSE) {
                parseResponse();
            } else {
                skipTag();
            }
        }
        return res;
    }

    public boolean parseResponse() throws IOException {
        boolean res = false;
        while (nextTag(Tags.SEARCH_RESPONSE) != END) {
            if (tag == Tags.SEARCH_STORE) {
                parseStore();
            } else {
                skipTag();
            }
        }
        return res;
    }

    public boolean parseStore() throws IOException {
        EmailSyncAdapter adapter = new EmailSyncAdapter(mService);
        EasEmailSyncParser parser = adapter.new EasEmailSyncParser(this, adapter);
        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
        boolean res = false;

        while (nextTag(Tags.SEARCH_STORE) != END) {
            if (tag == Tags.SEARCH_STATUS) {
                Log.d(Logging.LOG_TAG, "Store status: " + getValue());
            } else if (tag == Tags.SEARCH_RESULT) {
                parseResult(parser, ops);
            } else {
                skipTag();
            }
        }

        try {
            adapter.mContentResolver.applyBatch(EmailContent.AUTHORITY, ops);
            mService.userLog("Saved " + ops.size() + " search results");
        } catch (RemoteException e) {
            Log.d(Logging.LOG_TAG, "RemoteException while saving search results.");
        } catch (OperationApplicationException e) {
        }

        return res;
    }

    public boolean parseResult(EasEmailSyncParser parser, ArrayList<ContentProviderOperation> ops)
            throws IOException {
        // Get an email sync parser for our incoming message data
        boolean res = false;
        Message msg = new Message();
        while (nextTag(Tags.SEARCH_RESULT) != END) {
            if (tag == Tags.SYNC_CLASS) {
                Log.d(Logging.LOG_TAG, "Result class: " + getValue());
            } else if (tag == Tags.SYNC_COLLECTION_ID) {
                Log.d(Logging.LOG_TAG, "Result collectionId: " + getValue());
            } else if (tag == Tags.SEARCH_LONG_ID) {
                msg.mProtocolSearchInfo = getValue();
            } else if (tag == Tags.SEARCH_PROPERTIES) {
                msg.mAccountKey = mService.mAccount.mId;
                msg.mMailboxKey = mService.mMailbox.mId;
                msg.mFlagLoaded = Message.FLAG_LOADED_COMPLETE;
                parser.pushTag(tag);
                parser.addData(msg, tag);
                if (msg.mHtml != null) {
                    msg.mHtml = TextUtilities.highlightTermsInHtml(msg.mHtml, mQuery);
                }
                msg.addSaveOps(ops);
            } else {
                skipTag();
            }
        }
        return res;
    }
}

