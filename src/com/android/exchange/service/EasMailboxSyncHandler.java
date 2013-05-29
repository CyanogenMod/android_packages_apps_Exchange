package com.android.exchange.service;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.database.Cursor;
import android.os.Bundle;

import com.android.emailcommon.TrafficFlags;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.SyncWindow;
import com.android.exchange.Eas;
import com.android.exchange.adapter.AbstractSyncParser;
import com.android.exchange.adapter.EmailSyncAdapter.EasEmailSyncParser;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;

import java.io.IOException;
import java.io.InputStream;
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

    @Override
    protected int getTrafficFlag() {
        return TrafficFlags.DATA_EMAIL;
    }

    @Override
    protected String getSyncKey() {
        if (mMailbox == null) {
            return null;
        }
        if (mMailbox.mSyncKey == null) {
            // TODO: Write to DB? Probably not, and just let successful sync do that.
            mMailbox.mSyncKey = "0";
        }
        return mMailbox.mSyncKey;
    }

    @Override
    protected String getFolderClassName() {
        return "Email";
    }

    @Override
    protected AbstractSyncParser getParser(final InputStream is) throws IOException {
        return new EasEmailSyncParser(mContext, mContentResolver, is, mMailbox, mAccount);
    }

    @Override
    protected void setInitialSyncOptions(final Serializer s) {
        // No-op.
    }

    @Override
    protected void setNonInitialSyncOptions(final Serializer s) throws IOException {
        // The "empty" case is typical; we send a request for changes, and also specify a sync
        // window, body preference type (HTML for EAS 12.0 and later; MIME for EAS 2.5), and
        // truncation
        // If there are fetch requests, we only want the fetches (i.e. no changes from the server)
        // so we turn MIME support off.  Note that we are always using EAS 2.5 if there are fetch
        // requests
        // TODO: Fix.
        final boolean hasFetchRequests = false;
        if (!hasFetchRequests) {
            // Permanently delete if in trash mailbox
            // In Exchange 2003, deletes-as-moves tag = true; no tag = false
            // In Exchange 2007 and up, deletes-as-moves tag is "0" (false) or "1" (true)
            final boolean isTrashMailbox = mMailbox.mType == Mailbox.TYPE_TRASH;
            if (getProtocolVersion() < Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
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
            if (getProtocolVersion() >= Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
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

    @Override
    protected void setUpsyncCommands(final Serializer s) throws IOException {

    }
}
