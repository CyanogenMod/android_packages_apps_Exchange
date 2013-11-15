package com.android.exchange.service;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.TrafficStats;
import android.net.Uri;
import android.text.format.DateUtils;
import android.util.Log;

import com.android.emailcommon.TrafficFlags;
import com.android.emailcommon.internet.Rfc822Output;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.Body;
import com.android.emailcommon.provider.EmailContent.BodyColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.Utility;
import com.android.exchange.CommandStatusException.CommandStatus;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;
import com.android.exchange.adapter.Parser;
import com.android.exchange.adapter.Parser.EmptyStreamException;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;
import com.android.mail.utils.LogUtils;

import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.entity.InputStreamEntity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.cert.CertificateException;
import java.util.ArrayList;

/**
 * Performs an Exchange Outbox sync, i.e. sends all mail from the Outbox.
 */
public class EasOutboxSyncHandler extends EasServerConnection {
    // Value for a message's server id when sending fails.
    public static final int SEND_FAILED = 1;

    // WHERE clause to query for unsent messages.
    // TODO: Is the SEND_FAILED check actually what we want?
    public static final String MAILBOX_KEY_AND_NOT_SEND_FAILED =
            MessageColumns.MAILBOX_KEY + "=? and (" + SyncColumns.SERVER_ID + " is null or " +
            SyncColumns.SERVER_ID + "!=" + SEND_FAILED + ')';

    // This needs to be long enough to send the longest reasonable message, without being so long
    // as to effectively "hang" sending of mail.  The standard 30 second timeout isn't long enough
    // for pictures and the like.  For now, we'll use 15 minutes, in the knowledge that any socket
    // failure would probably generate an Exception before timing out anyway
    public static final long SEND_MAIL_TIMEOUT = 15 * DateUtils.MINUTE_IN_MILLIS;

    private final Mailbox mMailbox;
    private final File mCacheDir;

    public EasOutboxSyncHandler(final Context context, final Account account,
            final Mailbox mailbox) {
        super(context, account);
        mMailbox = mailbox;
        mCacheDir = context.getCacheDir();
    }

    public void performSync() {
        // Use SMTP flags for sending mail
        TrafficStats.setThreadStatsTag(TrafficFlags.getSmtpFlags(mContext, mAccount));
        // Get a cursor to Outbox messages
        final Cursor c = mContext.getContentResolver().query(Message.CONTENT_URI,
                Message.CONTENT_PROJECTION, MAILBOX_KEY_AND_NOT_SEND_FAILED,
                new String[] {Long.toString(mMailbox.mId)}, null);
        try {
            // Loop through the messages, sending each one
            while (c.moveToNext()) {
                final Message message = new Message();
                message.restore(c);
                if (Utility.hasUnloadedAttachments(mContext, message.mId)) {
                    // We'll just have to wait on this...
                    continue;
                }

                // TODO: Fix -- how do we want to signal to UI that we started syncing?
                // Note the entire callback mechanism here needs improving.
                //sendMessageStatus(message.mId, null, EmailServiceStatus.IN_PROGRESS, 0);

                if (!sendOneMessage(message,
                        SmartSendInfo.getSmartSendInfo(mContext, mAccount, message))) {
                    break;
                }
            }
        } finally {
            // TODO: Some sort of sendMessageStatus() is needed here.
            c.close();
        }
    }

    /**
     * Information needed for SmartReply/SmartForward.
     */
    private static class SmartSendInfo {
        public static final String[] BODY_SOURCE_PROJECTION =
                new String[] {BodyColumns.SOURCE_MESSAGE_KEY};
        public static final String WHERE_MESSAGE_KEY = Body.MESSAGE_KEY + "=?";

        final String mItemId;
        final String mCollectionId;
        final boolean mIsReply;
        final ArrayList<Attachment> mRequiredAtts;

        private SmartSendInfo(final String itemId, final String collectionId, final boolean isReply,
                final ArrayList<Attachment> requiredAtts) {
            mItemId = itemId;
            mCollectionId = collectionId;
            mIsReply = isReply;
            mRequiredAtts = requiredAtts;
        }

        public String generateSmartSendCmd() {
            final StringBuilder sb = new StringBuilder();
            sb.append(isForward() ? "SmartForward" : "SmartReply");
            sb.append("&ItemId=");
            sb.append(Uri.encode(mItemId, ":"));
            sb.append("&CollectionId=");
            sb.append(Uri.encode(mCollectionId, ":"));
            return sb.toString();
        }

        public boolean isForward() {
            return !mIsReply;
        }

        /**
         * See if a given attachment is among an array of attachments; it is if the locations of
         * both are the same (we're looking to see if they represent the same attachment on the
         * server. Note that an attachment that isn't on the server (e.g. an outbound attachment
         * picked from the  gallery) won't have a location, so the result will always be false.
         *
         * @param att the attachment to test
         * @param atts the array of attachments to look in
         * @return whether the test attachment is among the array of attachments
         */
        private static boolean amongAttachments(final Attachment att, final Attachment[] atts) {
            final String location = att.mLocation;
            if (location == null) return false;
            for (final Attachment a: atts) {
                if (location.equals(a.mLocation)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * If this message should use SmartReply or SmartForward, return an object with the data
         * for the smart send.
         *
         * @param context the caller's context
         * @param account the Account we're sending from
         * @param message the Message being sent
         * @return an object to support smart sending, or null if not applicable.
         */
        public static SmartSendInfo getSmartSendInfo(final Context context,
                final Account account, final Message message) {
            final int flags = message.mFlags;
            // We only care about the original message if we include quoted text.
            if ((flags & Message.FLAG_NOT_INCLUDE_QUOTED_TEXT) != 0) {
                return null;
            }
            final boolean reply = (flags & Message.FLAG_TYPE_REPLY) != 0;
            final boolean forward = (flags & Message.FLAG_TYPE_FORWARD) != 0;
            // We also only care for replies or forwards.
            if (!reply && !forward) {
                return null;
            }
            // Just a sanity check here, since we assume that reply and forward are mutually
            // exclusive throughout this class.
            if (reply && forward) {
                return null;
            }
            // If we don't support SmartForward and it's a forward, then don't proceed.
            if (forward && (account.mFlags & Account.FLAGS_SUPPORTS_SMART_FORWARD) == 0) {
                return null;
            }

            // Note: itemId and collectionId are the terms used by EAS to refer to the serverId and
            // mailboxId of a Message
            String itemId = null;
            String collectionId = null;

            // First, we need to get the id of the reply/forward message
            String[] cols = Utility.getRowColumns(context, Body.CONTENT_URI, BODY_SOURCE_PROJECTION,
                    WHERE_MESSAGE_KEY, new String[] {Long.toString(message.mId)});
            long refId = 0;
            // TODO: We can probably just write a smarter query to do this all at once.
            if (cols != null && cols[0] != null) {
                refId = Long.parseLong(cols[0]);
                // Then, we need the serverId and mailboxKey of the message
                cols = Utility.getRowColumns(context, Message.CONTENT_URI, refId,
                        SyncColumns.SERVER_ID, MessageColumns.MAILBOX_KEY,
                        MessageColumns.PROTOCOL_SEARCH_INFO);
                if (cols != null) {
                    itemId = cols[0];
                    final long boxId = Long.parseLong(cols[1]);
                    // Then, we need the serverId of the mailbox
                    cols = Utility.getRowColumns(context, Mailbox.CONTENT_URI, boxId,
                            MailboxColumns.SERVER_ID);
                    if (cols != null) {
                        collectionId = cols[0];
                    }
                }
            }
            // We need either a longId or both itemId (serverId) and collectionId (mailboxId) to
            // process a smart reply or a smart forward
            if (itemId != null && collectionId != null) {
                final ArrayList<Attachment> requiredAtts;
                if (forward) {
                    // See if we can really smart forward (all reference attachments must be sent)
                    final Attachment[] outAtts =
                            Attachment.restoreAttachmentsWithMessageId(context, message.mId);
                    final Attachment[] refAtts =
                            Attachment.restoreAttachmentsWithMessageId(context, refId);
                    for (final Attachment refAtt: refAtts) {
                        // If an original attachment isn't among what's going out, we can't be smart
                        if (!amongAttachments(refAtt, outAtts)) {
                            return null;
                        }
                    }
                    requiredAtts = new ArrayList<Attachment>();
                    for (final Attachment outAtt: outAtts) {
                        // If an outgoing attachment isn't in original message, we must send it
                        if (!amongAttachments(outAtt, refAtts)) {
                            requiredAtts.add(outAtt);
                        }
                    }
                } else {
                    requiredAtts = null;
                }
                return new SmartSendInfo(itemId, collectionId, reply, requiredAtts);
            }
            return null;
        }
    }

    /**
     * Our own HttpEntity subclass that is able to insert opaque data (in this case the MIME
     * representation of the message body as stored in a temporary file) into the serializer stream
     */
    private static class SendMailEntity extends InputStreamEntity {
        private final FileInputStream mFileStream;
        private final long mFileLength;
        private final int mSendTag;
        private final Message mMessage;
        private final SmartSendInfo mSmartSendInfo;

        public SendMailEntity(final FileInputStream instream, final long length, final int tag,
                final Message message, final SmartSendInfo smartSendInfo) {
            super(instream, length);
            mFileStream = instream;
            mFileLength = length;
            mSendTag = tag;
            mMessage = message;
            mSmartSendInfo = smartSendInfo;
        }

        /**
         * We always return -1 because we don't know the actual length of the POST data (this
         * causes HttpClient to send the data in "chunked" mode)
         */
        @Override
        public long getContentLength() {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                // Calculate the overhead for the WBXML data
                writeTo(baos, false);
                // Return the actual size that will be sent
                return baos.size() + mFileLength;
            } catch (final IOException e) {
                // Just return -1 (unknown)
            } finally {
                try {
                    baos.close();
                } catch (final IOException e) {
                    // Ignore
                }
            }
            return -1;
        }

        @Override
        public void writeTo(final OutputStream outstream) throws IOException {
            writeTo(outstream, true);
        }

        /**
         * Write the message to the output stream
         * @param outstream the output stream to write
         * @param withData whether or not the actual data is to be written; true when sending
         *   mail; false when calculating size only
         * @throws IOException
         */
        public void writeTo(final OutputStream outstream, final boolean withData)
                throws IOException {
            // Not sure if this is possible; the check is taken from the superclass
            if (outstream == null) {
                throw new IllegalArgumentException("Output stream may not be null");
            }

            // We'll serialize directly into the output stream
            final Serializer s = new Serializer(outstream);
            // Send the appropriate initial tag
            s.start(mSendTag);
            // The Message-Id for this message (note that we cannot use the messageId stored in
            // the message, as EAS 14 limits the length to 40 chars and we use 70+)
            s.data(Tags.COMPOSE_CLIENT_ID, "SendMail-" + System.nanoTime());
            // We always save sent mail
            s.tag(Tags.COMPOSE_SAVE_IN_SENT_ITEMS);

            // If we're using smart reply/forward, we need info about the original message
            if (mSendTag != Tags.COMPOSE_SEND_MAIL) {
                if (mSmartSendInfo != null) {
                    s.start(Tags.COMPOSE_SOURCE);
                    // For search results, use the long id (stored in mProtocolSearchInfo); else,
                    // use folder id/item id combo
                    if (mMessage.mProtocolSearchInfo != null) {
                        s.data(Tags.COMPOSE_LONG_ID, mMessage.mProtocolSearchInfo);
                    } else {
                        s.data(Tags.COMPOSE_ITEM_ID, mSmartSendInfo.mItemId);
                        s.data(Tags.COMPOSE_FOLDER_ID, mSmartSendInfo.mCollectionId);
                    }
                    s.end();  // Tags.COMPOSE_SOURCE
                }
            }

            // Start the MIME tag; this is followed by "opaque" data (byte array)
            s.start(Tags.COMPOSE_MIME);
            // Send opaque data from the file stream
            if (withData) {
                s.opaque(mFileStream, (int)mFileLength);
            } else {
                s.opaqueWithoutData((int)mFileLength);
            }
            // And we're done
            s.end().end().done();
        }
    }

    private static class SendMailParser extends Parser {
        private final int mStartTag;
        private int mStatus;

        public SendMailParser(final InputStream in, final int startTag) throws IOException {
            super(in);
            mStartTag = startTag;
        }

        public int getStatus() {
            return mStatus;
        }

        /**
         * The only useful info in the SendMail response is the status; we capture and save it
         */
        @Override
        public boolean parse() throws IOException {
            if (nextTag(START_DOCUMENT) != mStartTag) {
                throw new IOException();
            }
            while (nextTag(START_DOCUMENT) != END_DOCUMENT) {
                if (tag == Tags.COMPOSE_STATUS) {
                    mStatus = getValueInt();
                } else {
                    skipTag();
                }
            }
            return true;
        }
    }

    /**
     * Attempt to send one message.
     * @param message The message to send.
     * @param smartSendInfo The SmartSendInfo for this message, or null if we don't have or don't
     *      want to use smart send.
     * @return Whether or not sending this message succeeded.
     * TODO: Improve how we handle the types of failures. I've left the old error codes in as TODOs
     * for future reference.
     */
    private boolean sendOneMessage(final Message message, final SmartSendInfo smartSendInfo) {
        final File tmpFile;
        try {
            tmpFile = File.createTempFile("eas_", "tmp", mCacheDir);
        } catch (final IOException e) {
            return false; // TODO: Handle SyncStatus.FAILURE_IO;
        }

        final EasResponse resp;
        // Send behavior differs pre and post EAS14.
        final boolean isEas14 = (Double.parseDouble(mAccount.mProtocolVersion) >=
                Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE);
        final int modeTag = getModeTag(isEas14, smartSendInfo);
        try {
            if (!writeMessageToTempFile(tmpFile, message, smartSendInfo)) {
                return false; // TODO: Handle SyncStatus.FAILURE_IO;
            }

            final FileInputStream fileStream;
            try {
                fileStream = new FileInputStream(tmpFile);
            } catch (final FileNotFoundException e) {
                return false; // TODO: Handle SyncStatus.FAILURE_IO;
            }
            try {

                final long fileLength = tmpFile.length();
                final HttpEntity entity;
                if (isEas14) {
                    entity = new SendMailEntity(fileStream, fileLength, modeTag, message,
                            smartSendInfo);
                } else {
                    entity = new InputStreamEntity(fileStream, fileLength);
                }

                // Create the appropriate command.
                String cmd = "SendMail";
                if (smartSendInfo != null) {
                    // In EAS 14, we don't send itemId and collectionId in the command
                    if (isEas14) {
                        cmd = smartSendInfo.isForward() ? "SmartForward" : "SmartReply";
                    } else {
                        cmd = smartSendInfo.generateSmartSendCmd();
                    }
                }
                // If we're not EAS 14, add our save-in-sent setting here
                if (!isEas14) {
                    cmd += "&SaveInSent=T";
                }
                // Finally, post SendMail to the server
                try {
                    resp = sendHttpClientPost(cmd, entity, SEND_MAIL_TIMEOUT);
                } catch (final IOException e) {
                    return false; // TODO: Handle SyncStatus.FAILURE_IO;
                } catch (final CertificateException e) {
                    return false;
                }

            } finally {
                try {
                    fileStream.close();
                } catch (final IOException e) {
                    // TODO: Should we do anything here, or is it ok to just proceed?
                }
            }
        } finally {
            if (tmpFile.exists()) {
                tmpFile.delete();
            }
        }

        try {
            final int code = resp.getStatus();
            if (code == HttpStatus.SC_OK) {
                // HTTP OK before EAS 14 is a thumbs up; in EAS 14, we've got to parse
                // the reply
                if (isEas14) {
                    try {
                        // Try to parse the result
                        final SendMailParser p = new SendMailParser(resp.getInputStream(), modeTag);
                        // If we get here, the SendMail failed; go figure
                        p.parse();
                        // The parser holds the status
                        final int status = p.getStatus();
                        if (CommandStatus.isNeedsProvisioning(status)) {
                            return false; // TODO: Handle SyncStatus.FAILURE_SECURITY;
                        } else if (status == CommandStatus.ITEM_NOT_FOUND &&
                                smartSendInfo != null) {
                            // Let's retry without "smart" commands.
                            return sendOneMessage(message, null);
                        }
                        // TODO: Set syncServerId = SEND_FAILED in DB?
                        return false; // TODO: Handle SyncStatus.FAILURE_MESSAGE;
                    } catch (final EmptyStreamException e) {
                        // This is actually fine; an empty stream means SendMail succeeded
                    } catch (final IOException e) {
                        // Parsing failed in some other way.
                        return false; // TODO: Handle SyncStatus.FAILURE_IO;
                    }
                }
            } else if (code == HttpStatus.SC_INTERNAL_SERVER_ERROR && smartSendInfo != null) {
                // Let's retry without "smart" commands.
                return sendOneMessage(message, null);
            } else {
                if (resp.isAuthError()) {
                    LogUtils.d(LogUtils.TAG, "Got auth error from server during outbox sync");
                    return false; // TODO: Handle SyncStatus.FAILURE_LOGIN;
                } else if (resp.isProvisionError()) {
                    LogUtils.d(LogUtils.TAG, "Got provision error from server during outbox sync.");
                    return false; // TODO: Handle SyncStatus.FAILURE_SECURITY;
                } else {
                    // TODO: Handle some other error
                    LogUtils.d(LogUtils.TAG,
                            "Got other HTTP error from server during outbox sync: %d", code);
                    return false;
                }
            }
        } finally {
            resp.close();
        }

        // If we manage to get here, the message sent successfully. Hooray!
        // Delete the sent message.
        mContext.getContentResolver().delete(
                ContentUris.withAppendedId(Message.CONTENT_URI, message.mId), null, null);
        return true;
    }

    /**
     * Writes message to the temp file.
     * @param tmpFile The temp file to use.
     * @param message The {@link Message} to write.
     * @param smartSendInfo The {@link SmartSendInfo} for this message send attempt.
     * @return Whether we could successfully write the file.
     */
    private boolean writeMessageToTempFile(final File tmpFile, final Message message,
            final SmartSendInfo smartSendInfo) {
        final FileOutputStream fileStream;
        try {
            fileStream = new FileOutputStream(tmpFile);
        } catch (final FileNotFoundException e) {
            Log.e(LogUtils.TAG, "Failed to create message file", e);
            return false;
        }
        try {
            final boolean smartSend = smartSendInfo != null;
            final ArrayList<Attachment> attachments =
                    smartSend ? smartSendInfo.mRequiredAtts : null;
            Rfc822Output.writeTo(mContext, message, fileStream, smartSend, true, attachments);
        } catch (final Exception e) {
            Log.e(LogUtils.TAG, "Failed to write message file", e);
            return false;
        } finally {
            try {
                fileStream.close();
            } catch (final IOException e) {
                // should not happen
                Log.e(LogUtils.TAG, "Failed to close file - should not happen", e);
            }
        }
        return true;
    }

    private static int getModeTag(final boolean isEas14, final SmartSendInfo smartSendInfo) {
        if (isEas14) {
            if (smartSendInfo == null) {
                return Tags.COMPOSE_SEND_MAIL;
            } else if (smartSendInfo.isForward()) {
                return Tags.COMPOSE_SMART_FORWARD;
            } else {
                return Tags.COMPOSE_SMART_REPLY;
            }
        }
        return 0;
    }
}
