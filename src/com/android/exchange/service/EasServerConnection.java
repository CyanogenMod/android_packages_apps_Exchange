package com.android.exchange.service;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Base64;

import com.android.emailcommon.Device;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.utility.EmailClientConnectionManager;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;

import org.apache.http.HttpEntity;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpOptions;
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
import java.util.HashMap;

/**
 * Base class for communicating with an EAS server. Anything that needs to send messages to the
 * server can subclass this to get access to the {@link #sendHttpClientPost} family of functions.
 */
public abstract class EasServerConnection {
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


    private static final ConnPerRoute sConnPerRoute = new ConnPerRoute() {
        @Override
        public int getMaxForRoute(final HttpRoute route) {
            return 8;
        }
    };

    protected final Context mContext;
    protected final HostAuth mHostAuth;

    // Bookkeeping for interrupting a POST. This is primarily for use by Ping (there's currently
    // no mechanism for stopping a sync).
    // Access to these variables should be synchronized on this.
    private HttpPost mPendingPost = null;
    private boolean mStopped = false;

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
                connectionManager =
                        EmailClientConnectionManager.newInstance(context, params, hostAuth);
                // We don't save managers for validation/autodiscover
                if (hostAuth.mId != HostAuth.NOT_SAVED) {
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

    protected EasServerConnection(final Context context, final HostAuth hostAuth) {
        mContext = context;
        mHostAuth = hostAuth;
    }

    protected EmailClientConnectionManager getClientConnectionManager() {
        return sConnectionManagers.getConnectionManager(mContext, mHostAuth);
    }

    protected void redirectHostAuth(final String newAddress) {
        mHostAuth.mAddress = newAddress;
        sConnectionManagers.uncacheConnectionManager(mHostAuth);
    }

    private HttpClient getHttpClient(final EmailClientConnectionManager connectionManager,
            final long timeout) {
        final HttpParams params = new BasicHttpParams();
        HttpConnectionParams.setConnectionTimeout(params, (int)(CONNECTION_TIMEOUT));
        HttpConnectionParams.setSoTimeout(params, (int)(timeout));
        HttpConnectionParams.setSocketBufferSize(params, 8192);
        return new DefaultHttpClient(connectionManager, params);
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

    /**
     * Get the protocol version for an account, or a default if we can't determine it.
     * @param account The account whose protocol version we want to get.
     * @return The protocol version for account, as a String.
     */
    protected String getProtocolVersion(final Account account) {
        if (account != null && account.mProtocolVersion != null) {
            return account.mProtocolVersion;
        }
        return Eas.DEFAULT_PROTOCOL_VERSION;
    }

    /**
     * Set standard HTTP headers, using a policy key if required
     * @param account The Account for which we are communicating.
     * @param method the method we are going to send
     * @param usePolicyKey whether or not a policy key should be sent in the headers
     */
    private void setHeaders(final Account account, final HttpRequestBase method,
            final boolean usePolicyKey) {
        method.setHeader("Authorization", makeAuthString());
        method.setHeader("MS-ASProtocolVersion", getProtocolVersion(account));
        method.setHeader("User-Agent", USER_AGENT);
        method.setHeader("Accept-Encoding", "gzip");
        if (usePolicyKey) {
            // If there's an account in existence, use its key; otherwise (we're creating the
            // account), send "0".  The server will respond with code 449 if there are policies
            // to be enforced
            String key = "0";
            if (account != null) {
                final String accountKey = account.mSecuritySyncKey;
                if (!TextUtils.isEmpty(accountKey)) {
                    key = accountKey;
                }
            }
            method.setHeader("X-MS-PolicyKey", key);
        }
    }

    /**
     * Send an http OPTIONS request to server.
     * @return The {@link EasResponse} from the Exchange server.
     * @throws IOException
     */
    protected EasResponse sendHttpClientOptions() throws IOException {
        final EmailClientConnectionManager connectionManager = getClientConnectionManager();
        // For OPTIONS, just use the base string and the single header
        final HttpOptions method = new HttpOptions(URI.create(makeBaseUriString()));
        method.setHeader("Authorization", makeAuthString());
        method.setHeader("User-Agent", USER_AGENT);
        final HttpClient client = getHttpClient(connectionManager, COMMAND_TIMEOUT);
        return EasResponse.fromHttpRequest(connectionManager, client, method);
    }

    /**
     * Send a POST request to the server.
     * @param account The {@link Account} for which we're sending the POST.
     * @param cmd The command we're sending to the server.
     * @param entity The {@link HttpEntity} containing the payload of the message.
     * @param timeout The timeout for this POST.
     * @return The response from the Exchange server.
     * @throws IOException
     */
    protected EasResponse sendHttpClientPost(final Account account,
            String cmd, final HttpEntity entity, final long timeout) throws IOException {
        final EmailClientConnectionManager connectionManager = getClientConnectionManager();
        final HttpClient client = getHttpClient(connectionManager, timeout);
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
        final String protocolVersion = getProtocolVersion(account);
        final Double protocolVersionDouble = Eas.getProtocolVersionDouble(protocolVersion);
        if (msg && (protocolVersionDouble < Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE)) {
            method.setHeader("Content-Type", "message/rfc822");
        } else if (entity != null) {
            method.setHeader("Content-Type", "application/vnd.ms-sync.wbxml");
        }
        setHeaders(account, method, !isPingCommand);
        // NOTE
        // The next lines are added at the insistence of $VENDOR, who is seeing inappropriate
        // network activity related to the Ping command on some networks with some servers.
        // This code should be removed when the underlying issue is resolved
        if (isPingCommand) {
            method.setHeader("Connection", "close");
        }
        method.setEntity(entity);

        synchronized (this) {
            if (mStopped) {
                mStopped = false;
                // If this gets stopped after the POST actually starts, it throws an IOException.
                // Therefore if we get stopped here, let's throw the same sort of exception, so
                // callers can just equate IOException with the "this POST got killed for some
                // reason".
                throw new IOException("Sync was stopped before POST");
            }
           mPendingPost = method;
        }
        try {
            return EasResponse.fromHttpRequest(connectionManager, client, method);
        } finally {
            synchronized (this) {
                mPendingPost = null;
            }
        }
    }

    protected EasResponse sendHttpClientPost(final Account account, final String cmd,
            final byte[] bytes, final long timeout) throws IOException {
        return sendHttpClientPost(account, cmd, new ByteArrayEntity(bytes), timeout);
    }

    protected EasResponse sendHttpClientPost(final Account account, final String cmd,
            final byte[] bytes) throws IOException {
        return sendHttpClientPost(account, cmd, bytes, COMMAND_TIMEOUT);
    }

    /**
     * Stop the current request. If we're in the middle of the POST, abort it, otherwise prevent
     * the next POST from happening. This second part is necessary in cases where the stop request
     * happens while we're setting up the POST but before we're actually in it.
     * TODO: We also want to do something reasonable if the stop request comes in after the POST
     * responds.
     */
    public synchronized void stop() {
        if (mPendingPost != null) {
            mPendingPost.abort();
        } else {
            mStopped = true;
        }
    }
}
