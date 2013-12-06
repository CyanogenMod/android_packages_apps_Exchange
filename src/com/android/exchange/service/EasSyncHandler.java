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

package com.android.exchange.service;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.net.TrafficStats;
import android.os.Bundle;
import android.text.format.DateUtils;

import com.android.emailcommon.TrafficFlags;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;
import com.android.exchange.CommandStatusException;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;
import com.android.exchange.adapter.AbstractSyncParser;
import com.android.exchange.adapter.Parser;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;
import com.android.exchange.eas.EasProvision;
import com.android.mail.utils.LogUtils;

import org.apache.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateException;

/**
 * Base class for syncing a single collection from an Exchange server. A "collection" is a single
 * mailbox, or contacts for an account, or calendar for an account. (Tasks is part of the protocol
 * but not implemented.)
 * A single {@link ContentResolver#requestSync} for a single collection corresponds to a single
 * object (of the appropriate subclass) being created and {@link #performSync} being called on it.
 * This in turn will result in one or more Sync POST requests being sent to the Exchange server;
 * from the client's point of view, these multiple Exchange Sync requests are all part of the same
 * "sync" (i.e. the fact that there are multiple requests to the server is a detail of the Exchange
 * protocol).
 * Different collection types (e.g. mail, contacts, calendar) should subclass this class and
 * implement the various abstract functions. The majority of how the sync flow is common to all,
 * aside from a few details and the {@link Parser} used.
 * Details on how this class (and Exchange Sync) works:
 * - Overview MSDN link: http://msdn.microsoft.com/en-us/library/ee159766(v=exchg.80).aspx
 * - Sync MSDN link: http://msdn.microsoft.com/en-us/library/gg675638(v=exchg.80).aspx
 * - The very first time, the client sends a Sync request with SyncKey = 0 and no other parameters.
 *   This initial Sync request simply gets us a real SyncKey.
 *   TODO: We should add the initial Sync to EasAccountSyncHandler.
 * - Non-initial Sync requests can be for one or more collections; this implementation does one at
 *   a time. TODO: allow sync for multiple collections to be aggregated?
 * - For each collection, we send SyncKey, ServerId, other modifiers, Options, and Commands. The
 *   protocol has a specific order in which these elements must appear in the request.
 * - {@link #buildEasRequest} forms the XML for the request, using {@link #setInitialSyncOptions},
 *   {@link #setNonInitialSyncOptions}, and {@link #setUpsyncCommands} to fill in the details
 *   specific for each collection type.
 * - The Sync response may specify that there's more data available on the server, in which case
 *   we keep sending Sync requests to get that data.
 * - The ordering constraints and other details may require subclasses to have member variables to
 *   store state between the various calls while performing a single Sync request. These may need
 *   to be reset between Sync requests to the Exchange server. Additionally, there are possibly
 *   other necessary cleanups after parsing a Sync response. These are handled in {@link #cleanup}.
 */
public abstract class EasSyncHandler extends EasServerConnection {
    private static final String TAG = Eas.LOG_TAG;

    public static final int MAX_WINDOW_SIZE = 512;

    /** Window sizes for PIM (contact & calendar) sync options. */
    public static final int PIM_WINDOW_SIZE_CONTACTS = 10;
    public static final int PIM_WINDOW_SIZE_CALENDAR = 10;

    // TODO: For each type of failure, provide info about why.
    protected static final int SYNC_RESULT_DENIED = -3;
    protected static final int SYNC_RESULT_PROVISIONING_ERROR = -2;
    protected static final int SYNC_RESULT_FAILED = -1;
    protected static final int SYNC_RESULT_DONE = 0;
    protected static final int SYNC_RESULT_MORE_AVAILABLE = 1;

    protected final ContentResolver mContentResolver;
    protected final Mailbox mMailbox;
    protected final Bundle mSyncExtras;
    protected final SyncResult mSyncResult;

    protected EasSyncHandler(final Context context, final ContentResolver contentResolver,
            final Account account, final Mailbox mailbox, final Bundle syncExtras,
            final SyncResult syncResult) {
        super(context, account);
        mContentResolver = contentResolver;
        mMailbox = mailbox;
        mSyncExtras = syncExtras;
        mSyncResult = syncResult;
    }

    /**
     * Create an instance of the appropriate subclass to handle sync for mailbox.
     * @param context
     * @param contentResolver
     * @param accountManagerAccount The {@link android.accounts.Account} for this sync.
     * @param account The {@link Account} for mailbox.
     * @param mailbox The {@link Mailbox} to sync.
     * @param syncExtras The extras for this sync, for consumption by {@link #performSync}.
     * @param syncResult The output results for this sync, which may be written to by
     *      {@link #performSync}.
     * @return An appropriate EasSyncHandler for this mailbox, or null if this sync can't be
     *      handled.
     */
    public static EasSyncHandler getEasSyncHandler(final Context context,
            final ContentResolver contentResolver,
            final android.accounts.Account accountManagerAccount,
            final Account account, final Mailbox mailbox,
            final Bundle syncExtras, final SyncResult syncResult) {
        if (account != null && mailbox != null) {
            switch (mailbox.mType) {
                case Mailbox.TYPE_INBOX:
                case Mailbox.TYPE_MAIL:
                case Mailbox.TYPE_DRAFTS:
                case Mailbox.TYPE_SENT:
                case Mailbox.TYPE_TRASH:
                    return new EasMailboxSyncHandler(context, contentResolver, account, mailbox,
                            syncExtras, syncResult);
                case Mailbox.TYPE_CALENDAR:
                    return new EasCalendarSyncHandler(context, contentResolver,
                            accountManagerAccount, account, mailbox, syncExtras, syncResult);
                case Mailbox.TYPE_CONTACTS:
                    return new EasContactsSyncHandler(context, contentResolver,
                            accountManagerAccount, account, mailbox, syncExtras, syncResult);
            }
        }
        // Unknown mailbox type.
        LogUtils.e(TAG, "Invalid mailbox type %d", mailbox.mType);
        return null;
    }

    // Interface for subclasses to implement:
    // Subclasses must implement the abstract functions below to provide the information needed by
    // performSync.

    /**
     * Get the flag for traffic bookkeeping for this sync type.
     * @return The appropriate value from {@link TrafficFlags} for this sync.
     */
    protected abstract int getTrafficFlag();

    /**
     * Get the sync key for this mailbox.
     * @return The sync key for the object being synced. "0" means this is the first sync. If
     *      there is an error in getting the sync key, this function returns null.
     */
    protected String getSyncKey() {
        if (mMailbox == null) {
            return null;
        }
        if (mMailbox.mSyncKey == null) {
            mMailbox.mSyncKey = "0";
        }
        return mMailbox.mSyncKey;
    }

    /**
     * Get the folder class name for this mailbox.
     * @return The string for this folder class, as defined by the Exchange spec.
     */
    // TODO: refactor this to be the same strings as EasPingSyncHandler#handleOneMailbox.
    protected abstract String getFolderClassName();

    /**
     * Return an {@link AbstractSyncParser} appropriate for this sync type and response.
     * @param is The {@link InputStream} for the {@link EasResponse} for this sync.
     * @return The {@link AbstractSyncParser} for this response.
     * @throws IOException
     */
    protected abstract AbstractSyncParser getParser(final InputStream is) throws IOException;

    /**
     * Add to the {@link Serializer} for this sync the child elements of a Collection needed for an
     * initial sync for this collection.
     * @param s The {@link Serializer} for this sync.
     * @throws IOException
     */
    protected abstract void setInitialSyncOptions(final Serializer s) throws IOException;

    /**
     * Add to the {@link Serializer} for this sync the child elements of a Collection needed for a
     * non-initial sync for this collection, OTHER THAN Commands (which are written by
     * {@link #setUpsyncCommands}.
     *
     * @param s The {@link com.android.exchange.adapter.Serializer} for this sync.
     * @param numWindows
     * @throws IOException
     */
    protected abstract void setNonInitialSyncOptions(final Serializer s, int numWindows)
            throws IOException;

    /**
     * Add all Commands to the {@link Serializer} for this Sync request. Strictly speaking, it's
     * not all Upsync requests since Fetch is also a command, but largely that's what this section
     * is used for.
     * @param s The {@link Serializer} for this sync.
     * @throws IOException
     */
    protected abstract void setUpsyncCommands(final Serializer s) throws IOException;

    /**
     * Perform any necessary cleanup after processing a Sync response.
     */
    protected abstract void cleanup(final int syncResult);

    // End of abstract functions.

    /**
     * Shared non-initial sync options for PIM (contacts & calendar) objects.
     *
     * @param s The {@link com.android.exchange.adapter.Serializer} for this sync request.
     * @param filter The lookback to use, or null if no lookback is desired.
     * @param windowSize
     * @throws IOException
     */
    protected void setPimSyncOptions(final Serializer s, final String filter, int windowSize)
            throws IOException {
        s.tag(Tags.SYNC_DELETES_AS_MOVES);
        s.tag(Tags.SYNC_GET_CHANGES);
        s.data(Tags.SYNC_WINDOW_SIZE, String.valueOf(windowSize));
        s.start(Tags.SYNC_OPTIONS);
        // Set the filter (lookback), if provided
        if (filter != null) {
            s.data(Tags.SYNC_FILTER_TYPE, filter);
        }
        // Set the truncation amount and body type
        if (getProtocolVersion() >= Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
            s.start(Tags.BASE_BODY_PREFERENCE);
            // Plain text
            s.data(Tags.BASE_TYPE, Eas.BODY_PREFERENCE_TEXT);
            s.data(Tags.BASE_TRUNCATION_SIZE, Eas.EAS12_TRUNCATION_SIZE);
            s.end();
        } else {
            s.data(Tags.SYNC_TRUNCATION, Eas.EAS2_5_TRUNCATION_SIZE);
        }
        s.end();
    }

    /**
     * Create and populate the {@link Serializer} for this Sync POST to the Exchange server.
     *
     * @param syncKey The sync key to use for this request.
     * @param initialSync Whether this sync is the first for this object.
     * @param numWindows
     * @return The {@link Serializer} for to use for this request.
     * @throws IOException
     */
    private Serializer buildEasRequest(
            final String syncKey, final boolean initialSync, int numWindows) throws IOException {
        final String className = getFolderClassName();
        LogUtils.d(TAG, "Syncing account %d mailbox %d (class %s) with syncKey %s", mAccount.mId,
                mMailbox.mId, className, syncKey);

        final Serializer s = new Serializer();

        s.start(Tags.SYNC_SYNC);
        s.start(Tags.SYNC_COLLECTIONS);
        s.start(Tags.SYNC_COLLECTION);
        // The "Class" element is removed in EAS 12.1 and later versions
        if (getProtocolVersion() < Eas.SUPPORTED_PROTOCOL_EX2007_SP1_DOUBLE) {
            s.data(Tags.SYNC_CLASS, className);
        }
        s.data(Tags.SYNC_SYNC_KEY, syncKey);
        s.data(Tags.SYNC_COLLECTION_ID, mMailbox.mServerId);
        if (initialSync) {
            setInitialSyncOptions(s);
        } else {
            setNonInitialSyncOptions(s, numWindows);
            setUpsyncCommands(s);
        }
        s.end().end().end().done();

        return s;
    }

    /**
     * Interpret a successful (HTTP code = 200) response from the Exchange server.
     * @param resp The {@link EasResponse} for the Sync message.
     * @return One of {@link #SYNC_RESULT_FAILED}, {@link #SYNC_RESULT_MORE_AVAILABLE}, or
     *      {@link #SYNC_RESULT_DONE} as appropriate for the server response.
     */
    private int parse(final EasResponse resp) {
        try {
            final AbstractSyncParser parser = getParser(resp.getInputStream());
            final boolean moreAvailable = parser.parse();
            if (moreAvailable) {
                return SYNC_RESULT_MORE_AVAILABLE;
            }
        } catch (final Parser.EmptyStreamException e) {
            // This indicates a compressed response which was empty, which is OK.
        } catch (final IOException e) {
            return SYNC_RESULT_FAILED;
        } catch (final CommandStatusException e) {
            // TODO: This is basically copied from EasOperation, will go away when this merges.
            final int status = e.mStatus;
            LogUtils.e(TAG, "CommandStatusException: %d", status);
            if (CommandStatusException.CommandStatus.isNeedsProvisioning(status)) {
               return SYNC_RESULT_PROVISIONING_ERROR;
            }
            if (CommandStatusException.CommandStatus.isDeniedAccess(status)) {
                return SYNC_RESULT_DENIED;
            }
            return SYNC_RESULT_FAILED;
        }
        return SYNC_RESULT_DONE;
    }

    /**
     * Send one Sync POST to the Exchange server, and handle the response.
     * @return One of {@link #SYNC_RESULT_FAILED}, {@link #SYNC_RESULT_MORE_AVAILABLE}, or
     *      {@link #SYNC_RESULT_DONE} as appropriate for the server response.
     * @param syncResult
     * @param numWindows
     */
    private int performOneSync(SyncResult syncResult, int numWindows) {
        final String syncKey = getSyncKey();
        if (syncKey == null) {
            return SYNC_RESULT_FAILED;
        }
        final boolean initialSync = syncKey.equals("0");

        final EasResponse resp;
        try {
            final Serializer s = buildEasRequest(syncKey, initialSync, numWindows);
            final long timeout = initialSync ? 120 * DateUtils.SECOND_IN_MILLIS : COMMAND_TIMEOUT;
            resp = sendHttpClientPost("Sync", s.toByteArray(), timeout);
        } catch (final IOException e) {
            LogUtils.e(TAG, e, "Sync error:");
            syncResult.stats.numIoExceptions++;
            return SYNC_RESULT_FAILED;
        } catch (final CertificateException e) {
            LogUtils.e(TAG, e, "Certificate error:");
            syncResult.stats.numAuthExceptions++;
            return SYNC_RESULT_FAILED;
        }

        final int result;
        try {
            final int responseResult;
            final int code = resp.getStatus();
            if (code == HttpStatus.SC_OK) {
                // A successful sync can have an empty response -- this indicates no change.
                // In the case of a compressed stream, resp will be non-empty, but parse() handles
                // that case.
                if (!resp.isEmpty()) {
                    responseResult = parse(resp);
                } else {
                    responseResult = SYNC_RESULT_DONE;
                }
            } else {
                LogUtils.e(TAG, "Sync failed with Status: " + code);
                responseResult = SYNC_RESULT_FAILED;
            }

            if (responseResult == SYNC_RESULT_DONE
                    || responseResult == SYNC_RESULT_MORE_AVAILABLE) {
                result = responseResult;
            } else if (resp.isProvisionError()
                    || responseResult == SYNC_RESULT_PROVISIONING_ERROR) {
                final EasProvision provision = new EasProvision(mContext, mAccount.mId, this);
                if (provision.provision(syncResult, mAccount.mId)) {
                    // We handled the provisioning error, so loop.
                    LogUtils.d(TAG, "Provisioning error handled during sync, retrying");
                    result = SYNC_RESULT_MORE_AVAILABLE;
                } else {
                    syncResult.stats.numAuthExceptions++;
                    result = SYNC_RESULT_FAILED;
                }
            } else if (resp.isAuthError() || responseResult == SYNC_RESULT_DENIED) {
                syncResult.stats.numAuthExceptions++;
                result = SYNC_RESULT_FAILED;
            } else {
                syncResult.stats.numParseExceptions++;
                result = SYNC_RESULT_FAILED;
            }

        } finally {
            resp.close();
        }

        cleanup(result);

        if (initialSync && result != SYNC_RESULT_FAILED) {
            // TODO: Handle Automatic Lookback
        }

        return result;
    }

    /**
     * Perform the sync, updating {@link #mSyncResult} as appropriate (which was passed in from
     * the system SyncManager and will be read by it on the way out).
     * This function can send multiple Sync messages to the Exchange server, due to the server
     * replying to a Sync request with MoreAvailable.
     * In the case of errors, this function should not attempt any retries, but rather should
     * set {@link #mSyncResult} to reflect the problem and let the system SyncManager handle
     * any it.
     * @param syncResult
     */
    public final boolean performSync(SyncResult syncResult) {
        // Set up traffic stats bookkeeping.
        final int trafficFlags = TrafficFlags.getSyncFlags(mContext, mAccount);
        TrafficStats.setThreadStatsTag(trafficFlags | getTrafficFlag());

        // TODO: Properly handle UI status updates.
        //syncMailboxStatus(EmailServiceStatus.IN_PROGRESS, 0);
        int result = SYNC_RESULT_MORE_AVAILABLE;
        int numWindows = 1;
        String key = getSyncKey();
        while (result == SYNC_RESULT_MORE_AVAILABLE) {
            result = performOneSync(syncResult, numWindows);
            // TODO: Clear pending request queue.
            final String newKey = getSyncKey();
            if (result == SYNC_RESULT_MORE_AVAILABLE && key.equals(newKey)) {
                LogUtils.e(TAG,
                        "Server has more data but we have the same key: %s numWindows: %d",
                        key, numWindows);
                numWindows++;
            } else {
                numWindows = 1;
            }
            key = newKey;
        }
        return result == SYNC_RESULT_DONE;
    }
}
