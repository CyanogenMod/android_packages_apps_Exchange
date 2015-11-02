/**
 * Copyright (c) 2013-2014, The Linux Foundation. All rights reserved.
 *
 * Not a Contribution.
 *
 * Copyright (C) 2008-2009 Marc Blank
 * Licensed to The Android Open Source Project.
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

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;

import com.android.emailcommon.Logging;
import com.android.emailcommon.internet.MimeMessage;
import com.android.emailcommon.internet.MimeUtility;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.mail.Part;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.ProviderUnavailableException;
import com.android.emailcommon.provider.EmailContent.Body;
import com.android.emailcommon.provider.EmailContent.BodyColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.utility.ConversionUtilities;
import com.android.exchange.CommandStatusException;
import com.android.exchange.Eas;
import com.android.exchange.EasAuthenticationException;
import com.android.exchange.EasResponse;
import com.android.exchange.adapter.Parser;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;
import com.android.mail.utils.LogUtils;

import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.entity.ByteArrayEntity;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

public class EasLoadMore extends EasOperation {
    private static final String CMD = "ItemOperations";

    private static final int RESULT_SUCCESS = 0;

    private Message mMessage;

    public EasLoadMore(final Context context, final Account account, final Message msg) {
        super(context, account);
        mMessage = msg;
    }

    @Override
    protected String getCommand() {
        if (mMessage == null) {
            LogUtils.wtf(LOG_TAG, "Error, mMessage is null");
            return null;
        }
        return CMD;
    }

    /**
     * The FetchMessageRequest is basically our wrapper for the Fetch service call
     *
     * Request:
     * <?xml version="1.0" encoding="utf-8"?>
     * <ItemOperations>
     *     <Fetch>
     *         <Store>Mailbox</Store>
     *         <airsync:CollectionId>collectionId</airsync:CollectionId>
     *         <airsync:ServerId>serverId</airsync:ServerId>
     *         <Options>
     *             <airsyncbase:BodyPreference>
     *                 <airsyncbase:Type>1</airsyncbase:Type>
     *                 <airsyncbase:TruncationSize>size</airsyncbase:TruncationSize>
     *                 <airsyncbase:AllOrNone>0</airsyncbase:AllOrNone>
     *             </airsyncbase:BodyPreference>
     *         </Options>
     *     </Fetch>
     * </ItemOperations>
     */
    @Override
    protected HttpEntity getRequestEntity() throws IOException, MessageInvalidException {
        if (mMessage == null) {
            LogUtils.wtf(LOG_TAG, "Error, mMessage is null");
            return null;
        }

        final ContentResolver cr = mContext.getContentResolver();

        String serverId = "";
        long mailbox = -1;
        Uri qreryUri = ContentUris.withAppendedId(Message.CONTENT_URI, mMessage.mId);
        String[] projection = new String[] { SyncColumns.SERVER_ID, MessageColumns.MAILBOX_KEY };
        Cursor c = cr.query(qreryUri, projection, null, null, null);
        if (c == null) {
            throw new ProviderUnavailableException();
        } else {
            if (c.moveToFirst()) {
                serverId = c.getString(0);
                mailbox = c.getLong(1);
            }
            c.close();
            c = null;
        }
        if (TextUtils.isEmpty(serverId) || mailbox < 0) return null;
        Mailbox box = Mailbox.restoreMailboxWithId(mContext, mailbox);

        Serializer s = new Serializer();

        s.start(Tags.ITEMS_ITEMS).start(Tags.ITEMS_FETCH);
        s.data(Tags.ITEMS_STORE, "Mailbox");
        s.data(Tags.SYNC_COLLECTION_ID, box.mServerId);
        s.data(Tags.SYNC_SERVER_ID, mMessage.mServerId);
        s.start(Tags.ITEMS_OPTIONS);
        if (getProtocolVersion() >= Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
            s.start(Tags.BASE_BODY_PREFERENCE);
            s.data(Tags.BASE_TYPE, Eas.BODY_PREFERENCE_HTML);
            s.end();
        } else {
            s.data(Tags.SYNC_MIME_SUPPORT, Eas.MIME_BODY_PREFERENCE_MIME);
            s.start(Tags.BASE_BODY_PREFERENCE);
            s.data(Tags.BASE_TYPE, Eas.BODY_PREFERENCE_MIME);
            s.end();
        }
        s.end().end().end().done();

        return new ByteArrayEntity(s.toByteArray());
    }

    @Override
    protected int handleResponse(EasResponse response) throws IOException, CommandStatusException {
        int status = response.getStatus();
        if (status == HttpStatus.SC_OK) {
            if (!response.isEmpty()) {
                InputStream is = response.getInputStream();
                LoadMoreParser parser = new LoadMoreParser(is, mMessage);
                parser.parse();
                if (parser.getStatusCode() == LoadMoreParser.STATUS_CODE_SUCCESS) {
                    parser.commit(mContext);
                }
            } else {
                return RESULT_NETWORK_PROBLEM;
            }
        } else {
            LogUtils.e(Logging.LOG_TAG, "Fetch entire mail(messageId:" + mMessage.mId
                    + ") response error: ", status);
            if (response.isAuthError()) {
                throw new EasAuthenticationException();
            } else {
                throw new IOException();
            }
        }

        return RESULT_SUCCESS;
    }

    private class LoadMoreParser extends Parser {
        /**
         * Response:
         * <?xml version="1.0" encoding="utf-8"?>
         * <ItemOperations>
         *     <Status>1</Status>
         *     <Response>
         *         <Fetch>
         *             <Status>1</Status>
         *             <airsync:CollectionId>collectionId</airsync:CollectionId>
         *             <airsync:ServerId>serverId</airsync:ServerId>
         *             <airsync:Class>Email</airsync:Class>
         *             <Properties>
         *                 ...
         *             </Properties>
         *         </Fetch>
         *     </Response>
         * </ItemOperations>
         */

        private int mStatusCode = 0;
        private String mBodyType;

        public static final int STATUS_CODE_SUCCESS = 1;

        public LoadMoreParser(InputStream in, Message msg)
                throws IOException {
            super(in);
        }

        public int getStatusCode() {
            return mStatusCode;
        }

        // commit the body data to database.
        public void commit(Context context) {
            LogUtils.d(Logging.LOG_TAG, "Fetched message body successfully for " + mMessage.mId);

            // update the body data
            ContentValues cv = new ContentValues();
            cv.put(BodyColumns.MESSAGE_KEY, mMessage.mId);
            if (mBodyType.equals(Eas.BODY_PREFERENCE_HTML)) {
                cv.put(BodyColumns.HTML_CONTENT, mMessage.mHtml);
            } else {
                cv.put(BodyColumns.TEXT_CONTENT, mMessage.mText);
            }
            ContentResolver contentResolver = context.getContentResolver();
            int res = contentResolver.update(Body.CONTENT_URI, cv,
                    BodyColumns.MESSAGE_KEY + "=" + mMessage.mId, null);
            LogUtils.d(Logging.LOG_TAG, "update the body content, success number : " + res);

            // update the loaded flag to database.
            cv.clear();
            cv.put(MessageColumns.FLAG_LOADED, Message.FLAG_LOADED_COMPLETE);
            Uri uri = ContentUris.withAppendedId(Message.CONTENT_URI, mMessage.mId);
            res = contentResolver.update(uri, cv, null, null);
            LogUtils.d(Logging.LOG_TAG, "update the message content, success number : " + res);
        }

        public void parseBody() throws IOException {
            mBodyType = Eas.BODY_PREFERENCE_TEXT;
            String body = "";
            while (nextTag(Tags.BASE_BODY) != END) {
                switch (tag) {
                    case Tags.BASE_TYPE:
                        mBodyType = getValue();
                        break;
                    case Tags.BASE_DATA:
                        body = getValue();
                        break;
                    default:
                        skipTag();
                }
            }
            // We always ask for TEXT or HTML; there's no third option
            if (mBodyType.equals(Eas.BODY_PREFERENCE_HTML)) {
                mMessage.mHtml = body;
            } else {
                mMessage.mText = body;
            }
        }

        public void parseMIMEBody(String mimeData) throws IOException {
            try {
                ByteArrayInputStream in = new ByteArrayInputStream(mimeData.getBytes());
                // The constructor parses the message
                MimeMessage mimeMessage = new MimeMessage(in);
                // Now process body parts & attachments
                ArrayList<Part> viewables = new ArrayList<Part>();
                // We'll ignore the attachments, as we'll get them directly from EAS
                ArrayList<Part> attachments = new ArrayList<Part>();
                MimeUtility.collectParts(mimeMessage, viewables, attachments);
                // parseBodyFields fills in the content fields of the Body
                ConversionUtilities.BodyFieldData data =
                        ConversionUtilities.parseBodyFields(viewables);
                // But we need them in the message itself for handling during commit()
                mMessage.setFlags(data.isQuotedReply, data.isQuotedForward);
                mMessage.mSnippet = data.snippet;
                mMessage.mHtml = data.htmlContent;
                mMessage.mText = data.textContent;
            } catch (MessagingException e) {
                // This would most likely indicate a broken stream
                throw new IOException(e);
            }
        }

        public void parseProperties() throws IOException {
            while (nextTag(Tags.ITEMS_PROPERTIES) != END) {
                switch (tag) {
                    case Tags.BASE_BODY:
                        parseBody();
                        break;
                    case Tags.EMAIL_MIME_DATA:
                        parseMIMEBody(getValue());
                        break;
                    case Tags.EMAIL_BODY:
                        String text = getValue();
                        mMessage.mText = text;
                        break;
                    default:
                        skipTag();
                }
            }
        }

        public void parseFetch() throws IOException {
            while (nextTag(Tags.ITEMS_FETCH) != END) {
                if (tag == Tags.ITEMS_PROPERTIES) {
                    parseProperties();
                } else {
                    skipTag();
                }
            }
        }

        public void parseResponse() throws IOException {
            while (nextTag(Tags.ITEMS_RESPONSE) != END) {
                if (tag == Tags.ITEMS_FETCH) {
                    parseFetch();
                } else {
                    skipTag();
                }
            }
        }

        @Override
        public boolean parse() throws IOException {
            boolean res = false;
            if (nextTag(START_DOCUMENT) != Tags.ITEMS_ITEMS) {
                throw new IOException();
            }
            while (nextTag(START_DOCUMENT) != END_DOCUMENT) {
                if (tag == Tags.ITEMS_STATUS) {
                    // save the status code.
                    mStatusCode = getValueInt();
                } else if (tag == Tags.ITEMS_RESPONSE) {
                    parseResponse();
                } else {
                    skipTag();
                }
            }
            return res;
        }
    }
}
