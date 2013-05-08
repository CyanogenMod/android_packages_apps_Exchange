package com.android.exchange.service;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.TrafficStats;
import android.os.Bundle;
import android.text.format.DateUtils;

import com.android.emailcommon.TrafficFlags;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.SyncWindow;
import com.android.exchange.CommandStatusException;
import com.android.exchange.CommandStatusException.CommandStatus;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;
import com.android.exchange.adapter.EmailSyncAdapter.EasEmailSyncParser;
import com.android.exchange.adapter.Parser.EmptyStreamException;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;

import org.apache.http.HttpStatus;
import org.apache.http.entity.ByteArrayEntity;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Performs an Exchange mailbox sync for "normal" mailboxes.
 */
public class EasMailboxSyncHandler extends EasSyncHandler {

    public EasMailboxSyncHandler(final Context context, final ContentResolver contentResolver,
            final Account account, final Mailbox mailbox, final Bundle syncExtras,
            final SyncResult syncResult) {
        super(context, contentResolver, account, mailbox, syncExtras, syncResult);
    }

    // Maximum number of times we'll allow a sync to "loop" with MoreAvailable true before
    // forcing it to stop.  This number has been determined empirically.
    private static final int MAX_LOOPING_COUNT = 100;

    @Override
    public SyncStatus performSync() {
        final int trafficFlags = TrafficFlags.getSyncFlags(mContext, mAccount);
        TrafficStats.setThreadStatsTag(trafficFlags | TrafficFlags.DATA_EMAIL);
        if (mMailbox.mSyncKey == null) {
            mMailbox.mSyncKey = "0";
            // TODO: Write to DB?
        }

        // TODO: Only do this for UI initiated requests?
        syncMailboxStatus(EmailServiceStatus.IN_PROGRESS, 0);

        boolean moreAvailable = true;
        int loopingCount = 0;
        while (moreAvailable && loopingCount <= MAX_LOOPING_COUNT) {
            final EasResponse resp;
            final ArrayList<FetchRequest> fetchRequestList;
            try {
                // Build the EAS request.
                final Serializer s = new Serializer();
                s.start(Tags.SYNC_SYNC);
                s.start(Tags.SYNC_COLLECTIONS);
                s.start(Tags.SYNC_COLLECTION);

                final Double protocolVersionDouble =
                        Eas.getProtocolVersionDouble(getProtocolVersion());
                // The "Class" element is removed in EAS 12.1 and later versions
                if (protocolVersionDouble < Eas.SUPPORTED_PROTOCOL_EX2007_SP1_DOUBLE) {
                    s.data(Tags.SYNC_CLASS, "Email");
                }
                s.data(Tags.SYNC_SYNC_KEY, mMailbox.mSyncKey);
                s.data(Tags.SYNC_COLLECTION_ID, mMailbox.mServerId);

                final boolean initialSync = mMailbox.mSyncKey.equals("0");
                // Use enormous timeout for initial sync, which empirically can take a while longer
                final long timeout =
                        initialSync ? 120 * DateUtils.SECOND_IN_MILLIS : COMMAND_TIMEOUT;

                fetchRequestList = createFetchRequestList(initialSync);

                // EAS doesn't allow GetChanges in an initial sync; sending other options
                // appears to cause the server to delay its response in some cases, and this delay
                // can be long enough to result in an IOException and total failure to sync.
                // Therefore, we don't send any options with the initial sync.
                // Set the truncation amount, body preference, lookback, etc.
                if (!initialSync) {
                    sendSyncOptions(protocolVersionDouble, s, !fetchRequestList.isEmpty());
                    // TODO: Fix.
                    //EmailSyncAdapter.upsync(resolver, mailbox, s);
                }

                // Send the EAS request and interpret the response.
                s.end().end().end().done();
                resp = sendHttpClientPost("Sync", new ByteArrayEntity(s.toByteArray()), timeout);
            } catch (final IOException e) {
                return SyncStatus.FAILURE_IO;
            }
            try {
                final int code = resp.getStatus();
                if (code == HttpStatus.SC_OK) {
                    // In EAS 12.1, we can get "empty" sync responses, which indicate that there are
                    // no changes in the mailbox. These are still successful syncs.
                    // There are two cases here; if we get back a compressed stream (GZIP), we won't
                    // know until we try to parse it (and generate an EmptyStreamException). If we
                    // get uncompressed data, the response will be empty (i.e. have zero length)
                    if (resp.isEmpty()) {
                        moreAvailable = false;
                    } else {
                        try {
                            final EasEmailSyncParser p = new EasEmailSyncParser(mContext,
                                    mContentResolver, resp.getInputStream(), mMailbox, mAccount);
                            moreAvailable = p.parse();
                            if (p.fetchNeeded() || !fetchRequestList.isEmpty()) {
                                moreAvailable = true;
                            } else {
                                if (!("0".equals(mMailbox.mSyncKey))) {
                                    // We've completed the first successful sync
                                    if (getEmailFilter().equals(Eas.FILTER_AUTO)) {
                                        // TODO: Fix.
                                        //getAutomaticLookback();
                                     }
                                }
                            }
                            if (p.isLooping()) {
                                ++loopingCount;
                            } else {
                                loopingCount = 0;
                            }
                        } catch (final EmptyStreamException e) {
                            // This happens when we have a compressed empty stream, so it's not
                            // really an error. Proceed as normal.
                            moreAvailable = false;
                        } catch (final CommandStatusException e) {
                            final int status = e.mStatus;
                            if (CommandStatus.isNeedsProvisioning(status)) {
                                return SyncStatus.FAILURE_SECURITY;
                            } else if (CommandStatus.isDeniedAccess(status)) {
                                return SyncStatus.FAILURE_LOGIN;
                            } else if (CommandStatus.isTransientError(status)) {
                                return SyncStatus.FAILURE_IO;
                            }
                            return SyncStatus.FAILURE_OTHER;
                        } catch (final IOException e) {
                            return SyncStatus.FAILURE_IO;
                        }
                    }

                    // target.cleanup() or equivalent

                } else if (EasResponse.isProvisionError(code)) {
                    return SyncStatus.FAILURE_SECURITY;
                } else if (EasResponse.isAuthError(code)) {
                    return SyncStatus.FAILURE_LOGIN;
                } else {
                    return SyncStatus.FAILURE_OTHER;
                }
            } finally {
                resp.close();
            }
        }

        // TODO: send this on error paths too.
        syncMailboxStatus(EmailServiceStatus.SUCCESS, 0);
        return SyncStatus.SUCCESS;
    }

    private static final String[] FETCH_REQUEST_PROJECTION =
            new String[] {EmailContent.RECORD_ID, SyncColumns.SERVER_ID};
    private static final int FETCH_REQUEST_RECORD_ID = 0;
    private static final int FETCH_REQUEST_SERVER_ID = 1;

    private static final String EMAIL_WINDOW_SIZE = "5";

    /**
     * Holder for fetch request information (record id and server id)
     */
    private static class FetchRequest {
        final long messageId;
        final String serverId;

        FetchRequest(final long _messageId, final String _serverId) {
            messageId = _messageId;
            serverId = _serverId;
        }
    }

    private String getEmailFilter() {
        int syncLookback = mMailbox.mSyncLookback;
        if (syncLookback == SyncWindow.SYNC_WINDOW_UNKNOWN
                || mMailbox.mType == Mailbox.TYPE_INBOX) {
            syncLookback = mAccount.mSyncLookback;
        }
        switch (syncLookback) {
            case SyncWindow.SYNC_WINDOW_AUTO:
                return Eas.FILTER_AUTO;
            case SyncWindow.SYNC_WINDOW_1_DAY:
                return Eas.FILTER_1_DAY;
            case SyncWindow.SYNC_WINDOW_3_DAYS:
                return Eas.FILTER_3_DAYS;
            case SyncWindow.SYNC_WINDOW_1_WEEK:
                return Eas.FILTER_1_WEEK;
            case SyncWindow.SYNC_WINDOW_2_WEEKS:
                return Eas.FILTER_2_WEEKS;
            case SyncWindow.SYNC_WINDOW_1_MONTH:
                return Eas.FILTER_1_MONTH;
            case SyncWindow.SYNC_WINDOW_ALL:
                return Eas.FILTER_ALL;
            default:
                return Eas.FILTER_1_WEEK;
        }
    }

    private ArrayList<FetchRequest> createFetchRequestList(final boolean initialSync) {
        final ArrayList<FetchRequest> fetchRequestList = new ArrayList<FetchRequest>();
        if (!initialSync) {
            // Find partially loaded messages; this should typically be a rare occurrence
            final Cursor c = mContentResolver.query(Message.CONTENT_URI, FETCH_REQUEST_PROJECTION,
                    MessageColumns.FLAG_LOADED + "=" + Message.FLAG_LOADED_PARTIAL + " AND " +
                    MessageColumns.MAILBOX_KEY + "=?", new String[] {Long.toString(mMailbox.mId)},
                    null);

            try {
                // Put all of these messages into a list; we'll need both id and server id
                while (c.moveToNext()) {
                    fetchRequestList.add(new FetchRequest(c.getLong(FETCH_REQUEST_RECORD_ID),
                            c.getString(FETCH_REQUEST_SERVER_ID)));
                }
            } finally {
                c.close();
            }
        }
        return fetchRequestList;
    }

    private void sendSyncOptions(final Double protocolVersion, final Serializer s,
            final boolean hasFetchRequests) throws IOException {
        // The "empty" case is typical; we send a request for changes, and also specify a sync
        // window, body preference type (HTML for EAS 12.0 and later; MIME for EAS 2.5), and
        // truncation
        // If there are fetch requests, we only want the fetches (i.e. no changes from the server)
        // so we turn MIME support off.  Note that we are always using EAS 2.5 if there are fetch
        // requests
        if (!hasFetchRequests) {
            // Permanently delete if in trash mailbox
            // In Exchange 2003, deletes-as-moves tag = true; no tag = false
            // In Exchange 2007 and up, deletes-as-moves tag is "0" (false) or "1" (true)
            final boolean isTrashMailbox = mMailbox.mType == Mailbox.TYPE_TRASH;
            if (protocolVersion < Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
                if (!isTrashMailbox) {
                    s.tag(Tags.SYNC_DELETES_AS_MOVES);
                }
            } else {
                s.data(Tags.SYNC_DELETES_AS_MOVES, isTrashMailbox ? "0" : "1");
            }
            s.tag(Tags.SYNC_GET_CHANGES);
            s.data(Tags.SYNC_WINDOW_SIZE, EMAIL_WINDOW_SIZE);
            s.start(Tags.SYNC_OPTIONS);
            // Set the lookback appropriately (EAS calls this a "filter")
            String filter = getEmailFilter();
            // We shouldn't get FILTER_AUTO here, but if we do, make it something legal...
            if (filter.equals(Eas.FILTER_AUTO)) {
                filter = Eas.FILTER_3_DAYS;
            }
            s.data(Tags.SYNC_FILTER_TYPE, filter);
            // Set the truncation amount for all classes
            if (protocolVersion >= Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
                s.start(Tags.BASE_BODY_PREFERENCE);
                // HTML for email
                s.data(Tags.BASE_TYPE, Eas.BODY_PREFERENCE_HTML);
                s.data(Tags.BASE_TRUNCATION_SIZE, Eas.EAS12_TRUNCATION_SIZE);
                s.end();
            } else {
                // Use MIME data for EAS 2.5
                s.data(Tags.SYNC_MIME_SUPPORT, Eas.MIME_BODY_PREFERENCE_MIME);
                s.data(Tags.SYNC_MIME_TRUNCATION, Eas.EAS2_5_TRUNCATION_SIZE);
            }
            s.end();
        } else {
            s.start(Tags.SYNC_OPTIONS);
            // Ask for plain text, rather than MIME data.  This guarantees that we'll get a usable
            // text body
            s.data(Tags.SYNC_MIME_SUPPORT, Eas.MIME_BODY_PREFERENCE_TEXT);
            s.data(Tags.SYNC_TRUNCATION, Eas.EAS2_5_TRUNCATION_SIZE);
            s.end();
        }
    }
}
