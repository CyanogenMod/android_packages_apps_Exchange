package com.android.exchange.service;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Base64;

import com.android.emailcommon.Device;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.utility.EmailClientConnectionManager;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;

import org.apache.http.HttpEntity;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.params.ConnManagerPNames;
import org.apache.http.conn.params.ConnPerRoute;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

import java.io.IOException;
import java.net.URI;

/**
 * Base class for performing a single sync action. It holds the state needed for all sync actions
 * (e.g. account and auth info, sync extras and results), commonly used functions to handle the
 * network communication with the server, and functions to communicate to with the app UI.
 * Sublclasses must implement {@link #performSync}, but otherwise have no other requirements.
 */
public abstract class EasSyncHandler {
    private static final String DEVICE_TYPE = "Android";
    private static final String USER_AGENT = DEVICE_TYPE + '/' + Build.VERSION.RELEASE + '-' +
        Eas.CLIENT_VERSION;

    protected final Context mContext;
    protected final ContentResolver mContentResolver;
    protected final Account mAccount;
    protected final HostAuth mHostAuth;
    protected final Mailbox mMailbox;
    protected final Bundle mSyncExtras;
    protected final SyncResult mSyncResult;

    // Bookkeeping for interrupting a sync. This is primarily for use by Ping (there's currently
    // no mechanism for stopping a sync).
    // Access to these variables should be synchronized on this.
    protected HttpPost mPendingPost = null;
    protected boolean mStopped = false;

    /**
     * Status values to indicate success or manner of failure when sending a single Message.
     */
    public enum SyncStatus {
        // Message sent successfully.
        SUCCESS,
        // Sync failed due to an I/O error (file or network).
        FAILURE_IO,
        // Sync failed due to login problems.
        FAILURE_LOGIN,
        // Sync failed due to login problems.
        FAILURE_SECURITY,
        // Sync failed due to bad message data.
        FAILURE_MESSAGE,
        FAILURE_OTHER
    }

    protected EasSyncHandler(final Context context, final ContentResolver contentResolver,
            final Account account, final Mailbox mailbox, final Bundle syncExtras,
            final SyncResult syncResult) {
        mContext = context;
        mContentResolver = contentResolver;
        mAccount = account;
        mHostAuth = HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv);
        mMailbox = mailbox;
        mSyncExtras = syncExtras;
        mSyncResult = syncResult;
    }

    /**
     * Create an instance of the appropriate subclass to handle sync for mailbox.
     * @param context
     * @param contentResolver
     * @param account The {@link Account} for mailbox.
     * @param mailbox The {@link Mailbox} to sync.
     * @param syncExtras The extras for this sync, for consumption by {@link #performSync}.
     * @param syncResult The output results for this sync, which may be written to by
     *      {@link #performSync}.
     * @return An appropriate EasSyncHandler for this mailbox, or null if this sync can't be
     *      handled.
     */
    public static EasSyncHandler getEasSyncHandler(final Context context,
            final ContentResolver contentResolver, final Account account, final Mailbox mailbox,
            final Bundle syncExtras, final SyncResult syncResult) {
        if (account != null && mailbox != null) {
            switch (mailbox.mType) {
                case Mailbox.TYPE_INBOX:
                case Mailbox.TYPE_MAIL:
                    return new EasMailboxSyncHandler(context, contentResolver, account, mailbox,
                            syncExtras, syncResult);
                case Mailbox.TYPE_OUTBOX:
                    return new EasOutboxSyncHandler(context, contentResolver, account, mailbox,
                            syncExtras, syncResult);
                case Mailbox.TYPE_EAS_ACCOUNT_MAILBOX:
                    return new EasAccountSyncHandler(context, contentResolver, account, mailbox,
                            syncExtras, syncResult);
            }
        }
        // Could not handle this sync.
        return null;
    }

    /**
     * Perform the sync, updating {@link #mSyncResult} as appropriate (which was passed in from
     * the system SyncManager and will be read by it on the way out).
     * In the case of errors, this function should not attempt any retries, but rather should
     * set the {@link SyncResult} to reflect the problem and let the system SyncManager handle
     * any retries etc.
     * @return An exit status code.
     * TODO: Do we really need a return value, or should we just use the SyncResult for this?
     */
    public abstract SyncStatus performSync();


    // Client - server communication.

    // Command timeout is the the time allowed for reading data from an open connection before an
    // IOException is thrown.  After a small added allowance, our watchdog alarm goes off (allowing
    // us to detect a silently dropped connection).  The allowance is defined below.
    protected static final long COMMAND_TIMEOUT = 30 * DateUtils.SECOND_IN_MILLIS;
    // Connection timeout is the time given to connect to the server before reporting an IOException
    protected static final long CONNECTION_TIMEOUT = 20 * DateUtils.SECOND_IN_MILLIS;

    private static final ConnPerRoute sConnPerRoute = new ConnPerRoute() {
        @Override
        public int getMaxForRoute(final HttpRoute route) {
            return 8;
        }
    };

    // TODO: Don't make a new one each time.
    private EmailClientConnectionManager getClientConnectionManager() {
        final HttpParams params = new BasicHttpParams();
        params.setIntParameter(ConnManagerPNames.MAX_TOTAL_CONNECTIONS, 25);
        params.setParameter(ConnManagerPNames.MAX_CONNECTIONS_PER_ROUTE, sConnPerRoute);
        final boolean ssl = mHostAuth.shouldUseSsl();
        final int port = mHostAuth.mPort;
        return EmailClientConnectionManager.newInstance(mContext, params, mHostAuth);
    }

    private HttpClient getHttpClient(final long timeout) {
        final HttpParams params = new BasicHttpParams();
        HttpConnectionParams.setConnectionTimeout(params, (int)(CONNECTION_TIMEOUT));
        HttpConnectionParams.setSoTimeout(params, (int)(timeout));
        HttpConnectionParams.setSocketBufferSize(params, 8192);
        return new DefaultHttpClient(getClientConnectionManager(), params);
    }

    private String makeAuthString() {
        final String cs = mHostAuth.mLogin + ":" + mHostAuth.mPassword;
        return "Basic " + Base64.encodeToString(cs.getBytes(), Base64.NO_WRAP);
    }

    private String makeUserString() {
        String deviceId = "";
        try {
            deviceId = Device.getDeviceId(mContext);
        } catch (final IOException e) {
            // TODO: Make Device.getDeviceId not throw IOException, if possible.
            // Otherwise use a better deviceId default.
            deviceId = "0";
        }
        return "&User=" + Uri.encode(mHostAuth.mLogin) + "&DeviceId=" +
                deviceId + "&DeviceType=" + DEVICE_TYPE;
    }

    private String makeBaseUriString() {
        return EmailClientConnectionManager.makeScheme(mHostAuth.shouldUseSsl(),
                mHostAuth.shouldTrustAllServerCerts(), mHostAuth.mClientCertAlias) +
                "://" + mHostAuth.mAddress + "/Microsoft-Server-ActiveSync";
    }

    private String makeUriString(final String cmd, final String extra) {
        String uriString = makeBaseUriString();
        if (cmd != null) {
            uriString += "?Cmd=" + cmd + makeUserString();
        }
        if (extra != null) {
            uriString += extra;
        }
        return uriString;
    }

    protected String getProtocolVersion() {
        if (mAccount != null && mAccount.mProtocolVersion != null) {
            return mAccount.mProtocolVersion;
        }
        return Eas.DEFAULT_PROTOCOL_VERSION;
    }

    /**
     * Set standard HTTP headers, using a policy key if required
     * @param method the method we are going to send
     * @param usePolicyKey whether or not a policy key should be sent in the headers
     */
    private void setHeaders(final HttpRequestBase method, final boolean usePolicyKey) {
        method.setHeader("Authorization", makeAuthString());
        method.setHeader("MS-ASProtocolVersion", getProtocolVersion());
        method.setHeader("User-Agent", USER_AGENT);
        method.setHeader("Accept-Encoding", "gzip");
        if (usePolicyKey) {
            // If there's an account in existence, use its key; otherwise (we're creating the
            // account), send "0".  The server will respond with code 449 if there are policies
            // to be enforced
            String key = "0";
            if (mAccount != null) {
                final String accountKey = mAccount.mSecuritySyncKey;
                if (!TextUtils.isEmpty(accountKey)) {
                    key = accountKey;
                }
            }
            method.setHeader("X-MS-PolicyKey", key);
        }
    }

    protected EasResponse sendHttpClientPost(String cmd, final HttpEntity entity,
            final long timeout) throws IOException {
        final HttpClient client = getHttpClient(timeout);
        final boolean isPingCommand = cmd.equals("Ping");

        // Split the mail sending commands
        String extra = null;
        boolean msg = false;
        if (cmd.startsWith("SmartForward&") || cmd.startsWith("SmartReply&")) {
            final int cmdLength = cmd.indexOf('&');
            extra = cmd.substring(cmdLength);
            cmd = cmd.substring(0, cmdLength);
            msg = true;
        } else if (cmd.startsWith("SendMail&")) {
            msg = true;
        }

        final String us = makeUriString(cmd, extra);
        final HttpPost method = new HttpPost(URI.create(us));
        // Send the proper Content-Type header; it's always wbxml except for messages when
        // the EAS protocol version is < 14.0
        // If entity is null (e.g. for attachments), don't set this header
        final String protocolVersion = getProtocolVersion();
        final Double protocolVersionDouble = Eas.getProtocolVersionDouble(protocolVersion);
        if (msg && (protocolVersionDouble < Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE)) {
            method.setHeader("Content-Type", "message/rfc822");
        } else if (entity != null) {
            method.setHeader("Content-Type", "application/vnd.ms-sync.wbxml");
        }
        setHeaders(method, !isPingCommand);
        // NOTE
        // The next lines are added at the insistence of $VENDOR, who is seeing inappropriate
        // network activity related to the Ping command on some networks with some servers.
        // This code should be removed when the underlying issue is resolved
        if (isPingCommand) {
            method.setHeader("Connection", "close");
        }
        method.setEntity(entity);
        return executePostWithTimeout(client, method);
    }

    protected EasResponse sendHttpClientPost(final String cmd, final byte[] bytes)
            throws IOException {
        return sendHttpClientPost(cmd, new ByteArrayEntity(bytes), COMMAND_TIMEOUT);
    }

    private EasResponse executePostWithTimeout(final HttpClient client, final HttpPost method)
            throws IOException {
        synchronized (this) {
            if (mStopped) {
                mStopped = false;
                // If this gets stopped after the post actually starts, it throws an IOException.
                // Therefore if we get stopped here, let's throw the same sort of exception, so
                // callers can just equate IOException with the "this POST got killed for some
                // reason".
                throw new IOException("Sync was stopped before POST");
            }
           mPendingPost = method;
        }
        try {
            // TODO: The first argument below is probably bad.
            return EasResponse.fromHttpRequest(getClientConnectionManager(), client, method);
        } finally {
            synchronized (this) {
                mPendingPost = null;
            }
        }
    }

    // Communication with the application.

    // TODO: Consider bringing the EmailServiceStatus functions here?
    /**
     * Convenience wrapper to {@link EmailServiceStatus#syncMailboxStatus}.
     * @param statusCode
     * @param progress
     */
    protected void syncMailboxStatus(final int statusCode, final int progress) {
        EmailServiceStatus.syncMailboxStatus(mContentResolver, mSyncExtras, mMailbox.mId,
                statusCode, progress);
    }

    /**
     * Convenience wrapper to {@link EmailServiceStatus#sendMessageStatus}.
     * @param messageId
     * @param subject
     * @param statusCode
     * @param progress
     */
    protected void sendMessageStatus(final long messageId, final String subject,
            final int statusCode, final int progress) {
        EmailServiceStatus.sendMessageStatus(mContentResolver, mSyncExtras, messageId, subject,
                statusCode, progress);
    }

    /**
     * Convenience wrapper to {@link EmailServiceStatus#syncMailboxListStatus}.
     * @param statusCode
     * @param progress
     */
    protected void syncMailboxListStatus(final int statusCode, final int progress) {
        EmailServiceStatus.syncMailboxListStatus(mContentResolver, mSyncExtras, mAccount.mId,
                statusCode, progress);
    }
}
