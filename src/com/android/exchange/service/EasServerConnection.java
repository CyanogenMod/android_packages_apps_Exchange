package com.android.exchange.service;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Base64;

import com.android.emailcommon.internet.MimeUtility;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.AccountServiceProxy;
import com.android.emailcommon.utility.EmailClientConnectionManager;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;
import com.android.mail.utils.LogUtils;

import org.apache.http.HttpEntity;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPost;
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
import java.util.HashMap;

/**
 * Base class for communicating with an EAS server. Anything that needs to send messages to the
 * server can subclass this to get access to the {@link #sendHttpClientPost} family of functions.
 * TODO: This class has a regrettable name. It's not a connection, but rather a task that happens
 * to have (and use) a connection to the server.
 */
public abstract class EasServerConnection {
    /** Logging tag. */
    private static final String TAG = "EasServerConnection";

    /**
     * Timeout for establishing a connection to the server.
     */
    private static final long CONNECTION_TIMEOUT = 20 * DateUtils.SECOND_IN_MILLIS;

    /**
     * Timeout for http requests after the connection has been established.
     */
    protected static final long COMMAND_TIMEOUT = 30 * DateUtils.SECOND_IN_MILLIS;

    private static final String DEVICE_TYPE = "Android";
    protected static final String USER_AGENT = DEVICE_TYPE + '/' + Build.VERSION.RELEASE + '-' +
        Eas.CLIENT_VERSION;

    /** Message MIME type for EAS version 14 and later. */
    private static final String EAS_14_MIME_TYPE = "application/vnd.ms-sync.wbxml";

    private static final ConnPerRoute sConnPerRoute = new ConnPerRoute() {
        @Override
        public int getMaxForRoute(final HttpRoute route) {
            return 8;
        }
    };

    /**
     * Value for {@link #mStoppedReason} when we haven't been stopped.
     */
    public static final int STOPPED_REASON_NONE = 0;

    /**
     * Passed to {@link #stop} to indicate that this stop request should terminate this task.
     */
    public static final int STOPPED_REASON_ABORT = 1;

    /**
     * Passed to {@link #stop} to indicate that this stop request should restart this task (e.g. in
     * order to reload parameters).
     */
    public static final int STOPPED_REASON_RESTART = 2;

    private static String sDeviceId = null;

    protected final Context mContext;
    // TODO: Make this private if possible. Subclasses must be careful about altering the HostAuth
    // to not screw up any connection caching (use redirectHostAuth).
    protected final HostAuth mHostAuth;
    protected final Account mAccount;

    // Bookkeeping for interrupting a POST. This is primarily for use by Ping (there's currently
    // no mechanism for stopping a sync).
    // Access to these variables should be synchronized on this.
    private HttpPost mPendingPost = null;
    private boolean mStopped = false;
    private int mStoppedReason = STOPPED_REASON_NONE;

    /**
     * The protocol version to use, as a double. This is a cached value based on the protocol
     * version in {@link #mAccount}, so whenever that value is changed,
     * {@link #uncacheProtocolVersion()} must be called.
     */
    private double mProtocolVersionDouble = 0.0d;

    /**
     * The client for any requests made by this object. This is created lazily, and cleared
     * whenever our host auth is redirected.
     */
    private HttpClient mClient;

    /**
     * The connection manager for any requests made by this object. This is created lazily, and
     * cleared whenever our host auth is redirected.
     */
    private EmailClientConnectionManager mConnectionManager;


    /**
     * We want to reuse {@link EmailClientConnectionManager} across different requests to the same
     * {@link HostAuth}. Since HostAuths have unique ids, we can use that as the cache key.
     * All access to the cache must be synchronized in theory, although in practice since we don't
     * have concurrent requests to the same account it should never come up.
     */
    private static class ConnectionManagerCache {
        private final HashMap<Long, EmailClientConnectionManager> mMap =
                new HashMap<Long, EmailClientConnectionManager>();

        /**
         * Get a connection manager from the cache, or create one and add it if needed.
         * @param context The {@link Context}.
         * @param hostAuth The {@link HostAuth} to which we want to connect.
         * @return The connection manager for hostAuth.
         */
        public synchronized EmailClientConnectionManager getConnectionManager(
                final Context context, final HostAuth hostAuth) {
            EmailClientConnectionManager connectionManager = mMap.get(hostAuth.mId);
            if (connectionManager == null) {
                final HttpParams params = new BasicHttpParams();
                params.setIntParameter(ConnManagerPNames.MAX_TOTAL_CONNECTIONS, 25);
                params.setParameter(ConnManagerPNames.MAX_CONNECTIONS_PER_ROUTE, sConnPerRoute);
                final boolean ssl = hostAuth.shouldUseSsl();
                final int port = hostAuth.mPort;
                LogUtils.i(TAG, "Creating connection manager for port %d (%s)", port,
                        ssl ? "uses ssl" : "no ssl");
                connectionManager =
                        EmailClientConnectionManager.newInstance(context, params, hostAuth);
                // We don't save managers for validation/autodiscover
                if (hostAuth.isSaved()) {
                    mMap.put(hostAuth.mId, connectionManager);
                }
            }
            return connectionManager;
        }

        /**
         * Remove a connection manager from the cache. This is necessary when a {@link HostAuth} is
         * redirected or otherwise altered.
         * TODO: We should uncache when we delete accounts.
         * @param hostAuth The {@link HostAuth} whose connection manager should be deleted.
         */
        public synchronized void uncacheConnectionManager(final HostAuth hostAuth) {
            mMap.remove(hostAuth.mId);
        }
    }
    private static final ConnectionManagerCache sConnectionManagers = new ConnectionManagerCache();

    protected EasServerConnection(final Context context, final Account account,
            final HostAuth hostAuth) {
        mContext = context;
        mHostAuth = hostAuth;
        mAccount = account;
    }

    protected EasServerConnection(final Context context, final Account account) {
        this(context, account, HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv));
    }

    protected EmailClientConnectionManager getClientConnectionManager() {
        if (mConnectionManager == null) {
            mConnectionManager = sConnectionManagers.getConnectionManager(mContext, mHostAuth);
        }
        return mConnectionManager;
    }

    protected void redirectHostAuth(final String newAddress) {
        mClient = null;
        mConnectionManager = null;
        mHostAuth.mAddress = newAddress;
        if (mHostAuth.isSaved()) {
            sConnectionManagers.uncacheConnectionManager(mHostAuth);
            final ContentValues cv = new ContentValues(1);
            cv.put(EmailContent.HostAuthColumns.ADDRESS, newAddress);
            mHostAuth.update(mContext, cv);
        }
    }

    private HttpClient getHttpClient(final long timeout) {
        if (mClient == null) {
            final HttpParams params = new BasicHttpParams();
            HttpConnectionParams.setConnectionTimeout(params, (int)(CONNECTION_TIMEOUT));
            HttpConnectionParams.setSoTimeout(params, (int)(timeout));
            HttpConnectionParams.setSocketBufferSize(params, 8192);
            mClient = new DefaultHttpClient(getClientConnectionManager(), params);
        }
        return mClient;
    }

    private String makeAuthString() {
        final String cs = mHostAuth.mLogin + ":" + mHostAuth.mPassword;
        return "Basic " + Base64.encodeToString(cs.getBytes(), Base64.NO_WRAP);
    }

    private String makeUserString() {
        if (sDeviceId == null) {
            sDeviceId = new AccountServiceProxy(mContext).getDeviceId();
            if (sDeviceId == null) {
                LogUtils.e(TAG, "Could not get device id, defaulting to '0'");
                sDeviceId = "0";
            }
        }
        return "&User=" + Uri.encode(mHostAuth.mLogin) + "&DeviceId=" +
                sDeviceId + "&DeviceType=" + DEVICE_TYPE;
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

    /**
     * Get the protocol version for {@link #mAccount}, or a default if we can't determine it.
     * @return The protocol version for {@link #mAccount}, as a String.
     */
    private String getProtocolVersionString() {
        if (mAccount.mProtocolVersion != null) {
            return mAccount.mProtocolVersion;
        }
        return Eas.DEFAULT_PROTOCOL_VERSION;
    }

    /**
     * If a sync causes us to update our protocol version, this function must be called so that
     * subsequent calls to {@link #getProtocolVersion()} will do the right thing.
     */
    protected void uncacheProtocolVersion() {
        mProtocolVersionDouble = 0.0d;
    }

    /**
     * Get the protocol version for {@link #mAccount}, as a double. This function caches the result
     * of looking up the value so that subsequent calls do not have to repeat that.
     * @return The protocol version for {@link #mAccount}, as a double.
     */
    protected double getProtocolVersion() {
        if (mProtocolVersionDouble == 0.0d) {
            mProtocolVersionDouble = Eas.getProtocolVersionDouble(getProtocolVersionString());
        }
        return mProtocolVersionDouble;
    }

    /**
     * Send an http OPTIONS request to server.
     * @return The {@link EasResponse} from the Exchange server.
     * @throws IOException
     */
    protected EasResponse sendHttpClientOptions() throws IOException {
        // For OPTIONS, just use the base string and the single header
        final HttpOptions method = new HttpOptions(URI.create(makeBaseUriString()));
        method.setHeader("Authorization", makeAuthString());
        method.setHeader("User-Agent", USER_AGENT);
        return EasResponse.fromHttpRequest(getClientConnectionManager(),
                getHttpClient(COMMAND_TIMEOUT), method);
    }

    protected void resetAuthorization(final HttpPost post) {
        post.removeHeaders("Authorization");
        post.setHeader("Authorization", makeAuthString());
    }

    /**
     * Make an {@link HttpPost} for a specific request.
     * @param uri The uri for this request, as a {@link String}.
     * @param entity The {@link HttpEntity} for this request.
     * @param contentType The Content-Type for this request.
     * @param usePolicyKey Whether or not a policy key should be sent.
     * @return
     */
    protected HttpPost makePost(final String uri, final HttpEntity entity, final String contentType,
            final boolean usePolicyKey) {
        final HttpPost post = new HttpPost(uri);
        post.setHeader("Authorization", makeAuthString());
        post.setHeader("MS-ASProtocolVersion", getProtocolVersionString());
        post.setHeader("User-Agent", USER_AGENT);
        post.setHeader("Accept-Encoding", "gzip");
        if (contentType != null) {
            post.setHeader("Content-Type", contentType);
        }
        if (usePolicyKey) {
            // If there's an account in existence, use its key; otherwise (we're creating the
            // account), send "0".  The server will respond with code 449 if there are policies
            // to be enforced
            final String key;
            final String accountKey = mAccount.mSecuritySyncKey;
            if (!TextUtils.isEmpty(accountKey)) {
                key = accountKey;
            } else {
                key = "0";
            }
            post.setHeader("X-MS-PolicyKey", key);
        }
        post.setEntity(entity);
        return post;
    }

    /**
     * Send a POST request to the server.
     * @param cmd The command we're sending to the server.
     * @param entity The {@link HttpEntity} containing the payload of the message.
     * @param timeout The timeout for this POST.
     * @return The response from the Exchange server.
     * @throws IOException
     */
    protected EasResponse sendHttpClientPost(String cmd, final HttpEntity entity,
            final long timeout) throws IOException {
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

        // Send the proper Content-Type header; it's always wbxml except for messages when
        // the EAS protocol version is < 14.0
        // If entity is null (e.g. for attachments), don't set this header
        final String contentType;
        if (msg && (getProtocolVersion() < Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE)) {
            contentType = MimeUtility.MIME_TYPE_RFC822;
        } else if (entity != null) {
            contentType = EAS_14_MIME_TYPE;
        }
        else {
            contentType = null;
        }
        final HttpPost method = makePost(makeUriString(cmd, extra), entity, contentType,
                !isPingCommand);
        // NOTE
        // The next lines are added at the insistence of $VENDOR, who is seeing inappropriate
        // network activity related to the Ping command on some networks with some servers.
        // This code should be removed when the underlying issue is resolved
        if (isPingCommand) {
            method.setHeader("Connection", "close");
        }
        return executePost(method, timeout);
    }

    protected EasResponse sendHttpClientPost(final String cmd, final byte[] bytes,
            final long timeout) throws IOException {
        final ByteArrayEntity entity;
        if (bytes == null) {
            entity = null;
        } else {
            entity = new ByteArrayEntity(bytes);
        }
        return sendHttpClientPost(cmd, entity, timeout);
    }

    protected EasResponse sendHttpClientPost(final String cmd, final byte[] bytes)
            throws IOException {
        return sendHttpClientPost(cmd, bytes, COMMAND_TIMEOUT);
    }

    /**
     * Executes an {@link HttpPost}.
     * Note: this function must not be called by multiple threads concurrently. Only one thread may
     * send server requests from a particular object at a time.
     * @param method The post to execute.
     * @param timeout The timeout to use.
     * @return The response from the Exchange server.
     * @throws IOException
     */
    protected EasResponse executePost(final HttpPost method, final long timeout)
            throws IOException {
        // The synchronized blocks are here to support the stop() function, specifically to handle
        // when stop() is called first. Notably, they are NOT here in order to guard against
        // concurrent access to this function, which is not supported.
        synchronized (this) {
            if (mStopped) {
                mStopped = false;
                // If this gets stopped after the POST actually starts, it throws an IOException.
                // Therefore if we get stopped here, let's throw the same sort of exception, so
                // callers can just equate IOException with the "this POST got killed for some
                // reason".
                throw new IOException("Command was stopped before POST");
            }
           mPendingPost = method;
        }
        try {
            return EasResponse.fromHttpRequest(getClientConnectionManager(), getHttpClient(timeout),
                    method);
        } finally {
            synchronized (this) {
                mPendingPost = null;
                mStoppedReason = STOPPED_REASON_NONE;
            }
        }
    }

    protected EasResponse executePost(final HttpPost method) throws IOException {
        return executePost(method, COMMAND_TIMEOUT);
    }

    /**
     * If called while this object is executing a POST, interrupt it with an {@link IOException}.
     * Otherwise cause the next attempt to execute a POST to be interrupted with an
     * {@link IOException}.
     * @param reason The reason for requesting a stop. This should be one of the STOPPED_REASON_*
     *               constants defined in this class, other than {@link #STOPPED_REASON_NONE} which
     *               is used to signify that no stop has occurred.
     *               This class simply stores the value; subclasses are responsible for checking
     *               this value when catching the {@link IOException} and responding appropriately.
     * @return Whether we were in the middle of a POST.
     */
    public synchronized boolean stop(final int reason) {
        final boolean isMidPost = (mPendingPost != null);
        LogUtils.i(TAG, "%s with reason %d", (isMidPost ? "Interrupt" : "Stop next"), reason);
        mStoppedReason = reason;
        if (isMidPost) {
            mPendingPost.abort();
        } else {
            mStopped = true;
        }
        return isMidPost;
    }

    /**
     * Check the reason of the last {@link #stop} request.
     * @return The reason supplied to the last call to {@link #stop}, or
     *     {@link #STOPPED_REASON_NONE} if we haven't been stopped since the last successful POST.
     */
    protected int getStoppedReason() {
        return mStoppedReason;
    }

    /**
     * Convenience method for adding a Message to an account's outbox
     * @param msg the message to send
     */
    protected void sendMessage(final EmailContent.Message msg) {
        long mailboxId = Mailbox.findMailboxOfType(mContext, mAccount.mId, Mailbox.TYPE_OUTBOX);
        // TODO: Improve system mailbox handling.
        if (mailboxId == Mailbox.NO_MAILBOX) {
            LogUtils.d(TAG, "No outbox for account %d, creating it", mAccount.mId);
            final Mailbox outbox =
                    Mailbox.newSystemMailbox(mContext, mAccount.mId, Mailbox.TYPE_OUTBOX);
            outbox.save(mContext);
            mailboxId = outbox.mId;
        }
        msg.mMailboxKey = mailboxId;
        msg.mAccountKey = mAccount.mId;
        msg.save(mContext);
        requestSyncForMailbox(new android.accounts.Account(mAccount.mEmailAddress,
                Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE), EmailContent.AUTHORITY, mailboxId);
    }

    /**
     * Issue a {@link android.content.ContentResolver#requestSync} for a specific mailbox.
     * @param amAccount The {@link android.accounts.Account} for the account we're pinging.
     * @param authority The authority for the mailbox that needs to sync.
     * @param mailboxId The id of the mailbox that needs to sync.
     */
    protected static void requestSyncForMailbox(final android.accounts.Account amAccount,
            final String authority, final long mailboxId) {
        final Bundle extras = new Bundle(1);
        extras.putLong(Mailbox.SYNC_EXTRA_MAILBOX_ID, mailboxId);
        ContentResolver.requestSync(amAccount, authority, extras);
    }

}
