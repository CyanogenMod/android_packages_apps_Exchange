/* Copyright (C) 2011 The Android Open Source Project.
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

import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.AttachmentColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.exchange.Eas;
import com.android.exchange.EasSyncService;
import com.android.exchange.EasSyncService.EasResponse;
import com.android.exchange.ExchangeService;
import com.android.exchange.PartRequest;

import org.apache.http.HttpStatus;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.RemoteException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Handle EAS attachment loading, regardless of protocol version
 */
public class AttachmentLoader {
    static private final int CHUNK_SIZE = 16*1024;

    private final EasSyncService mService;
    private final Context mContext;
    private final ContentResolver mResolver;
    private final Attachment mAttachment;
    private final long mAttachmentId;
    private final int mAttachmentSize;
    private final long mMessageId;
    private final Message mMessage;
    private final long mAccountId;
    private final Uri mAttachmentUri;

    public AttachmentLoader(EasSyncService service, PartRequest req) {
        mService = service;
        mContext = service.mContext;
        mResolver = service.mContentResolver;
        mAttachment = req.mAttachment;
        mAttachmentId = mAttachment.mId;
        mAttachmentSize = (int)mAttachment.mSize;
        mAccountId = mAttachment.mAccountKey;
        mMessageId = mAttachment.mMessageKey;
        mMessage = Message.restoreMessageWithId(mContext, mMessageId);
        mAttachmentUri = AttachmentUtilities.getAttachmentUri(mAccountId, mAttachmentId);
    }

    private void doStatusCallback(int status) {
        try {
            ExchangeService.callback().loadAttachmentStatus(mMessageId, mAttachmentId, status, 0);
        } catch (RemoteException e) {
            // No danger if the client is no longer around
        }
    }

    private void doProgressCallback(int progress) {
        try {
            ExchangeService.callback().loadAttachmentStatus(mMessageId, mAttachmentId,
                    EmailServiceStatus.IN_PROGRESS, progress);
        } catch (RemoteException e) {
            // No danger if the client is no longer around
        }
    }

    /**
     * Save away the contentUri for this Attachment and notify listeners
     */
    private void finishLoadAttachment() {
        ContentValues cv = new ContentValues();
        cv.put(AttachmentColumns.CONTENT_URI, mAttachmentUri.toString());
        mAttachment.update(mContext, cv);
        doStatusCallback(EmailServiceStatus.SUCCESS);
    }

    /**
     * Read the attachment data in chunks and write the data back out to our attachment file
     * @param inputStream the InputStream we're reading the attachment from
     * @param outputStream the OutputStream the attachment will be written to
     * @param len the number of expected bytes we're going to read
     * @throws IOException
     */
    public void readChunked(InputStream inputStream, OutputStream outputStream, int len)
            throws IOException {
        byte[] bytes = new byte[CHUNK_SIZE];
        int length = len;
        // Loop terminates 1) when EOF is reached or 2) IOException occurs
        // One of these is guaranteed to occur
        int totalRead = 0;
        mService.userLog("Expected attachment length: ", len);
        while (true) {
            int read = inputStream.read(bytes, 0, CHUNK_SIZE);
            if (read < 0) {
                // -1 means EOF
                mService.userLog("Attachment load reached EOF, totalRead: ", totalRead);
                break;
            }

            // Keep track of how much we've read for progress callback
            totalRead += read;
            // Write these bytes out
            outputStream.write(bytes, 0, read);

            // We can't report percentage if data is chunked; the length of incoming data is unknown
            if (length > 0) {
                // Belt and suspenders check to prevent runaway reading
                if (totalRead > length) {
                    mService.errorLog("totalRead is greater than attachment length?");
                    break;
                }
                // Report progress back to the UI
                doProgressCallback((totalRead * 100) / length);
            }
        }
    }

    /**
     * Loads an attachment, based on the PartRequest passed in the constructor
     * @throws IOException
     */
    public void loadAttachment() throws IOException {
        if (mMessage == null) {
            doStatusCallback(EmailServiceStatus.MESSAGE_NOT_FOUND);
            return;
        }
        // Say we've started loading the attachment
        doProgressCallback(0);

        EasResponse resp;
        boolean eas14 = mService.mProtocolVersionDouble >= Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE;
        // The method of attachment loading is different in EAS 14.0 than in earlier versions
        if (eas14) {
            Serializer s = new Serializer();
            s.start(Tags.ITEMS_ITEMS).start(Tags.ITEMS_FETCH);
            s.data(Tags.ITEMS_STORE, "Mailbox");
            s.data(Tags.BASE_FILE_REFERENCE, mAttachment.mLocation);
            s.end().end().done(); // ITEMS_FETCH, ITEMS_ITEMS
            resp = mService.sendHttpClientPost("ItemOperations", s.toByteArray());
        } else {
            String cmd = "GetAttachment&AttachmentName=" + mAttachment.mLocation;
            resp = mService.sendHttpClientPost(cmd, null, EasSyncService.COMMAND_TIMEOUT);
        }

        try {
            int status = resp.getStatus();
            if (status == HttpStatus.SC_OK) {
                if (!resp.isEmpty()) {
                    InputStream is = resp.getInputStream();
                    OutputStream os = null;
                    try {
                        os = mResolver.openOutputStream(mAttachmentUri);
                        if (eas14) {
                            ItemOperationsParser p = new ItemOperationsParser(this, is, os,
                                    mAttachmentSize);
                            p.parse();
                            if (p.getStatusCode() == 1 /* Success */) {
                                finishLoadAttachment();
                                return;
                            }
                        } else {
                            int len = resp.getLength();
                            if (len != 0) {
                                // len > 0 means that Content-Length was set in the headers
                                // len < 0 means "chunked" transfer-encoding
                                readChunked(is, os, len);
                                finishLoadAttachment();
                                return;
                            }
                        }
                    } catch (FileNotFoundException e) {
                        mService.errorLog("Can't get attachment; write file not found?");
                    } finally {
                        if (os != null) {
                            os.flush();
                            os.close();
                        }
                    }
                }
            }
        } finally {
            resp.close();
        }

        // All errors lead here...
        doStatusCallback(EmailServiceStatus.ATTACHMENT_NOT_FOUND);
    }
}
