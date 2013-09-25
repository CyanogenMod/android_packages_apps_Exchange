/*
 * Copyright (C) 2008-2009 Marc Blank
 * Licensed to The Android Open Source Project.
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

package com.android.exchange.adapter;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.MailboxUtilities;
import com.android.emailcommon.service.SyncWindow;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.exchange.CommandStatusException;
import com.android.exchange.CommandStatusException.CommandStatus;
import com.android.exchange.Eas;
import com.android.exchange.ExchangeService;
import com.google.common.annotations.VisibleForTesting;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Parse the result of a FolderSync command
 *
 * Handles the addition, deletion, and changes to folders in the user's Exchange account.
 **/

public class FolderSyncParser extends AbstractSyncParser {

    public static final String TAG = "FolderSyncParser";

    // These are defined by the EAS protocol
    public static final int USER_GENERIC_TYPE = 1;
    public static final int INBOX_TYPE = 2;
    public static final int DRAFTS_TYPE = 3;
    public static final int DELETED_TYPE = 4;
    public static final int SENT_TYPE = 5;
    public static final int OUTBOX_TYPE = 6;
    public static final int TASKS_TYPE = 7;
    public static final int CALENDAR_TYPE = 8;
    public static final int CONTACTS_TYPE = 9;
    public static final int NOTES_TYPE = 10;
    public static final int JOURNAL_TYPE = 11;
    public static final int USER_MAILBOX_TYPE = 12;

    // Chunk size for our mailbox commits
    private final static int MAILBOX_COMMIT_SIZE = 20;
    // Max mailboxes per account
    private final static int MAX_MAILBOXES_PER_ACCOUNT = 1000000;

    // EAS types that we are willing to consider valid folders for EAS sync
    private static final List<Integer> VALID_EAS_FOLDER_TYPES = Arrays.asList(INBOX_TYPE,
            DRAFTS_TYPE, DELETED_TYPE, SENT_TYPE, OUTBOX_TYPE, USER_MAILBOX_TYPE, CALENDAR_TYPE,
            CONTACTS_TYPE, USER_GENERIC_TYPE);

    public static final String ALL_BUT_ACCOUNT_MAILBOX = MailboxColumns.ACCOUNT_KEY + "=? and " +
        MailboxColumns.TYPE + "!=" + Mailbox.TYPE_EAS_ACCOUNT_MAILBOX;

    private static final String WHERE_SERVER_ID_AND_ACCOUNT = MailboxColumns.SERVER_ID + "=? and " +
        MailboxColumns.ACCOUNT_KEY + "=?";

    private static final String WHERE_DISPLAY_NAME_AND_ACCOUNT = MailboxColumns.DISPLAY_NAME +
        "=? and " + MailboxColumns.ACCOUNT_KEY + "=?";

    private static final String WHERE_PARENT_SERVER_ID_AND_ACCOUNT =
        MailboxColumns.PARENT_SERVER_ID +"=? and " + MailboxColumns.ACCOUNT_KEY + "=?";

    private static final String[] MAILBOX_ID_COLUMNS_PROJECTION =
        new String[] {MailboxColumns.ID, MailboxColumns.SERVER_ID, MailboxColumns.PARENT_SERVER_ID};
    private static final int MAILBOX_ID_COLUMNS_ID = 0;
    private static final int MAILBOX_ID_COLUMNS_SERVER_ID = 1;
    private static final int MAILBOX_ID_COLUMNS_PARENT_SERVER_ID = 2;

    @VisibleForTesting
    long mAccountId;
    @VisibleForTesting
    String mAccountIdAsString;
    @VisibleForTesting
    boolean mInUnitTest = false;

    private String[] mBindArguments = new String[2];
    private ArrayList<ContentProviderOperation> mOperations =
        new ArrayList<ContentProviderOperation>();
    private boolean mInitialSync;
    private ArrayList<String> mParentFixupsNeeded = new ArrayList<String>();
    private boolean mFixupUninitializedNeeded = false;
    // If true, we only care about status (this is true when validating an account) and ignore
    // other data
    private final boolean mStatusOnly;

    private static final ContentValues UNINITIALIZED_PARENT_KEY = new ContentValues();

    {
        UNINITIALIZED_PARENT_KEY.put(MailboxColumns.PARENT_KEY, Mailbox.PARENT_KEY_UNINITIALIZED);
    }

    public FolderSyncParser(final Context context, final ContentResolver resolver,
            final InputStream in, final Account account, final boolean statusOnly)
                    throws IOException {
        super(context, resolver, in, null, account);
        mAccountId = mAccount.mId;
        mAccountIdAsString = Long.toString(mAccountId);
        mStatusOnly = statusOnly;
    }

    public FolderSyncParser(InputStream in, AbstractSyncAdapter adapter) throws IOException {
        this(in, adapter, false);
    }

    public FolderSyncParser(InputStream in, AbstractSyncAdapter adapter, boolean statusOnly)
            throws IOException {
        super(in, adapter);
        mAccountId = mAccount.mId;
        mAccountIdAsString = Long.toString(mAccountId);
        mStatusOnly = statusOnly;
    }

    @Override
    public boolean parse() throws IOException, CommandStatusException {
        int status;
        boolean res = false;
        boolean resetFolders = false;
        // Since we're now (potentially) committing mailboxes in chunks, ensure that we start with
        // only the account mailbox
        String key = mAccount.mSyncKey;
        mInitialSync = (key == null) || "0".equals(key);
        if (mInitialSync) {
            mContentResolver.delete(Mailbox.CONTENT_URI, ALL_BUT_ACCOUNT_MAILBOX,
                    new String[] {Long.toString(mAccountId)});
        }
        if (nextTag(START_DOCUMENT) != Tags.FOLDER_FOLDER_SYNC)
            throw new EasParserException();
        while (nextTag(START_DOCUMENT) != END_DOCUMENT) {
            if (tag == Tags.FOLDER_STATUS) {
                status = getValueInt();
                // Do a sanity check on the account here; if we have any duplicated folders, we'll
                // act as though we have a bad folder sync key (wipe/reload mailboxes)
                // Note: The ContentValues isn't used, but no point creating a new one
                int dupes = 0;
                if (mAccountId > 0) {
                    dupes = mContentResolver.update(
                            ContentUris.withAppendedId(EmailContent.ACCOUNT_CHECK_URI, mAccountId),
                            UNINITIALIZED_PARENT_KEY, null, null);
                }
                if (dupes > 0) {
                    String e = "Duplicate mailboxes found for account " + mAccountId + ": " + dupes;
                    // For verbose logging, make sure this is in emaillog.txt
                    userLog(e);
                    // Worthy of logging, regardless
                    Log.w(Logging.LOG_TAG, e);
                    status = Eas.FOLDER_STATUS_INVALID_KEY;
                }
                if (status != Eas.FOLDER_STATUS_OK) {
                    // If the account hasn't been saved, this is a validation attempt, so we don't
                    // try reloading the folder list...
                    if (CommandStatus.isDeniedAccess(status) ||
                            CommandStatus.isNeedsProvisioning(status) ||
                            (mAccount.mId == Account.NOT_SAVED)) {
                        throw new CommandStatusException(status);
                    // Note that we need to catch both old-style (Eas.FOLDER_STATUS_INVALID_KEY)
                    // and EAS 14 style command status
                    } else if (status == Eas.FOLDER_STATUS_INVALID_KEY ||
                            CommandStatus.isBadSyncKey(status)) {
                        // Delete PIM data
                        ExchangeService.deleteAccountPIMData(mContext, mAccountId);
                        // Save away any mailbox sync information that is NOT default
                        saveMailboxSyncOptions();
                        // And only then, delete mailboxes
                        mContentResolver.delete(Mailbox.CONTENT_URI,
                                MailboxColumns.ACCOUNT_KEY + "=?",
                                new String[] {Long.toString(mAccountId)});
                        // Reconstruct _main
                        res = true;
                        resetFolders = true;
                        // Reset the sync key and save (this should trigger the AccountObserver
                        // in ExchangeService, which will recreate the account mailbox, which
                        // will then start syncing folders, etc.)
                        mAccount.mSyncKey = "0";
                        ContentValues cv = new ContentValues();
                        cv.put(AccountColumns.SYNC_KEY, mAccount.mSyncKey);
                        mContentResolver.update(ContentUris.withAppendedId(Account.CONTENT_URI,
                                mAccount.mId), cv, null, null);
                    } else {
                        // Other errors are at the server, so let's throw an error that will
                        // cause this sync to be retried at a later time
                        throw new EasParserException("Folder status error");
                    }
                }
            } else if (tag == Tags.FOLDER_SYNC_KEY) {
                String newKey = getValue();
                if (!resetFolders) {
                    mAccount.mSyncKey = newKey;
                }
            } else if (tag == Tags.FOLDER_CHANGES) {
                if (mStatusOnly) return res;
                changesParser(mOperations, mInitialSync);
            } else
                skipTag();
        }
        if (!mStatusOnly) {
            commit();
        }
        return res;
    }

    private Cursor getServerIdCursor(String serverId) {
        mBindArguments[0] = serverId;
        mBindArguments[1] = mAccountIdAsString;
        return mContentResolver.query(Mailbox.CONTENT_URI, MAILBOX_ID_COLUMNS_PROJECTION,
                WHERE_SERVER_ID_AND_ACCOUNT, mBindArguments, null);
    }

    private void deleteParser(ArrayList<ContentProviderOperation> ops) throws IOException {
        while (nextTag(Tags.FOLDER_DELETE) != END) {
            switch (tag) {
                case Tags.FOLDER_SERVER_ID:
                    final String serverId = getValue();
                    // Find the mailbox in this account with the given serverId
                    final Cursor c = getServerIdCursor(serverId);
                    try {
                        if (c.moveToFirst()) {
                            userLog("Deleting ", serverId);
                            final long mailboxId = c.getLong(MAILBOX_ID_COLUMNS_ID);
                            ops.add(ContentProviderOperation.newDelete(
                                    ContentUris.withAppendedId(Mailbox.CONTENT_URI,
                                            mailboxId)).build());
                            AttachmentUtilities.deleteAllMailboxAttachmentFiles(mContext,
                                    mAccountId, mailboxId);
                            if (!mInitialSync) {
                                String parentId = c.getString(MAILBOX_ID_COLUMNS_PARENT_SERVER_ID);
                                if (!TextUtils.isEmpty(parentId)) {
                                    mParentFixupsNeeded.add(parentId);
                                }
                            }
                        }
                    } finally {
                        c.close();
                    }
                    break;
                default:
                    skipTag();
            }
        }
    }

    private static class SyncOptions {
        private final int mInterval;
        private final int mLookback;

        private SyncOptions(int interval, int lookback) {
            mInterval = interval;
            mLookback = lookback;
        }
    }

    private static final String MAILBOX_STATE_SELECTION =
        MailboxColumns.ACCOUNT_KEY + "=? AND (" + MailboxColumns.SYNC_INTERVAL + "!=" +
            Account.CHECK_INTERVAL_NEVER + " OR " + Mailbox.SYNC_LOOKBACK + "!=" +
            SyncWindow.SYNC_WINDOW_UNKNOWN + ")";

    private static final String[] MAILBOX_STATE_PROJECTION = new String[] {
        MailboxColumns.SERVER_ID, MailboxColumns.SYNC_INTERVAL, MailboxColumns.SYNC_LOOKBACK};
    private static final int MAILBOX_STATE_SERVER_ID = 0;
    private static final int MAILBOX_STATE_INTERVAL = 1;
    private static final int MAILBOX_STATE_LOOKBACK = 2;
    @VisibleForTesting
    final HashMap<String, SyncOptions> mSyncOptionsMap = new HashMap<String, SyncOptions>();

    /**
     * For every mailbox in this account that has a non-default interval or lookback, save those
     * values.
     */
    @VisibleForTesting
    void saveMailboxSyncOptions() {
        // Shouldn't be necessary, but...
        mSyncOptionsMap.clear();
        Cursor c = mContentResolver.query(Mailbox.CONTENT_URI, MAILBOX_STATE_PROJECTION,
                MAILBOX_STATE_SELECTION, new String[] {mAccountIdAsString}, null);
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    mSyncOptionsMap.put(c.getString(MAILBOX_STATE_SERVER_ID),
                            new SyncOptions(c.getInt(MAILBOX_STATE_INTERVAL),
                                    c.getInt(MAILBOX_STATE_LOOKBACK)));
                }
            } finally {
                c.close();
            }
        }
    }

    /**
     * For every set of saved mailbox sync options, try to find and restore those values
     */
    @VisibleForTesting
    void restoreMailboxSyncOptions() {
        try {
            ContentValues cv = new ContentValues();
            mBindArguments[1] = mAccountIdAsString;
            for (String serverId: mSyncOptionsMap.keySet()) {
                SyncOptions options = mSyncOptionsMap.get(serverId);
                cv.put(MailboxColumns.SYNC_INTERVAL, options.mInterval);
                cv.put(MailboxColumns.SYNC_LOOKBACK, options.mLookback);
                mBindArguments[0] = serverId;
                // If we match account and server id, set the sync options
                mContentResolver.update(Mailbox.CONTENT_URI, cv, WHERE_SERVER_ID_AND_ACCOUNT,
                        mBindArguments);
            }
        } finally {
            mSyncOptionsMap.clear();
        }
    }

    private Mailbox addParser() throws IOException {
        String name = null;
        String serverId = null;
        String parentId = null;
        int type = 0;

        while (nextTag(Tags.FOLDER_ADD) != END) {
            switch (tag) {
                case Tags.FOLDER_DISPLAY_NAME: {
                    name = getValue();
                    break;
                }
                case Tags.FOLDER_TYPE: {
                    type = getValueInt();
                    break;
                }
                case Tags.FOLDER_PARENT_ID: {
                    parentId = getValue();
                    break;
                }
                case Tags.FOLDER_SERVER_ID: {
                    serverId = getValue();
                    break;
                }
                default:
                    skipTag();
            }
        }

        if (VALID_EAS_FOLDER_TYPES.contains(type)) {
            Mailbox mailbox = new Mailbox();
            mailbox.mDisplayName = name;
            mailbox.mServerId = serverId;
            mailbox.mAccountKey = mAccountId;
            mailbox.mType = Mailbox.TYPE_MAIL;
            // Note that all mailboxes default to checking "never" (i.e. manual sync only)
            // We set specific intervals for inbox, contacts, and (eventually) calendar
            mailbox.mSyncInterval = Mailbox.CHECK_INTERVAL_NEVER;
            switch (type) {
                case INBOX_TYPE:
                    mailbox.mType = Mailbox.TYPE_INBOX;
                    mailbox.mSyncInterval = mAccount.mSyncInterval;
                    break;
                case CONTACTS_TYPE:
                    mailbox.mType = Mailbox.TYPE_CONTACTS;
                    mailbox.mSyncInterval = mAccount.mSyncInterval;
                    break;
                case OUTBOX_TYPE:
                    // TYPE_OUTBOX mailboxes are known by ExchangeService to sync whenever they
                    // aren't empty.  The value of mSyncFrequency is ignored for this kind of
                    // mailbox.
                    mailbox.mType = Mailbox.TYPE_OUTBOX;
                    break;
                case SENT_TYPE:
                    mailbox.mType = Mailbox.TYPE_SENT;
                    break;
                case DRAFTS_TYPE:
                    mailbox.mType = Mailbox.TYPE_DRAFTS;
                    break;
                case DELETED_TYPE:
                    mailbox.mType = Mailbox.TYPE_TRASH;
                    break;
                case CALENDAR_TYPE:
                    mailbox.mType = Mailbox.TYPE_CALENDAR;
                    mailbox.mSyncInterval = mAccount.mSyncInterval;
                    break;
            }

            // Make boxes like Contacts and Calendar invisible in the folder list
            mailbox.mFlagVisible = (mailbox.mType < Mailbox.TYPE_NOT_EMAIL);

            if (!parentId.equals("0")) {
                mailbox.mParentServerId = parentId;
                if (!mInitialSync) {
                    mParentFixupsNeeded.add(parentId);
                }
            }
            // At the least, we'll need to set flags
            mFixupUninitializedNeeded = true;

            return mailbox;
        }
        return null;
    }

    private void updateParser(ArrayList<ContentProviderOperation> ops) throws IOException {
        String serverId = null;
        String displayName = null;
        String parentId = null;
        while (nextTag(Tags.FOLDER_UPDATE) != END) {
            switch (tag) {
                case Tags.FOLDER_SERVER_ID:
                    serverId = getValue();
                    break;
                case Tags.FOLDER_DISPLAY_NAME:
                    displayName = getValue();
                    break;
                case Tags.FOLDER_PARENT_ID:
                    parentId = getValue();
                    break;
                default:
                    skipTag();
                    break;
            }
        }
        // We'll make a change if one of parentId or displayName are specified
        // serverId is required, but let's be careful just the same
        if (serverId != null && (displayName != null || parentId != null)) {
            Cursor c = getServerIdCursor(serverId);
            try {
                // If we find the mailbox (using serverId), make the change
                if (c.moveToFirst()) {
                    userLog("Updating ", serverId);
                    // Fix up old and new parents, as needed
                    if (!TextUtils.isEmpty(parentId)) {
                        mParentFixupsNeeded.add(parentId);
                    }
                    String oldParentId = c.getString(MAILBOX_ID_COLUMNS_PARENT_SERVER_ID);
                    if (!TextUtils.isEmpty(oldParentId)) {
                        mParentFixupsNeeded.add(oldParentId);
                    }
                    // Set display name if we've got one
                    ContentValues cv = new ContentValues();
                    if (displayName != null) {
                        cv.put(Mailbox.DISPLAY_NAME, displayName);
                    }
                    // Save away the server id and uninitialize the parent key
                    cv.put(Mailbox.PARENT_SERVER_ID, parentId);
                    // Clear the parent key; it will be fixed up after the commit
                    cv.put(Mailbox.PARENT_KEY, Mailbox.PARENT_KEY_UNINITIALIZED);
                    ops.add(ContentProviderOperation.newUpdate(
                            ContentUris.withAppendedId(Mailbox.CONTENT_URI,
                                    c.getLong(MAILBOX_ID_COLUMNS_ID))).withValues(cv).build());
                    // Say we need to fixup uninitialized mailboxes
                    mFixupUninitializedNeeded = true;
                }
            } finally {
                c.close();
            }
        }
    }

    private boolean commitMailboxes(ArrayList<ContentProviderOperation> ops) {
        // Commit the mailboxes
        userLog("Applying ", mOperations.size(), " mailbox operations.");
        // Execute the batch; throw IOExceptions if this fails, hoping the issue isn't repeatable
        // If it IS repeatable, there's no good result, since the folder list will be invalid
        try {
            mContentResolver.applyBatch(EmailContent.AUTHORITY, mOperations);
            return true;
        } catch (RemoteException e) {
            userLog("RemoteException in commitMailboxes");
            return false;
        } catch (OperationApplicationException e) {
            userLog("OperationApplicationException in commitMailboxes");
            return false;
        }
    }

    private void changesParser(final ArrayList<ContentProviderOperation> ops,
            final boolean initialSync) throws IOException {

        // Array of added mailboxes
        final ArrayList<Mailbox> addMailboxes = new ArrayList<Mailbox>();

        // Indicate start of (potential) mailbox changes
        MailboxUtilities.startMailboxChanges(mContext, mAccount.mId);

        while (nextTag(Tags.FOLDER_CHANGES) != END) {
            if (tag == Tags.FOLDER_ADD) {
                final Mailbox mailbox = addParser();
                if (mailbox != null) {
                    addMailboxes.add(mailbox);
                }
            } else if (tag == Tags.FOLDER_DELETE) {
                deleteParser(ops);
            } else if (tag == Tags.FOLDER_UPDATE) {
                updateParser(ops);
            } else if (tag == Tags.FOLDER_COUNT) {
                getValueInt();
            } else
                skipTag();
        }

        // Map folder serverId to mailbox (used to validate user mailboxes)
        final HashMap<String, Mailbox> mailboxMap = new HashMap<String, Mailbox>();
        for (final Mailbox mailbox : addMailboxes) {
            mailboxMap.put(mailbox.mServerId, mailbox);
        }
        userLog("Total of " + addMailboxes.size() + " mailboxes parsed");

        // Synchronize on the parser to prevent this being run concurrently
        // (an extremely unlikely event, but nonetheless possible)
        if (mInitialSync)  {
            synchronized (FolderSyncParser.this) {
                // Assign unique sequential ids and set appropriate mailbox flags; it's safe to
                // assume that there won't be more than one million mailboxes defined for an
                // account.  I use millions for ease in debugging (i.e. associating an int in
                // the debugger with an account)
                long mailboxId = (mAccount.mId * MAX_MAILBOXES_PER_ACCOUNT) + 1;
                // Set basic flags
                for (Mailbox mailbox : addMailboxes) {
                    int type = mailbox.mType;
                    if (type <= Mailbox.TYPE_NOT_EMAIL) {
                        mailbox.mFlags |= Mailbox.FLAG_HOLDS_MAIL + Mailbox.FLAG_SUPPORTS_SETTINGS;
                    }
                    // Outbox, Drafts, and Sent don't allow mail to be moved to them
                    if (type == Mailbox.TYPE_MAIL || type == Mailbox.TYPE_TRASH ||
                            type == Mailbox.TYPE_JUNK || type == Mailbox.TYPE_INBOX) {
                        mailbox.mFlags |= Mailbox.FLAG_ACCEPTS_MOVED_MAIL;
                    }
                    mailbox.mId = mailboxId++;
                }
                // Set parent mailbox key and hierarchical name; set parent flags on parents
                for (Mailbox mailbox: addMailboxes) {
                    String parentServerId = mailbox.mParentServerId;
                    if (parentServerId == null || parentServerId.equals("0")) {
                        mailbox.mParentKey = Mailbox.NO_MAILBOX;
                    } else {
                        Mailbox parentMailbox = mailboxMap.get(parentServerId);
                        if (parentMailbox != null) {
                            mailbox.mParentKey = parentMailbox.mId;
                            parentMailbox.mFlags |=
                                    Mailbox.FLAG_HAS_CHILDREN + Mailbox.FLAG_CHILDREN_VISIBLE;
                            String hierarchicalName = mailbox.mDisplayName;
                            while (parentMailbox != null) {
                                hierarchicalName = parentMailbox.mDisplayName + "/" +
                                        hierarchicalName;
                                if (parentMailbox.mParentServerId != null &&
                                        !parentMailbox.mParentServerId.equals("0")) {
                                    parentMailbox = mailboxMap.get(parentMailbox.mParentServerId);
                                } else {
                                    break;
                                }
                            }
                            mailbox.mHierarchicalName = hierarchicalName;
                        } else {
                            userLog("Parent not found with serverId = " + parentServerId);
                        }
                    }
                }
            }

            // Save all the new mailboxes away in groups of 20
            int batchCount = 0;
            for (Mailbox mailbox: addMailboxes) {
                if (mailbox.mId == Mailbox.NO_MAILBOX) {
                    userLog("Skipping mailbox: ", mailbox.mDisplayName);
                    continue;
                }
                if (++batchCount == MAILBOX_COMMIT_SIZE) {
                    if (!commitMailboxes(ops)) {
                        //mService.stop();
                        return;
                    }
                    ops.clear();
                    batchCount = 0;
                }
                userLog("Adding mailbox: ", mailbox.mDisplayName);
                ContentValues initialValues = mailbox.toContentValues();
                // We already have an id if this is the initial sync
                if (mInitialSync) {
                    initialValues.put(MailboxColumns.ID, mailbox.mId);
                }
                ops.add(ContentProviderOperation.newInsert(
                        Mailbox.CONTENT_URI).withValues(initialValues).build());
            }

            // Save away the new sync key with the last batch
            ContentValues cv = new ContentValues();
            cv.put(AccountColumns.SYNC_KEY, mAccount.mSyncKey);
            ops.add(ContentProviderOperation
                    .newUpdate(
                            ContentUris.withAppendedId(Account.CONTENT_URI,
                                    mAccount.mId))
                                    .withValues(cv).build());
            if (!commitMailboxes(ops)) {
                //mService.stop();
                return;
            }
        }

        // If this isn't the initial sync, we need to fix up the hierarchy
        if (!mInitialSync) {
            String accountSelector = Mailbox.ACCOUNT_KEY + "=" + mAccount.mId;
            // For new boxes, setup the parent key and flags
            if (mFixupUninitializedNeeded) {
                MailboxUtilities.fixupUninitializedParentKeys(mContext,
                        accountSelector);
            }
            // For modified parents, reset the flags (and children's parent key)
            for (String parentServerId: mParentFixupsNeeded) {
                Cursor c = mContentResolver.query(Mailbox.CONTENT_URI,
                        Mailbox.CONTENT_PROJECTION, Mailbox.PARENT_SERVER_ID + "=?",
                        new String[] {parentServerId}, null);
                try {
                    if (c.moveToFirst()) {
                        MailboxUtilities.setFlagsAndChildrensParentKey(mContext, c,
                                accountSelector);
                    }
                } finally {
                    c.close();
                }
            }

            MailboxUtilities.setupHierarchicalNames(mContext, mAccount.mId);
        }

        // Signal completion of mailbox changes
        MailboxUtilities.endMailboxChanges(mContext, mAccount.mId);
    }

    /**
     * Not needed for FolderSync parsing; everything is done within changesParser
     */
    @Override
    public void commandsParser() throws IOException {
    }

    /**
     * Clean up after sync
     */
    @Override
    public void commit() throws IOException {
        // Look for sync issues and its children and delete them
        // I'm not aware of any other way to deal with this properly
        mBindArguments[0] = "Sync Issues";
        mBindArguments[1] = mAccountIdAsString;
        Cursor c = mContentResolver.query(Mailbox.CONTENT_URI,
                MAILBOX_ID_COLUMNS_PROJECTION, WHERE_DISPLAY_NAME_AND_ACCOUNT,
                mBindArguments, null);
        String parentServerId = null;
        long id = 0;
        try {
            if (c.moveToFirst()) {
                id = c.getLong(MAILBOX_ID_COLUMNS_ID);
                parentServerId = c.getString(MAILBOX_ID_COLUMNS_SERVER_ID);
            }
        } finally {
            c.close();
        }
        if (parentServerId != null) {
            mContentResolver.delete(ContentUris.withAppendedId(Mailbox.CONTENT_URI, id),
                    null, null);
            mBindArguments[0] = parentServerId;
            mContentResolver.delete(Mailbox.CONTENT_URI, WHERE_PARENT_SERVER_ID_AND_ACCOUNT,
                    mBindArguments);
        }

        // If we have saved options, restore them now
        if (mInitialSync) {
            restoreMailboxSyncOptions();
        }
    }

    @Override
    public void responsesParser() throws IOException {
    }

}
