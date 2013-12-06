package com.android.exchange.service;

import android.content.Context;
import android.os.RemoteException;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;
import com.android.exchange.adapter.ItemOperationsParser;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;
import com.android.exchange.utility.UriCodec;
import com.android.mail.utils.LogUtils;

import org.apache.http.HttpStatus;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.cert.CertificateException;

/**
 * Loads attachments from the Exchange server.
 * TODO: Add ability to call back to UI when this failed, and generally better handle error cases.
 */
public class EasAttachmentLoader extends EasServerConnection {
    private static final String TAG = Eas.LOG_TAG;

    private final IEmailServiceCallback mCallback;

    private EasAttachmentLoader(final Context context, final Account account,
            final IEmailServiceCallback callback) {
        super(context, account);
        mCallback = callback;
    }

    // TODO: EmailServiceStatus.ATTACHMENT_NOT_FOUND is heavily used, may need to split that into
    // different statuses.
    private static void doStatusCallback(final IEmailServiceCallback callback,
            final long messageKey, final long attachmentId, final int status, final int progress) {
        if (callback != null) {
            try {
                callback.loadAttachmentStatus(messageKey, attachmentId, status, progress);
            } catch (final RemoteException e) {
                LogUtils.e(TAG, "RemoteException in loadAttachment: %s", e.getMessage());
            }
        }
    }

    /**
     * Provides the parser with the data it needs to perform the callback.
     */
    public static class ProgressCallback {
        private final IEmailServiceCallback mCallback;
        private final Attachment mAttachment;

        public ProgressCallback(final IEmailServiceCallback callback,
                final Attachment attachment) {
            mCallback = callback;
            mAttachment = attachment;
        }

        public void doCallback(final int progress) {
            doStatusCallback(mCallback, mAttachment.mMessageKey, mAttachment.mId,
                    EmailServiceStatus.IN_PROGRESS, progress);
        }
    }

    /**
     * Load an attachment from the Exchange server, and write it to the content provider.
     * @param context Our {@link Context}.
     * @param attachmentId The local id of the attachment (i.e. its id in the database).
     * @param callback The callback for any status updates.
     */
    public static void loadAttachment(final Context context, final long attachmentId,
            final IEmailServiceCallback callback) {
        final Attachment attachment = Attachment.restoreAttachmentWithId(context, attachmentId);
        if (attachment == null) {
            LogUtils.d(TAG, "Could not load attachment %d", attachmentId);
            doStatusCallback(callback, -1, attachmentId, EmailServiceStatus.ATTACHMENT_NOT_FOUND,
                    0);
            return;
        }
        if (attachment.mLocation == null) {
            LogUtils.e(TAG, "Attachment %d lacks a location", attachmentId);
            doStatusCallback(callback, -1, attachmentId, EmailServiceStatus.ATTACHMENT_NOT_FOUND,
                    0);
            return;
        }
        final Account account = Account.restoreAccountWithId(context, attachment.mAccountKey);
        if (account == null) {
            LogUtils.d(TAG, "Attachment %d has bad account key %d", attachment.mId,
                    attachment.mAccountKey);
            doStatusCallback(callback, attachment.mMessageKey, attachmentId,
                    EmailServiceStatus.ATTACHMENT_NOT_FOUND, 0);
            return;
        }
        final Message message = Message.restoreMessageWithId(context, attachment.mMessageKey);
        if (message == null) {
            doStatusCallback(callback, attachment.mMessageKey, attachmentId,
                EmailServiceStatus.MESSAGE_NOT_FOUND, 0);
            return;
        }

        // Error cases handled, do the load.
        final EasAttachmentLoader loader =
                new EasAttachmentLoader(context, account, callback);
        final int status = loader.load(attachment);
        doStatusCallback(callback, attachment.mMessageKey, attachmentId, status, 0);
    }

    /**
     * Encoder for Exchange 2003 attachment names.  They come from the server partially encoded,
     * but there are still possible characters that need to be encoded (Why, MSFT, why?)
     */
    private static class AttachmentNameEncoder extends UriCodec {
        @Override
        protected boolean isRetained(final char c) {
            // These four characters are commonly received in EAS 2.5 attachment names and are
            // valid (verified by testing); we won't encode them
            return c == '_' || c == ':' || c == '/' || c == '.';
        }
    }

    /**
     * Finish encoding attachment names for Exchange 2003.
     * @param str A partially encoded string.
     * @return The fully encoded version of str.
     */
    private static String encodeForExchange2003(final String str) {
        final AttachmentNameEncoder enc = new AttachmentNameEncoder();
        final StringBuilder sb = new StringBuilder(str.length() + 16);
        enc.appendPartiallyEncoded(sb, str);
        return sb.toString();
    }

    /**
     * Make the appropriate Exchange server request for getting the attachment.
     * @param attachment The {@link Attachment} we wish to load.
     * @return The {@link EasResponse} for the request, or null if we encountered an error.
     */
    private EasResponse performServerRequest(final Attachment attachment) {
        try {
            // The method of attachment loading is different in EAS 14.0 than in earlier versions
            final String cmd;
            final byte[] bytes;
            if (getProtocolVersion() >= Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE) {
                final Serializer s = new Serializer();
                s.start(Tags.ITEMS_ITEMS).start(Tags.ITEMS_FETCH);
                s.data(Tags.ITEMS_STORE, "Mailbox");
                s.data(Tags.BASE_FILE_REFERENCE, attachment.mLocation);
                s.end().end().done(); // ITEMS_FETCH, ITEMS_ITEMS
                cmd = "ItemOperations";
                bytes = s.toByteArray();
            } else {
                final String location;
                // For Exchange 2003 (EAS 2.5), we have to look for illegal chars in the file name
                // that EAS sent to us!
                if (getProtocolVersion() < Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
                    location = encodeForExchange2003(attachment.mLocation);
                } else {
                    location = attachment.mLocation;
                }
                cmd = "GetAttachment&AttachmentName=" + location;
                bytes = null;
            }
            return sendHttpClientPost(cmd, bytes);
        } catch (final IOException e) {
            LogUtils.w(TAG, "IOException while loading attachment from server: %s", e.getMessage());
            return null;
        } catch (final CertificateException e) {
            LogUtils.w(TAG, "CertificateException while loading attachment from server: %s",
                    e.getMessage());
            return null;
        }
    }

    /**
     * Close, ignoring errors (as during cleanup)
     * @param c a Closeable
     */
    private static void close(final Closeable c) {
        try {
            c.close();
        } catch (IOException e) {
            LogUtils.w(TAG, "IOException while cleaning up attachment: %s", e.getMessage());
        }
    }

    /**
     * Save away the contentUri for this Attachment and notify listeners
     */
    private boolean finishLoadAttachment(final Attachment attachment, final File file) {
        final InputStream in;
        try {
            in = new FileInputStream(file);
          } catch (final FileNotFoundException e) {
            // Unlikely, as we just created it successfully, but log it.
            LogUtils.e(TAG, "Could not open attachment file: %s", e.getMessage());
            return false;
        }
        AttachmentUtilities.saveAttachment(mContext, in, attachment);
        close(in);
        return true;
    }

    /**
     * Read the {@link EasResponse} and extract the attachment data, saving it to the provider.
     * @param resp The (successful) {@link EasResponse} containing the attachment data.
     * @param attachment The {@link Attachment} with the attachment metadata.
     * @return A status code, from {@link EmailServiceStatus}, for this load.
     */
    private int handleResponse(final EasResponse resp, final Attachment attachment) {
        final File tmpFile;
        try {
            tmpFile = File.createTempFile("eas_", "tmp", mContext.getCacheDir());
        } catch (final IOException e) {
            LogUtils.w(TAG, "Could not open temp file: %s", e.getMessage());
            // TODO: This is what the old implementation did, but it's kind of the wrong error.
            return EmailServiceStatus.CONNECTION_ERROR;
        }

        try {
            final OutputStream os;
            try {
                os = new FileOutputStream(tmpFile);
            } catch (final FileNotFoundException e) {
                LogUtils.w(TAG, "Temp file not found: %s", e.getMessage());
                return EmailServiceStatus.ATTACHMENT_NOT_FOUND;
            }
            try {
                final InputStream is = resp.getInputStream();
                try {
                    final ProgressCallback callback = new ProgressCallback(mCallback, attachment);
                    final boolean success;
                    if (getProtocolVersion() >= Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE) {
                        final ItemOperationsParser parser = new ItemOperationsParser(is, os,
                                attachment.mSize, callback);
                        parser.parse();
                        success = (parser.getStatusCode() == 1);
                    } else {
                        final int length = resp.getLength();
                        if (length != 0) {
                            // len > 0 means that Content-Length was set in the headers
                            // len < 0 means "chunked" transfer-encoding
                            ItemOperationsParser.readChunked(is, os,
                                    (length < 0) ? attachment.mSize : length, callback);
                        }
                        success = true;
                    }
                    final int status;
                    if (success && finishLoadAttachment(attachment, tmpFile)) {
                        status = EmailServiceStatus.SUCCESS;
                    } else {
                        status = EmailServiceStatus.CONNECTION_ERROR;
                    }
                    return status;
                } catch (final IOException e) {
                    LogUtils.w(TAG, "Error reading attachment: %s", e.getMessage());
                    return EmailServiceStatus.CONNECTION_ERROR;
                } finally {
                    close(is);
                }
            } finally {
                close(os);
            }
        } finally {
            tmpFile.delete();
        }
    }

    /**
     * Load the attachment from the server.
     * @param attachment The attachment to load.
     * @return A status code, from {@link EmailServiceStatus}, for this load.
     */
    private int load(final Attachment attachment) {
        // Send a progress update that we're starting.
        doStatusCallback(mCallback, attachment.mMessageKey, attachment.mId,
                EmailServiceStatus.IN_PROGRESS, 0);
        final EasResponse resp = performServerRequest(attachment);
        if (resp == null) {
            return EmailServiceStatus.CONNECTION_ERROR;
        }

        try {
            if (resp.getStatus() != HttpStatus.SC_OK || resp.isEmpty()) {
                return EmailServiceStatus.ATTACHMENT_NOT_FOUND;
            }
            return handleResponse(resp, attachment);
        } finally {
            resp.close();
        }
    }

}
