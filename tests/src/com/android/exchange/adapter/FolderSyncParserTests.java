/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.content.ContentResolver;
import android.test.suitebuilder.annotation.MediumTest;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.SyncWindow;
import com.android.exchange.EasSyncService;
import com.android.exchange.provider.EmailContentSetupUtils;

import java.io.IOException;
import java.util.HashMap;

/**
 * You can run this entire test case with:
 *   runtest -c com.android.exchange.adapter.FolderSyncParserTests exchange
 */
@MediumTest
public class FolderSyncParserTests extends SyncAdapterTestCase<EmailSyncAdapter> {

    // We increment this to generate unique server id's
    private int mServerIdCount = 0;
    private final long mCreationTime = System.currentTimeMillis();

    public FolderSyncParserTests() {
        super();
    }

    public void testIsValidMailFolder() throws IOException {
        EasSyncService service = getTestService();
        EmailSyncAdapter adapter = new EmailSyncAdapter(service);
        FolderSyncParser parser = new FolderSyncParser(getTestInputStream(), adapter);
        HashMap<String, Mailbox> mailboxMap = new HashMap<String, Mailbox>();
        // The parser needs the mAccount set
        parser.mAccount = mAccount;
        mAccount.save(getContext());

        // Don't save the box; just create it, and give it a server id
        Mailbox boxMailType = EmailContentSetupUtils.setupMailbox("box1", mAccount.mId, false,
                mProviderContext, Mailbox.TYPE_MAIL);
        boxMailType.mServerId = "__1:1";
        // Automatically valid since TYPE_MAIL
        assertTrue(parser.isValidMailFolder(boxMailType, mailboxMap));

        Mailbox boxCalendarType = EmailContentSetupUtils.setupMailbox("box", mAccount.mId, false,
                mProviderContext, Mailbox.TYPE_CALENDAR);
        Mailbox boxContactsType = EmailContentSetupUtils.setupMailbox("box", mAccount.mId, false,
                mProviderContext, Mailbox.TYPE_CONTACTS);
        Mailbox boxTasksType = EmailContentSetupUtils.setupMailbox("box", mAccount.mId, false,
                mProviderContext, Mailbox.TYPE_TASKS);
        // Automatically invalid since TYPE_CALENDAR and TYPE_CONTACTS
        assertFalse(parser.isValidMailFolder(boxCalendarType, mailboxMap));
        assertFalse(parser.isValidMailFolder(boxContactsType, mailboxMap));
        assertFalse(parser.isValidMailFolder(boxTasksType, mailboxMap));

        // Unknown boxes are invalid unless they have a parent that's valid
        Mailbox boxUnknownType = EmailContentSetupUtils.setupMailbox("box", mAccount.mId, false,
                mProviderContext, Mailbox.TYPE_UNKNOWN);
        assertFalse(parser.isValidMailFolder(boxUnknownType, mailboxMap));
        boxUnknownType.mParentServerId = boxMailType.mServerId;
        // We shouldn't find the parent yet
        assertFalse(parser.isValidMailFolder(boxUnknownType, mailboxMap));
        // Put the mailbox in the map; the unknown box should now be valid
        mailboxMap.put(boxMailType.mServerId, boxMailType);
        assertTrue(parser.isValidMailFolder(boxUnknownType, mailboxMap));

        // Clear the map, but save away the parent box
        mailboxMap.clear();
        assertFalse(parser.isValidMailFolder(boxUnknownType, mailboxMap));
        boxMailType.save(mProviderContext);
        // The box should now be valid
        assertTrue(parser.isValidMailFolder(boxUnknownType, mailboxMap));

        // Somewhat harder case.  The parent will be in the map, but also unknown.  The parent's
        // parent will be in the database.
        Mailbox boxParentUnknownType = EmailContentSetupUtils.setupMailbox("box", mAccount.mId,
                false, mProviderContext, Mailbox.TYPE_UNKNOWN);
        assertFalse(parser.isValidMailFolder(boxParentUnknownType, mailboxMap));
        // Give the unknown type parent a parent (boxMailType)
        boxParentUnknownType.mServerId = "__1:2";
        boxParentUnknownType.mParentServerId = boxMailType.mServerId;
        // Give our unknown box an unknown parent
        boxUnknownType.mParentServerId = boxParentUnknownType.mServerId;
        // Confirm the box is still invalid
        assertFalse(parser.isValidMailFolder(boxUnknownType, mailboxMap));
        // Put the unknown type parent into the mailbox map
        mailboxMap.put(boxParentUnknownType.mServerId, boxParentUnknownType);
        // Our unknown box should now be valid, because 1) the parent is unknown, BUT 2) the
        // parent's parent is a mail type
        assertTrue(parser.isValidMailFolder(boxUnknownType, mailboxMap));
    }

    private Mailbox setupBoxSync(int interval, int lookback, String serverId) {
        // Don't save the box; just create it, and give it a server id
        Mailbox box = EmailContentSetupUtils.setupMailbox("box1", mAccount.mId, false,
                mProviderContext, Mailbox.TYPE_MAIL);
        box.mSyncInterval = interval;
        box.mSyncLookback = lookback;
        if (serverId != null) {
            box.mServerId = serverId;
        } else {
            box.mServerId = "serverId-" + mCreationTime + '-' + mServerIdCount++;
        }
        box.save(mProviderContext);
        return box;
    }

    private boolean syncOptionsSame(Mailbox a, Mailbox b) {
        if (a.mSyncInterval != b.mSyncInterval) return false;
        if (a.mSyncLookback != b.mSyncLookback) return false;
        return true;
    }

    public void testSaveAndRestoreMailboxSyncOptions() throws IOException {
        EasSyncService service = getTestService();
        EmailSyncAdapter adapter = new EmailSyncAdapter(service);
        FolderSyncParser parser = new FolderSyncParser(getTestInputStream(), adapter);
        mAccount.save(mProviderContext);

        parser.mAccount = mAccount;
        parser.mAccountId = mAccount.mId;
        parser.mAccountIdAsString = Long.toString(mAccount.mId);
        parser.mContext = mProviderContext;
        parser.mContentResolver = mProviderContext.getContentResolver();

        // Don't save the box; just create it, and give it a server id
        Mailbox box1 = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_UNKNOWN,
                null);
        Mailbox box2 = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_UNKNOWN,
                null);
        Mailbox boxa = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_1_MONTH,
                null);
        Mailbox boxb = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_2_WEEKS,
                null);
        Mailbox boxc = setupBoxSync(Account.CHECK_INTERVAL_PUSH, SyncWindow.SYNC_WINDOW_UNKNOWN,
                null);
        Mailbox boxd = setupBoxSync(Account.CHECK_INTERVAL_PUSH, SyncWindow.SYNC_WINDOW_UNKNOWN,
                null);
        Mailbox boxe = setupBoxSync(Account.CHECK_INTERVAL_PUSH, SyncWindow.SYNC_WINDOW_1_DAY,
                null);

        // Save the options (for a, b, c, d, e);
        parser.saveMailboxSyncOptions();
        // There should be 5 entries in the map, and they should be the correct ones
        assertNotNull(parser.mSyncOptionsMap.get(boxa.mServerId));
        assertNotNull(parser.mSyncOptionsMap.get(boxb.mServerId));
        assertNotNull(parser.mSyncOptionsMap.get(boxc.mServerId));
        assertNotNull(parser.mSyncOptionsMap.get(boxd.mServerId));
        assertNotNull(parser.mSyncOptionsMap.get(boxe.mServerId));

        // Delete all the mailboxes in the account
        ContentResolver cr = mProviderContext.getContentResolver();
        cr.delete(Mailbox.CONTENT_URI, Mailbox.ACCOUNT_KEY + "=?",
                new String[] {parser.mAccountIdAsString});

        // Create new boxes, all with default values for interval & window
        Mailbox box1x = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_UNKNOWN,
                box1.mServerId);
        Mailbox box2x = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_UNKNOWN,
                box2.mServerId);
        Mailbox boxax = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_UNKNOWN,
                boxa.mServerId);
        Mailbox boxbx = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_UNKNOWN,
                boxb.mServerId);
        Mailbox boxcx = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_UNKNOWN,
                boxc.mServerId);
        Mailbox boxdx = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_UNKNOWN,
                boxd.mServerId);
        Mailbox boxex = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_UNKNOWN,
                boxe.mServerId);

        // Restore the sync options
        parser.restoreMailboxSyncOptions();
        box1x = Mailbox.restoreMailboxWithId(mProviderContext, box1x.mId);
        box2x = Mailbox.restoreMailboxWithId(mProviderContext, box2x.mId);
        boxax = Mailbox.restoreMailboxWithId(mProviderContext, boxax.mId);
        boxbx = Mailbox.restoreMailboxWithId(mProviderContext, boxbx.mId);
        boxcx = Mailbox.restoreMailboxWithId(mProviderContext, boxcx.mId);
        boxdx = Mailbox.restoreMailboxWithId(mProviderContext, boxdx.mId);
        boxex = Mailbox.restoreMailboxWithId(mProviderContext, boxex.mId);

        assertTrue(syncOptionsSame(box1, box1x));
        assertTrue(syncOptionsSame(box2, box2x));
        assertTrue(syncOptionsSame(boxa, boxax));
        assertTrue(syncOptionsSame(boxb, boxbx));
        assertTrue(syncOptionsSame(boxc, boxcx));
        assertTrue(syncOptionsSame(boxd, boxdx));
        assertTrue(syncOptionsSame(boxe, boxex));
    }
}
