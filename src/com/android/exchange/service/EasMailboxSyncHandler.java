package com.android.exchange.service;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.database.Cursor;
import android.os.Bundle;

import com.android.emailcommon.TrafficFlags;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.SyncWindow;
import com.android.exchange.Eas;
import com.android.exchange.adapter.AbstractSyncParser;
import com.android.exchange.adapter.EmailSyncParser;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Performs an Exchange mailbox sync for "normal" mailboxes.
 */
public class EasMailboxSyncHandler extends EasSyncHandler {
    /**
     * The projection used for building the fetch request list.
     */
    private static final String[] FETCH_REQUEST_PROJECTION = { SyncColumns.SERVER_ID };
    private static final int FETCH_REQUEST_SERVER_ID = 0;

    private static final int EMAIL_WINDOW_SIZE = 10;

    /**
     * List of server ids for messages to fetch from the server.
     */
    private final ArrayList<String> mMessagesToFetch = new ArrayList<String>();

    public EasMailboxSyncHandler(final Context context, final ContentResolver contentResolver,
            final Account account, final Mailbox mailbox, final Bundle syncExtras,
            final SyncResult syncResult) {
        super(context, contentResolver, account, mailbox, syncExtras, syncResult);
    }

    private String getEmailFilter() {
        final int syncLookback = mMailbox.mSyncLookback == SyncWindow.SYNC_WINDOW_ACCOUNT
                ? mAccount.mSyncLookback : mMailbox.mSyncLookback;
        switch (syncLookback) {
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
                // Auto window is deprecated and will also use the default.
                return Eas.FILTER_1_WEEK;
        }
    }

    /**
     * Find partially loaded messages and add their server ids to {@link #mMessagesToFetch}.
     */
    private void addToFetchRequestList() {
        final Cursor c = mContentResolver.query(Message.CONTENT_URI, FETCH_REQUEST_PROJECTION,
                MessageColumns.FLAG_LOADED + "=" + Message.FLAG_LOADED_PARTIAL + " AND " +
                MessageColumns.MAILBOX_KEY + "=?", new String[] {Long.toString(mMailbox.mId)},
                null);
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    mMessagesToFetch.add(c.getString(FETCH_REQUEST_SERVER_ID));
                }
            } finally {
                c.close();
            }
        }
    }

    @Override
    protected int getTrafficFlag() {
        return TrafficFlags.DATA_EMAIL;
    }

    @Override
    protected String getFolderClassName() {
        return "Email";
    }

    @Override
    protected AbstractSyncParser getParser(final InputStream is) throws IOException {
        return new EmailSyncParser(mContext, mContentResolver, is, mMailbox, mAccount);
    }

    @Override
    protected void setInitialSyncOptions(final Serializer s) {
        // No-op.
    }

    @Override
    protected void setNonInitialSyncOptions(final Serializer s, int numWindows) throws IOException {
        // Check for messages that aren't fully loaded.
        addToFetchRequestList();
        // The "empty" case is typical; we send a request for changes, and also specify a sync
        // window, body preference type (HTML for EAS 12.0 and later; MIME for EAS 2.5), and
        // truncation
        // If there are fetch requests, we only want the fetches (i.e. no changes from the server)
        // so we turn MIME support off.  Note that we are always using EAS 2.5 if there are fetch
        // requests
        if (mMessagesToFetch.isEmpty()) {
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

            final int windowSize = numWindows * EMAIL_WINDOW_SIZE;
            if (windowSize > MAX_WINDOW_SIZE  + EMAIL_WINDOW_SIZE) {
                throw new IOException("Max window size reached and still no data");
            }
            s.data(Tags.SYNC_WINDOW_SIZE,
                    String.valueOf(windowSize < MAX_WINDOW_SIZE ? windowSize : MAX_WINDOW_SIZE));
            s.start(Tags.SYNC_OPTIONS);
            // Set the lookback appropriately (EAS calls this a "filter")
            s.data(Tags.SYNC_FILTER_TYPE, getEmailFilter());
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
            // If we have any messages that are not fully loaded, ask for plain text rather than
            // MIME, to guarantee we'll get usable text body. This also means we should NOT ask for
            // new messages -- we only want data for the message explicitly fetched.
            s.start(Tags.SYNC_OPTIONS);
            s.data(Tags.SYNC_MIME_SUPPORT, Eas.MIME_BODY_PREFERENCE_TEXT);
            s.data(Tags.SYNC_TRUNCATION, Eas.EAS2_5_TRUNCATION_SIZE);
            s.end();
        }
    }

    /**
     * Add FETCH commands for messages that need a body (i.e. we didn't find it during our earlier
     * sync; this happens only in EAS 2.5 where the body couldn't be found after parsing the
     * message's MIME data).
     * @param s The {@link Serializer} for this sync request.
     * @throws IOException
     */
    private void addFetchCommands(final Serializer s) throws IOException {
        if (!mMessagesToFetch.isEmpty()) {
            s.start(Tags.SYNC_COMMANDS);
            for (final String serverId : mMessagesToFetch) {
                s.start(Tags.SYNC_FETCH).data(Tags.SYNC_SERVER_ID, serverId).end();
            }
            s.end();
        }
    }

    @Override
    protected void setUpsyncCommands(final Serializer s) throws IOException {
        addFetchCommands(s);
    }

    @Override
    protected void cleanup(final int syncResult) {
        if (syncResult == SYNC_RESULT_MORE_AVAILABLE) {
            // Prepare our member variables for another sync request.
            mMessagesToFetch.clear();
        }
    }
}
