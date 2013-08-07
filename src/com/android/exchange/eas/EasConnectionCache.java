package com.android.exchange.eas;

import android.content.Context;

import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.utility.EmailClientConnectionManager;
import com.android.exchange.Eas;
import com.android.mail.utils.LogUtils;

import org.apache.http.conn.params.ConnManagerPNames;
import org.apache.http.conn.params.ConnPerRoute;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;

import java.util.HashMap;

/**
 * Manage all {@link EmailClientConnectionManager}s used by Exchange operations.
 * When making connections for persisted accounts, this class will cache and reuse connections
 * as much as possible. All access of connection objects should accordingly go through this class.
 *
 * We use {@link HostAuth}'s id as the cache key. Multiple calls to {@link #getConnectionManager}
 * with {@link HostAuth} objects with the same id will get the same connection object returned,
 * i.e. we assume that the rest of the contents of the {@link HostAuth} objects are also the same,
 * not just the id. If the {@link HostAuth} changes or is deleted, {@link #uncacheConnectionManager}
 * must be called.
 *
 * This cache is a singleton since the whole point is to not have multiples.
 */
public class EasConnectionCache {

    private final HashMap<Long, EmailClientConnectionManager> mConnectionMap;

    private static final ConnPerRoute sConnPerRoute = new ConnPerRoute() {
        @Override
        public int getMaxForRoute(final HttpRoute route) {
            return 8;
        }
    };

    /** The singleton instance of the cache. */
    private static EasConnectionCache sCache = null;

    /** Accessor for the cache singleton. */
    public static EasConnectionCache instance() {
        if (sCache == null) {
            sCache = new EasConnectionCache();
        }
        return sCache;
    }

    private EasConnectionCache() {
        mConnectionMap = new HashMap<Long, EmailClientConnectionManager>();
    }

    /**
     * Create an {@link EmailClientConnectionManager} for this {@link HostAuth}.
     * @param context The {@link Context}.
     * @param hostAuth The {@link HostAuth} to which we want to connect.
     * @return The {@link EmailClientConnectionManager} for hostAuth.
     */
    private EmailClientConnectionManager createConnectionManager(final Context context,
            final HostAuth hostAuth) {
        LogUtils.i(Eas.LOG_TAG, "Creating connection for HostAuth %d", hostAuth.mId);
        final HttpParams params = new BasicHttpParams();
        params.setIntParameter(ConnManagerPNames.MAX_TOTAL_CONNECTIONS, 25);
        params.setParameter(ConnManagerPNames.MAX_CONNECTIONS_PER_ROUTE, sConnPerRoute);
        return EmailClientConnectionManager.newInstance(context, params, hostAuth);
    }

    /**
     * Get the correct {@link EmailClientConnectionManager} for a {@link HostAuth} from our cache.
     * If it's not in the cache, create and add it.
     * @param context The {@link Context}.
     * @param hostAuth The {@link HostAuth} to which we want to connect.
     * @return The {@link EmailClientConnectionManager} for hostAuth.
     */
    private synchronized EmailClientConnectionManager getCachedConnectionManager(
            final Context context, final HostAuth hostAuth) {
        LogUtils.i(Eas.LOG_TAG, "Reusing cached connection for HostAuth %d", hostAuth.mId);
        EmailClientConnectionManager connectionManager = mConnectionMap.get(hostAuth.mId);
        if (connectionManager == null) {
            connectionManager = createConnectionManager(context, hostAuth);
            mConnectionMap.put(hostAuth.mId, connectionManager);
        }
        return connectionManager;
    }

    /**
     * Get the correct {@link EmailClientConnectionManager} for a {@link HostAuth}. If the
     * {@link HostAuth} is persistent, then use the cache for this request.
     * @param context The {@link Context}.
     * @param hostAuth The {@link HostAuth} to which we want to connect.
     * @return The {@link EmailClientConnectionManager} for hostAuth.
     */
    public EmailClientConnectionManager getConnectionManager(
            final Context context, final HostAuth hostAuth) {
        final EmailClientConnectionManager connectionManager;
        // We only cache the connection manager for persisted HostAuth objects, i.e. objects
        // whose ids are permanent and won't get reused by other transient HostAuth objects.
        if (hostAuth.isSaved()) {
            connectionManager = getCachedConnectionManager(context, hostAuth);
        } else {
            connectionManager = createConnectionManager(context, hostAuth);
        }
        return connectionManager;
    }

    /**
     * Remove a connection manager from the cache. This is necessary when a {@link HostAuth} is
     * redirected or otherwise altered. It's not strictly necessary but good to also call this
     * when a {@link HostAuth} is deleted, i.e. when an account is removed.
     * @param hostAuth The {@link HostAuth} whose connection manager should be deleted.
     */
    public synchronized void uncacheConnectionManager(final HostAuth hostAuth) {
        LogUtils.i(Eas.LOG_TAG, "Uncaching connection for HostAuth %d", hostAuth.mId);
        mConnectionMap.remove(hostAuth.mId);
    }
}
