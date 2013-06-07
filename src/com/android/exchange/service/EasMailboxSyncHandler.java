package com.android.exchange.service;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.SyncResult;
import android.database.Cursor;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.format.DateUtils;

import com.android.emailcommon.TrafficFlags;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.SyncWindow;
import com.android.exchange.Eas;
import com.android.exchange.EasAuthenticationException;
import com.android.exchange.EasResponse;
import com.android.exchange.adapter.AbstractSyncParser;
import com.android.exchange.adapter.EmailSyncAdapter.EasEmailSyncParser;
import com.android.exchange.adapter.MoveItemsParser;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;
import com.android.mail.utils.LogUtils;

import org.apache.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Performs an Exchange mailbox sync for "normal" mailboxes.
 */
public class EasMailboxSyncHandler extends EasSyncHandler {
    private static final String TAG = "EasMailboxSyncHandler";

    /**
     * The projection used for building the fetch request list.
     */
    private static final String[] FETCH_REQUEST_PROJECTION = { SyncColumns.SERVER_ID };
    private static final int FETCH_REQUEST_SERVER_ID = 0;

    /**
     * The projection used for querying message tables for the purpose of determining what needs
     * to be set as a sync update.
     */
    private static final String[] UPDATES_PROJECTION = { MessageColumns.ID,
            MessageColumns.MAILBOX_KEY, SyncColumns.SERVER_ID,
            MessageColumns.FLAGS, MessageColumns.FLAG_READ, MessageColumns.FLAG_FAVORITE };
    private static final int UPDATES_ID_COLUMN = 0;
    private static final int UPDATES_MAILBOX_KEY_COLUMN = 1;
    private static final int UPDATES_SERVER_ID_COLUMN = 2;
    private static final int UPDATES_FLAG_COLUMN = 3;
    private static final int UPDATES_READ_COLUMN = 4;
    private static final int UPDATES_FAVORITE_COLUMN = 5;

    /**
     * Message flags value to signify that the message has been moved, and eventually needs to be
     * deleted.
     */
    public static final int MESSAGE_FLAG_MOVED_MESSAGE = 1 << Message.FLAG_SYNC_ADAPTER_SHIFT;

    /**
     * The selection for moved messages that get deleted after a successful sync.
     */
    private static final String WHERE_MAILBOX_KEY_AND_MOVED =
            MessageColumns.MAILBOX_KEY + "=? AND (" + MessageColumns.FLAGS + "&" +
            MESSAGE_FLAG_MOVED_MESSAGE + ")!=0";

    private static final String EMAIL_WINDOW_SIZE = "5";

    private static final String WHERE_BODY_SOURCE_MESSAGE_KEY =
            EmailContent.Body.SOURCE_MESSAGE_KEY + "=?";

    // State needed across multiple functions during a Sync.
    // TODO: We should perhaps invert the meaning of mDeletedMessages & mUpdatedMessages and
    // store the values we want to *retain* after a successful upsync.

    /**
     * List of message ids that were read from Message_Deletes and were sent in the Commands section
     * of the current sync.
     */
    private final ArrayList<Long> mDeletedMessages = new ArrayList<Long>();

    /**
     * List of message ids that were read from Message_Updates and were sent in the Commands section
     * of the current sync.
     */
    private final ArrayList<Long> mUpdatedMessages = new ArrayList<Long>();

    /**
     * List of server ids for messages to fetch from the server.
     */
    private final ArrayList<String> mMessagesToFetch = new ArrayList<String>();

    /**
     * Holds all the data needed to process a MoveItems request.
     */
    private static class MoveRequest {
        public final long messageId;
        public final String messageServerId;
        public final int messageFlags;
        public final long sourceFolderId;
        public final String sourceFolderServerId;
        public final String destFolderServerId;

        public MoveRequest(final long _messageId, final String _messageServerId,
                final int _messageFlags,
                final long _sourceFolderId, final String _sourceFolderServerId,
                final String _destFolderServerId) {
            messageId = _messageId;
            messageServerId = _messageServerId;
            messageFlags = _messageFlags;
            sourceFolderId = _sourceFolderId;
            sourceFolderServerId = _sourceFolderServerId;
            destFolderServerId = _destFolderServerId;
        }
    }

    /**
     * List of all MoveRequests, i.e. all messages which have different mailboxes than they used to.
     */
    private final ArrayList<MoveRequest> mMessagesToMove = new ArrayList<MoveRequest>();

    public EasMailboxSyncHandler(final Context context, final ContentResolver contentResolver,
            final Account account, final Mailbox mailbox, final Bundle syncExtras,
            final SyncResult syncResult) {
        super(context, contentResolver, account, mailbox, syncExtras, syncResult);
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
     * Check whether a message is referenced by another (and therefore must be kept around).
     * @param messageId The id of the message to check.
     * @return Whether the message in question is referenced by another message.
     */
    private boolean messageReferenced(final long messageId) {
        final Cursor c = mContentResolver.query(EmailContent.Body.CONTENT_URI,
                EmailContent.Body.ID_PROJECTION, WHERE_BODY_SOURCE_MESSAGE_KEY,
                new String[] {Long.toString(messageId)}, null);
        if (c != null) {
            try {
                return c.moveToFirst();
            } finally {
                c.close();
            }
        }
        return false;
    }

    /**
     * Write the command to delete a message to the {@link Serializer}.
     * @param s The {@link Serializer} for this sync command.
     * @param serverId The server id for the message to delete.
     * @param firstCommand Whether any sync commands have already been written to s.
     * @throws IOException
     */
    private void addDeleteMessageCommand(final Serializer s, final String serverId,
            final boolean firstCommand) throws IOException {
        if (firstCommand) {
            s.start(Tags.SYNC_COMMANDS);
        }
        s.start(Tags.SYNC_DELETE).data(Tags.SYNC_SERVER_ID, serverId).end();
    }

    /**
     * Adds a sync delete command for all messages in the Message_Deletes table.
     * @param s The {@link Serializer} for this sync command.
     * @param hasCommands Whether any Commands have already been written to s.
     * @return Whether this function wrote any commands to s.
     * @throws IOException
     */
    private boolean addDeletedCommands(final Serializer s, final boolean hasCommands)
            throws IOException {
        boolean wroteCommands = false;
        final Cursor c = mContentResolver.query(Message.DELETED_CONTENT_URI,
                Message.ID_COLUMNS_PROJECTION, MessageColumns.MAILBOX_KEY + '=' + mMailbox.mId,
                null, null);
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    final String serverId = c.getString(Message.ID_COLUMNS_SYNC_SERVER_ID);
                    final long messageId = c.getLong(Message.ID_COLUMNS_ID_COLUMN);
                    // Only upsync delete commands for messages that have server ids and are not
                    // referenced by other messages.
                    if (serverId != null && !messageReferenced(messageId)) {
                        addDeleteMessageCommand(s, serverId, wroteCommands || hasCommands);
                        wroteCommands = true;
                        mDeletedMessages.add(messageId);
                    }
                }
            } finally {
                c.close();
            }
        }

        return wroteCommands;
    }

    /**
     * Create date/time in RFC8601 format.  Oddly enough, for calendar date/time, Microsoft uses
     * a different format that excludes the punctuation (this is why I'm not putting this in a
     * parent class)
     */
    private static String formatDateTime(final Calendar calendar) {
        final StringBuilder sb = new StringBuilder();
        //YYYY-MM-DDTHH:MM:SS.MSSZ
        sb.append(calendar.get(Calendar.YEAR));
        sb.append('-');
        sb.append(String.format(Locale.US, "%02d", calendar.get(Calendar.MONTH) + 1));
        sb.append('-');
        sb.append(String.format(Locale.US, "%02d", calendar.get(Calendar.DAY_OF_MONTH)));
        sb.append('T');
        sb.append(String.format(Locale.US, "%02d", calendar.get(Calendar.HOUR_OF_DAY)));
        sb.append(':');
        sb.append(String.format(Locale.US, "%02d", calendar.get(Calendar.MINUTE)));
        sb.append(':');
        sb.append(String.format(Locale.US, "%02d", calendar.get(Calendar.SECOND)));
        sb.append(".000Z");
        return sb.toString();
    }

    /**
     * Get the server id for a mailbox from the content provider.
     * @param mailboxId The id of the mailbox we're interested in.
     * @return The server id for the mailbox.
     */
    private String getServerIdForMailbox(final long mailboxId) {
        final Cursor c = mContentResolver.query(
                ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId),
                new String [] { EmailContent.MailboxColumns.SERVER_ID }, null, null, null);
        if (c != null) {
            try {
                if (c.moveToNext()) {
                    return c.getString(0);
                }
            } finally {
                c.close();
            }
        }
        return null;
    }

    /**
     * For a message that's in the Message_Updates table, add a sync command to the
     * {@link Serializer} if appropriate, and add the message to a list if it should be removed from
     * Message_Updates.
     * @param updatedMessageCursor The {@link Cursor} positioned at the message from Message_Updates
     *                             that we're processing.
     * @param s The {@link Serializer} for this sync command.
     * @param hasCommands Whether the {@link Serializer} already has sync commands added to it.
     * @param trashMailboxId The id for the trash mailbox.
     * @return Whether we added a sync command to s.
     * @throws IOException
     */
    private boolean handleOneUpdatedMessage(final Cursor updatedMessageCursor, final Serializer s,
            final boolean hasCommands, final long trashMailboxId) throws IOException {
        final long messageId = updatedMessageCursor.getLong(UPDATES_ID_COLUMN);
        // Get the current state of this message (updated table has original state).
        final Cursor currentCursor = mContentResolver.query(
                ContentUris.withAppendedId(Message.CONTENT_URI, messageId),
                UPDATES_PROJECTION, null, null, null);
        if (currentCursor == null) {
            // If, somehow, the message isn't still around, we still want to handle it as having
            // been updated so that it gets removed from the updated table.
            mUpdatedMessages.add(messageId);
            return false;
        }

        try {
            if (currentCursor.moveToFirst()) {
                final String serverId = currentCursor.getString(UPDATES_SERVER_ID_COLUMN);
                if (serverId == null) {
                    // No serverId means there's nothing to do, but we should still remove from the
                    // updated table.
                    mUpdatedMessages.add(messageId);
                    return false;
                }

                final long currentMailboxId = currentCursor.getLong(UPDATES_MAILBOX_KEY_COLUMN);
                final int currentFlags = currentCursor.getInt(UPDATES_FLAG_COLUMN);

                // Handle message deletion (i.e. move to trash).
                if (currentMailboxId == trashMailboxId) {
                    mUpdatedMessages.add(messageId);
                    addDeleteMessageCommand(s, serverId, !hasCommands);
                    // Also mark the message as moved in the DB (so the copy will be deleted if/when
                    // the server version is synced)
                    final ContentValues cv = new ContentValues(1);
                    cv.put(MessageColumns.FLAGS, currentFlags | MESSAGE_FLAG_MOVED_MESSAGE);
                    mContentResolver.update(
                            ContentUris.withAppendedId(Message.CONTENT_URI, messageId),
                            cv, null, null);
                    return true;
                }

                // Handle message moved.
                final long originalMailboxId =
                        updatedMessageCursor.getLong(UPDATES_MAILBOX_KEY_COLUMN);
                if (currentMailboxId != originalMailboxId) {
                    final String sourceMailboxId = getServerIdForMailbox(originalMailboxId);
                    final String destMailboxId;
                    if (sourceMailboxId != null) {
                        destMailboxId = getServerIdForMailbox(currentMailboxId);
                    } else {
                        destMailboxId = null;
                    }
                    if (destMailboxId != null) {
                        mMessagesToMove.add(
                                new MoveRequest(messageId, serverId, currentFlags,
                                        originalMailboxId, sourceMailboxId, destMailboxId));
                        // Since we don't want to remove this message from updated table until it
                        // downsyncs, we do not add it to updatedIds.
                    } else {
                        // TODO: If the message's mailboxes aren't there, handle it better.
                    }
                } else {
                    mUpdatedMessages.add(messageId);
                }

                final int favorite;
                final boolean favoriteChanged;
                // We can only send flag changes to the server in 12.0 or later
                if (getProtocolVersion() >= Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
                    favorite = currentCursor.getInt(UPDATES_FAVORITE_COLUMN);
                    favoriteChanged =
                            favorite != updatedMessageCursor.getInt(UPDATES_FAVORITE_COLUMN);
                } else {
                    favorite = 0;
                    favoriteChanged = false;
                }

                final int read = currentCursor.getInt(UPDATES_READ_COLUMN);
                final boolean readChanged =
                        read != updatedMessageCursor.getInt(UPDATES_READ_COLUMN);

                if (favoriteChanged || readChanged) {
                    if (!hasCommands) {
                        s.start(Tags.SYNC_COMMANDS);
                    }
                    s.start(Tags.SYNC_CHANGE);
                    s.data(Tags.SYNC_SERVER_ID, serverId);
                    s.start(Tags.SYNC_APPLICATION_DATA);
                    if (readChanged) {
                        s.data(Tags.EMAIL_READ, Integer.toString(read));
                    }

                    // "Flag" is a relatively complex concept in EAS 12.0 and above.  It is not only
                    // the boolean "favorite" that we think of in Gmail, but it also represents a
                    // follow up action, which can include a subject, start and due dates, and even
                    // recurrences.  We don't support any of this as yet, but EAS 12.0 and higher
                    // require that a flag contain a status, a type, and four date fields, two each
                    // for start date and end (due) date.
                    if (favoriteChanged) {
                        if (favorite != 0) {
                            // Status 2 = set flag
                            s.start(Tags.EMAIL_FLAG).data(Tags.EMAIL_FLAG_STATUS, "2");
                            // "FollowUp" is the standard type
                            s.data(Tags.EMAIL_FLAG_TYPE, "FollowUp");
                            final long now = System.currentTimeMillis();
                            final Calendar calendar =
                                GregorianCalendar.getInstance(TimeZone.getTimeZone("GMT"));
                            calendar.setTimeInMillis(now);
                            // Flags are required to have a start date and end date (duplicated)
                            // First, we'll set the current date/time in GMT as the start time
                            String utc = formatDateTime(calendar);
                            s.data(Tags.TASK_START_DATE, utc).data(Tags.TASK_UTC_START_DATE, utc);
                            // And then we'll use one week from today for completion date
                            calendar.setTimeInMillis(now + DateUtils.WEEK_IN_MILLIS);
                            utc = formatDateTime(calendar);
                            s.data(Tags.TASK_DUE_DATE, utc).data(Tags.TASK_UTC_DUE_DATE, utc);
                            s.end();
                        } else {
                            s.tag(Tags.EMAIL_FLAG);
                        }
                    }
                    s.end().end();  // SYNC_APPLICATION_DATA, SYNC_CHANGE
                    return true;
                }
            }
        } finally {
            currentCursor.close();
        }
        return false;
    }

    /**
     * Send all message move requests and process responses.
     * TODO: Make this just one request/response, which requires changes to the parser.
     * @throws IOException
     */
    private void performMessageMove() throws IOException {

        for (final MoveRequest req : mMessagesToMove) {
            final Serializer s = new Serializer();
            s.start(Tags.MOVE_MOVE_ITEMS);
            s.start(Tags.MOVE_MOVE);
            s.data(Tags.MOVE_SRCMSGID, req.messageServerId);
            s.data(Tags.MOVE_SRCFLDID, req.sourceFolderServerId);
            s.data(Tags.MOVE_DSTFLDID, req.destFolderServerId);
            s.end();
            s.end().done();
            final EasResponse resp = sendHttpClientPost("MoveItems", s.toByteArray());
            try {
                final int status = resp.getStatus();
                if (status == HttpStatus.SC_OK) {
                    if (!resp.isEmpty()) {
                        final MoveItemsParser p = new MoveItemsParser(resp.getInputStream());
                        p.parse();
                        final int statusCode = p.getStatusCode();
                        final ContentValues cv = new ContentValues();
                        if (statusCode == MoveItemsParser.STATUS_CODE_REVERT) {
                            // Restore the old mailbox id
                            cv.put(MessageColumns.MAILBOX_KEY, req.sourceFolderId);
                            mContentResolver.update(
                                    ContentUris.withAppendedId(Message.CONTENT_URI, req.messageId),
                                    cv, null, null);
                        } else if (statusCode == MoveItemsParser.STATUS_CODE_SUCCESS) {
                            // Update with the new server id
                            cv.put(SyncColumns.SERVER_ID, p.getNewServerId());
                            cv.put(Message.FLAGS, req.messageFlags | MESSAGE_FLAG_MOVED_MESSAGE);
                            mContentResolver.update(
                                    ContentUris.withAppendedId(Message.CONTENT_URI, req.messageId),
                                    cv, null, null);
                        }
                        if (statusCode == MoveItemsParser.STATUS_CODE_SUCCESS
                                || statusCode == MoveItemsParser.STATUS_CODE_REVERT) {
                            // If we revert or succeed, we no longer need the update information
                            // OR the now-duplicate email (the new copy will be synced down)
                            mContentResolver.delete(ContentUris.withAppendedId(
                                    Message.UPDATED_CONTENT_URI, req.messageId), null, null);
                        } else {
                            // In this case, we're retrying, so do nothing.  The request will be
                            // handled next sync
                        }
                    }
                } else if (EasResponse.isAuthError(status)) {
                    throw new EasAuthenticationException();
                } else {
                    LogUtils.i(TAG, "Move items request failed, code: %d", status);
                    throw new IOException();
                }
            } finally {
                resp.close();
            }
        }
    }

    /**
     * For each message in Message_Updates, add a sync command if appropriate, and add its id to
     * our list of processed messages if appropriate.
     * @param s The {@link Serializer} for this sync request.
     * @param hasCommands Whether sync commands have already been written to s.
     * @return Whether this function added any sync commands to s.
     * @throws IOException
     */
    private boolean addUpdatedCommands(final Serializer s, final boolean hasCommands)
            throws IOException {
        // Find our trash mailbox, since deletions will have been moved there.
        final long trashMailboxId =
                Mailbox.findMailboxOfType(mContext, mMailbox.mAccountKey, Mailbox.TYPE_TRASH);
        final Cursor c = mContentResolver.query(Message.UPDATED_CONTENT_URI, UPDATES_PROJECTION,
                MessageColumns.MAILBOX_KEY + '=' + mMailbox.mId, null, null);
        boolean addedCommands = false;
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    addedCommands |= handleOneUpdatedMessage(c, s, hasCommands || addedCommands,
                            trashMailboxId);
                }
            } finally {
                c.close();
            }
        }

        // mMessagesToMove is now populated. If it's non-empty, let's send the move request now.
        if (!mMessagesToMove.isEmpty()) {
            performMessageMove();
        }

        return addedCommands;
    }

    /**
     * Add FETCH commands for messages that need a body (i.e. we didn't find it during our earlier
     * sync; this happens only in EAS 2.5 where the body couldn't be found after parsing the
     * message's MIME data).
     * @param s The {@link Serializer} for this sync request.
     * @param hasCommands Whether sync commands have already been written to s.
     * @return Whether this function added any sync commands to s.
     * @throws IOException
     */
    private boolean addFetchCommands(final Serializer s, final boolean hasCommands)
            throws IOException {
        if (!hasCommands && !mMessagesToFetch.isEmpty()) {
            s.start(Tags.SYNC_COMMANDS);
        }
        for (final String serverId : mMessagesToFetch) {
            s.start(Tags.SYNC_FETCH).data(Tags.SYNC_SERVER_ID, serverId).end();
        }

        return !mMessagesToFetch.isEmpty();
    }

    @Override
    protected void setUpsyncCommands(final Serializer s) throws IOException {
        boolean addedCommands = addDeletedCommands(s, false);
        addedCommands = addFetchCommands(s, addedCommands);
        addedCommands = addUpdatedCommands(s, addedCommands);
        if (addedCommands) {
            s.end();
        }
    }

    @Override
    protected void cleanup(final int syncResult) {
        // After a successful sync, we have things to delete from the DB.
        if (syncResult != SYNC_RESULT_FAILED) {
            ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
            // Delete any moved messages (since we've just synced the mailbox, and no longer need
            // the placeholder message); this prevents duplicates from appearing in the mailbox.
            // TODO: Verify this still makes sense.
            ops.add(ContentProviderOperation.newDelete(Message.CONTENT_URI)
                    .withSelection(WHERE_MAILBOX_KEY_AND_MOVED,
                            new String[] {Long.toString(mMailbox.mId)}).build());
            // Delete any entries in Message_Updates and Message_Deletes that were upsynced.
            for (final long id: mDeletedMessages) {
                ops.add(ContentProviderOperation.newDelete(
                        ContentUris.withAppendedId(Message.DELETED_CONTENT_URI, id)).build());
            }
            for (final long id: mUpdatedMessages) {
                ops.add(ContentProviderOperation.newDelete(
                        ContentUris.withAppendedId(Message.UPDATED_CONTENT_URI, id)).build());
            }
            try {
                mContentResolver.applyBatch(EmailContent.AUTHORITY, ops);
            } catch (final RemoteException e) {
                // TODO: Improve handling.
            } catch (final OperationApplicationException e) {
                // TODO: Improve handing.
            }
        }

        if (syncResult == SYNC_RESULT_MORE_AVAILABLE) {
            // Prepare our member variables for another sync request.
            mDeletedMessages.clear();
            mUpdatedMessages.clear();
            mMessagesToFetch.clear();
            mMessagesToMove.clear();
        }
    }
}
