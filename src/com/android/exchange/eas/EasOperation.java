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

    protected final Context mContext;
    private final EasServerConnection mConnection;

    protected EasOperation(final Context context, final Account account, final HostAuth hostAuth) {
        mContext = context;
        mConnection = new EasServerConnection(context, account, hostAuth);
    }

    protected EasOperation(final Context context, final Account account) {
        this(context, account, HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv));
    }

    /**
     * The below constants are the result codes (returned from {@link #performOperation} for errors
     * that occur in the base class, i.e. those that happen either during making the request or due
     * to the common error handling. These values are all negative, leaving non-negative values for
     * {@link #handleResponse}. If {@link #performOperation} returns a negative value, then it's
     * most likely due to an error in the base class. Subclasses should generally not return
     * negative values from {@link #handleResponse}, except for possibly
     * {@link #RESULT_OTHER_FAILURE}.
     */

    /** Error code for the operation being cancelled via {@link #abort}. */
    public static final int RESULT_ABORT = -1;
    /** Error code for the operation being cancelled via {@link #restart}. */
    public static final int RESULT_RESTART = -2;
    /** Error code for when the Exchange servers redirect you too many times in a row. */
    public static final int RESULT_TOO_MANY_REDIRECTS = -3;
    /** Error code for when the request failed due to a network problem. */
    public static final int RESULT_REQUEST_FAILURE = -4;
    /** Error code for all other errors. */
    public static final int RESULT_OTHER_FAILURE = -5;

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
     * @param syncResult If this operation is a sync, the {@link SyncResult} object that should
     *                   be written to for this sync; otherwise null.
     * @return A result code for the outcome of this operation, one of the RESULT_* values above.
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
                // First off, the success case.
                if (response.isSuccess()) {
                    try {
                        return handleResponse(response, syncResult);
                    } catch (final IOException e) {
                        LogUtils.e(LOG_TAG, e, "Exception while handling response");
                        if (syncResult != null) {
                            ++syncResult.stats.numParseExceptions;
                        }
                        return RESULT_OTHER_FAILURE;
                    }
                }

                // Now handle the error types we know how to deal with.
                if (response.isForbidden() && handleForbidden()) {
                    // Some operations distinguish forbidden from provisioning errors, in which
                    // case there's nothing futher to do here.
                    LogUtils.e(LOG_TAG, "Forbidden response");
                } else  if (response.isProvisionError()) {
                    LogUtils.e(LOG_TAG, "Provisioning error");
                    handleProvisionError();
                } else if (response.isAuthError()) {
                    LogUtils.e(LOG_TAG, "Authentication error");
                    handleAuthError();
                } else {
                    LogUtils.e(LOG_TAG, "Generic error");
                }

                // If it's not a redirect, we're done.
                if (!response.isRedirectError()) {
                    if (syncResult != null) {
                        if (response.isAuthError()) {
                            ++syncResult.stats.numAuthExceptions;
                        } else {
                            // TODO: Is there a more appropriate stat?
                            ++syncResult.stats.numIoExceptions;
                        }
                    }
                    return RESULT_OTHER_FAILURE;
                }

                // For redirects, update our connection and try again.
                ++redirectCount;
                mConnection.redirectHostAuth(response.getRedirectAddress());
            } finally {
                response.close();
            }
        } while (redirectCount < MAX_REDIRECTS);

        // Non-redirects return out of the while loop, so the only way to reach here is if we
        // looped too many times.
        LogUtils.e(LOG_TAG, "Too many redirects");
        if (syncResult != null) {
           syncResult.tooManyRetries = true;
        }
        return RESULT_TOO_MANY_REDIRECTS;
    }

    /**
     * Handling for provisioning (i.e. policy enforcement) errors. Should be the same for all
     * operations.
     * TODO: Implement.
     */
    private final void handleProvisionError() {

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
     * @return A result code that is returned to the caller of {@link #performOperation}.
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
     *
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
     * Convenience method to make an {@link HttpEntity} from {@link Serializer}.
     */
    protected final HttpEntity makeEntity(final Serializer s) {
        return new ByteArrayEntity(s.toByteArray());
    }

    protected final double getProtocolVersion() {
        return mConnection.getProtocolVersion();
    }

    /**
     * Convenience method for adding a Message to an account's outbox
     * @param account The {@link Account} from which to send the message.
     * @param msg the message to send
     */
    protected void sendMessage(final Account account, final EmailContent.Message msg) {
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
