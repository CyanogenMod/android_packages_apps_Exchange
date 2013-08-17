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

package com.android.exchange.eas;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.os.Bundle;
import android.text.format.DateUtils;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.service.EasServerConnection;
import com.android.mail.utils.LogUtils;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;

import java.io.IOException;

/**
 * Base class for all Exchange operations that use a POST to talk to the server.
 *
 * The core of this class is {@link #performOperation}, which provides the skeleton of making
 * a request, handling common errors, and setting fields on the {@link SyncResult} if there is one.
 * This class abstracts the connection handling from its subclasses and callers.
 *
 * A subclass must implement the abstract functions below that create the request and parse the
 * response. There are also a set of functions that a subclass may override if it's substantially
 * different from the "normal" operation (e.g. most requests use the same request URI, but auto
 * discover deviates since it's not account-specific), but the default implementation should suffice
 * for most. The subclass must also define a public function which calls {@link #performOperation},
 * possibly doing nothing other than that. (I chose to force subclasses to do this, rather than
 * provide that function in the base class, in order to force subclasses to consider, for example,
 * whether it needs a {@link SyncResult} parameter, and what the proper name for the "doWork"
 * function ought to be for the subclass.)
 */
public abstract class EasOperation {
    public static final String LOG_TAG = Eas.LOG_TAG;

    /** The maximum number of server redirects we allow before returning failure. */
    private static final int MAX_REDIRECTS = 3;

    /** Message MIME type for EAS version 14 and later. */
    private static final String EAS_14_MIME_TYPE = "application/vnd.ms-sync.wbxml";

    /** Error code indicating the operation was cancelled via {@link #abort}. */
    public static final int RESULT_ABORT = -1;
    /** Error code indicating the operation was cancelled via {@link #restart}. */
    public static final int RESULT_RESTART = -2;
    /** Error code indicating the Exchange servers redirected too many times. */
    public static final int RESULT_TOO_MANY_REDIRECTS = -3;
    /** Error code indicating the request failed due to a network problem. */
    public static final int RESULT_REQUEST_FAILURE = -4;
    /** Error code indicating a 403 (forbidden) error. */
    public static final int RESULT_FORBIDDEN = -5;
    /** Error code indicating an unresolved provisioning error. */
    public static final int RESULT_PROVISIONING_ERROR = -6;
    /** Error code indicating an authentication problem. */
    public static final int RESULT_AUTHENTICATION_ERROR = -7;
    /** Error code indicating some other failure. */
    public static final int RESULT_OTHER_FAILURE = -8;

    protected final Context mContext;

    /**
     * The account id for this operation.
     * Currently only used for handling provisioning errors. Ideally we should minimize the creep
     * of how this gets used (i.e. don't let it get to the intertwined state of the past).
     */
    private final long mAccountId;
    private final EasServerConnection mConnection;

    protected EasOperation(final Context context, final Account account, final HostAuth hostAuth) {
        this(context, account.mId, new EasServerConnection(context, account, hostAuth));
    }

    protected EasOperation(final Context context, final Account account) {
        this(context, account, HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv));
    }

    protected EasOperation(final Context context, final long accountId,
            final EasServerConnection connection) {
        mContext = context;
        mAccountId = accountId;
        mConnection = connection;
    }

    /**
     * Request that this operation terminate. Intended for use by the sync service to interrupt
     * running operations, primarily Ping.
     */
    public final void abort() {
        mConnection.stop(EasServerConnection.STOPPED_REASON_ABORT);
    }

    /**
     * Request that this operation restart. Intended for use by the sync service to interrupt
     * running operations, primarily Ping.
     */
    public final void restart() {
        mConnection.stop(EasServerConnection.STOPPED_REASON_RESTART);
    }

    /**
     * The skeleton of performing an operation. This function handles all the common code and
     * error handling, calling into virtual functions that are implemented or overridden by the
     * subclass to do the operation-specific logic.
     *
     * The result codes work as follows:
     * - Negative values indicate common error codes and are defined above (the various RESULT_*
     *   constants).
     * - Non-negative values indicate the result of {@link #handleResponse}. These are obviously
     *   specific to the subclass, and may indicate success or error conditions.
     *
     * The common error codes primarily indicate conditions that occur when performing the POST
     * itself, such as network errors and handling of the HTTP response. However, some errors that
     * can be indicated in the HTTP response code can also be indicated in the payload of the
     * response as well, so {@link #handleResponse} should in those cases return the appropriate
     * negative result code, which will be handled the same as if it had been indicated in the HTTP
     * response code.
     *
     * @param syncResult If this operation is a sync, the {@link SyncResult} object that should
     *                   be written to for this sync; otherwise null.
     * @return A result code for the outcome of this operation, as described above.
     */
    protected final int performOperation(final SyncResult syncResult) {
        // We handle server redirects by looping, but we need to protect against too much looping.
        int redirectCount = 0;

        do {
            // Perform the POST and handle exceptions.
            final EasResponse response;
            try {
                final HttpPost post = mConnection.makePost(getRequestUri(), getRequestEntity(),
                        getRequestContentType(), addPolicyKeyHeaderToRequest());
                response = mConnection.executePost(post, getTimeout());
            } catch (final IOException e) {
                // If we were stopped, return the appropriate result code.
                switch (mConnection.getStoppedReason()) {
                    case EasServerConnection.STOPPED_REASON_ABORT:
                        return RESULT_ABORT;
                    case EasServerConnection.STOPPED_REASON_RESTART:
                        return RESULT_RESTART;
                    default:
                        break;
                }
                // If we're here, then we had a IOException that's not from a stop request.
                LogUtils.e(LOG_TAG, e, "Exception while sending request");
                if (syncResult != null) {
                    ++syncResult.stats.numIoExceptions;
                }
                return RESULT_REQUEST_FAILURE;
            } catch (final IllegalStateException e) {
                // Subclasses use ISE to signal a hard error when building the request.
                // TODO: If executePost can throw an ISE, we may want to tidy this up a bit.
                LogUtils.e(LOG_TAG, e, "Exception while sending request");
                if (syncResult != null) {
                    syncResult.databaseError = true;
                }
                return RESULT_OTHER_FAILURE;
            }

            // The POST completed, so process the response.
            try {
                final int result;
                // First off, the success case.
                if (response.isSuccess()) {
                    try {
                        result = handleResponse(response, syncResult);
                        if (result >= 0) {
                            return result;
                        }
                    } catch (final IOException e) {
                        LogUtils.e(LOG_TAG, e, "Exception while handling response");
                        if (syncResult != null) {
                            ++syncResult.stats.numParseExceptions;
                        }
                        return RESULT_OTHER_FAILURE;
                    }
                } else {
                    result = RESULT_OTHER_FAILURE;
                }

                // If this operation has distinct handling for 403 errors, do that.
                if (result == RESULT_FORBIDDEN || (response.isForbidden() && handleForbidden())) {
                    LogUtils.e(LOG_TAG, "Forbidden response");
                    if (syncResult != null) {
                        // TODO: Is this the best stat to increment?
                        ++syncResult.stats.numAuthExceptions;
                    }
                    return RESULT_FORBIDDEN;
                }

                // Handle provisioning errors.
                if (result == RESULT_PROVISIONING_ERROR || response.isProvisionError()) {
                    if (handleProvisionError(syncResult, mAccountId)) {
                        // The provisioning error has been taken care of, so we should re-do this
                        // request.
                        continue;
                    }
                    if (syncResult != null) {
                        // TODO: Is this the best stat to increment?
                        ++syncResult.stats.numAuthExceptions;
                    }
                    return RESULT_PROVISIONING_ERROR;
                }

                // Handle authentication errors.
                if (response.isAuthError()) {
                    LogUtils.e(LOG_TAG, "Authentication error");
                    if (syncResult != null) {
                        ++syncResult.stats.numAuthExceptions;
                    }
                    handleAuthError();
                    return RESULT_AUTHENTICATION_ERROR;
                }

                // Handle redirects.
                if (response.isRedirectError()) {
                    ++redirectCount;
                    mConnection.redirectHostAuth(response.getRedirectAddress());
                    // Note that unlike other errors, we do NOT return here; we just keep looping.
                } else {
                    // All other errors.
                    LogUtils.e(LOG_TAG, "Generic error");
                    if (syncResult != null) {
                        // TODO: Is this the best stat to increment?
                        ++syncResult.stats.numIoExceptions;
                    }
                    return RESULT_OTHER_FAILURE;
                }
            } finally {
                response.close();
            }
        } while (redirectCount < MAX_REDIRECTS);

        // Non-redirects return immediately after handling, so the only way to reach here is if we
        // looped too many times.
        LogUtils.e(LOG_TAG, "Too many redirects");
        if (syncResult != null) {
           syncResult.tooManyRetries = true;
        }
        return RESULT_TOO_MANY_REDIRECTS;
    }

    /**
     * Handling for authentication errors. Should be the same for all operations.
     * TODO: Implement.
     */
    private final void handleAuthError() {

    }

    /**
     * The following functions MUST be overridden by subclasses; these are things that are unique
     * to each operation.
     */

    /**
     * Get the name of the operation, used as the "Cmd=XXX" query param in the request URI. Note
     * that if you override {@link #getRequestUri}, then this function may be unused, but it's
     * abstract in order to make it impossible to omit for the subclasses that do need it.
     * @return The name of the command for this operation as defined by the EAS protocol.
     */
    protected abstract String getCommand();

    /**
     * Build the {@link HttpEntity} which us used to construct the POST. Typically this function
     * will build the Exchange request using a {@link Serializer} and then call {@link #makeEntity}.
     * @return The {@link HttpEntity} to pass to {@link EasServerConnection#makePost}.
     * @throws IOException
     */
    protected abstract HttpEntity getRequestEntity() throws IOException;

    /**
     * Parse the response from the Exchange perform whatever actions are dictated by that.
     * @param response The {@link EasResponse} to our request.
     * @param syncResult The {@link SyncResult} object for this operation, or null if we're not
     *                   handling a sync.
     * @return A result code. Non-negative values are returned directly to the caller; negative
     *         values
     *
     * that is returned to the caller of {@link #performOperation}.
     * @throws IOException
     */
    protected abstract int handleResponse(final EasResponse response, final SyncResult syncResult)
            throws IOException;

    /**
     * The following functions may be overriden by a subclass, but most operations will not need
     * to do so.
     */

    /**
     * Get the URI for the Exchange server and this operation. Most (signed in) operations need
     * not override this; the notable operation that needs to override it is auto-discover.
     * @return
     */
    protected String getRequestUri() {
        return mConnection.makeUriString(getCommand());
    }

    /**
     * @return Whether to set the X-MS-PolicyKey header. Only Ping does not want this header.
     */
    protected boolean addPolicyKeyHeaderToRequest() {
        return true;
    }

    /**
     * @return The content type of this request.
     */
    protected String getRequestContentType() {
        return EAS_14_MIME_TYPE;
    }

    /**
     * @return The timeout to use for the POST.
     */
    protected long getTimeout() {
        return 30 * DateUtils.SECOND_IN_MILLIS;
    }

    /**
     * If 403 responses should be handled in a special way, this function should be overridden to
     * do that.
     * @return Whether we handle 403 responses; if false, then treat 403 as a provisioning error.
     */
    protected boolean handleForbidden() {
        return false;
    }

    /**
     * Handle a provisioning error. Subclasses may override this to do something different, e.g.
     * to validate rather than actually do the provisioning.
     * @param syncResult
     * @param accountId
     * @return
     */
    protected boolean handleProvisionError(final SyncResult syncResult, final long accountId) {
        final EasProvision provisionOperation = new EasProvision(mContext, accountId, mConnection);
        return provisionOperation.provision(syncResult, accountId);
    }

    /**
     * Convenience methods for subclasses to use.
     */

    /**
     * Convenience method to make an {@link HttpEntity} from {@link Serializer}.
     */
    protected final HttpEntity makeEntity(final Serializer s) {
        return new ByteArrayEntity(s.toByteArray());
    }

    /**
     * @return The protocol version to use.
     */
    protected final double getProtocolVersion() {
        return mConnection.getProtocolVersion();
    }

    /**
     * @return Our useragent.
     */
    protected final String getUserAgent() {
        return mConnection.getUserAgent();
    }

    /**
     * Convenience method for adding a Message to an account's outbox
     * @param account The {@link Account} from which to send the message.
     * @param msg the message to send
     */
    protected final void sendMessage(final Account account, final EmailContent.Message msg) {
        long mailboxId = Mailbox.findMailboxOfType(mContext, account.mId, Mailbox.TYPE_OUTBOX);
        // TODO: Improve system mailbox handling.
        if (mailboxId == Mailbox.NO_MAILBOX) {
            LogUtils.d(LOG_TAG, "No outbox for account %d, creating it", account.mId);
            final Mailbox outbox =
                    Mailbox.newSystemMailbox(mContext, account.mId, Mailbox.TYPE_OUTBOX);
            outbox.save(mContext);
            mailboxId = outbox.mId;
        }
        msg.mMailboxKey = mailboxId;
        msg.mAccountKey = account.mId;
        msg.save(mContext);
        requestSyncForMailbox(new android.accounts.Account(account.mEmailAddress,
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
