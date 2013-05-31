package com.android.exchange.service;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.provider.SyncStateContract;

import com.android.emailcommon.TrafficFlags;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;
import com.android.exchange.adapter.AbstractSyncParser;
import com.android.exchange.adapter.ContactsSyncAdapter;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;

import java.io.IOException;
import java.io.InputStream;

/**
 * Performs an Exchange sync for contacts.
 * Contact state is in the contacts provider, not in our DB (and therefore not in e.g. mMailbox).
 * The Mailbox in the Email DB is only useful for serverId and syncInterval.
 */
public class EasContactsSyncHandler extends EasSyncHandler {
    private final android.accounts.Account mAccountManagerAccount;

    public EasContactsSyncHandler(final Context context, final ContentResolver contentResolver,
            final android.accounts.Account accountManagerAccount, final Account account,
            final Mailbox mailbox, final Bundle syncExtras, final SyncResult syncResult) {
        super(context, contentResolver, account, mailbox, syncExtras, syncResult);
        mAccountManagerAccount = accountManagerAccount;
    }

    @Override
    protected int getTrafficFlag() {
        return TrafficFlags.DATA_CONTACTS;
    }

    @Override
    protected String getSyncKey() {
        // mMailbox.mSyncKey is bogus since state is stored by the contacts provider, so we
        // need to fetch the data from there.
        // However, we need for that value to be reasonable, so we set it here once we fetch it.
        final ContentProviderClient client = mContentResolver.acquireContentProviderClient(
                ContactsContract.AUTHORITY_URI);
        try {
            final byte[] data = SyncStateContract.Helpers.get(client,
                    ContactsContract.SyncState.CONTENT_URI, mAccountManagerAccount);
            if (data == null || data.length == 0) {
                // We don't have a sync key yet, initialize it.
                // TODO: Should we leave it and just let the first successful sync set it?
                /*
                mMailbox.mSyncKey = "0";
                SyncStateContract.Helpers.set(client, ContactsContract.SyncState.CONTENT_URI,
                            mAccountManagerAccount, "0".getBytes());
                // Make sure ungrouped contacts for Exchange are visible by default.
                final ContentValues cv = new ContentValues(3);
                cv.put(ContactsContract.Groups.ACCOUNT_NAME, mAccount.mEmailAddress);
                cv.put(ContactsContract.Groups.ACCOUNT_TYPE, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE);
                cv.put(ContactsContract.Settings.UNGROUPED_VISIBLE, true);
                client.insert(addCallerIsSyncAdapterParameter(Settings.CONTENT_URI), cv);
                */
                mMailbox.mSyncKey = "0";
            } else {
                mMailbox.mSyncKey = new String(data);
            }
            return mMailbox.mSyncKey;
        } catch (final RemoteException e) {
            return null;
        }
    }

    @Override
    protected String getFolderClassName() {
        return "Contacts";
    }


    @Override
    protected AbstractSyncParser getParser(final InputStream is) throws IOException {
        return new ContactsSyncAdapter.EasContactsSyncParser(mContext, mContentResolver, is,
                mMailbox, mAccount, mAccountManagerAccount);
    }

    @Override
    protected void setInitialSyncOptions(final Serializer s) throws IOException {
        // These are the tags we support for upload; whenever we add/remove support
        // (in addData), we need to update this list
        s.start(Tags.SYNC_SUPPORTED);
        s.tag(Tags.CONTACTS_FIRST_NAME);
        s.tag(Tags.CONTACTS_LAST_NAME);
        s.tag(Tags.CONTACTS_MIDDLE_NAME);
        s.tag(Tags.CONTACTS_SUFFIX);
        s.tag(Tags.CONTACTS_COMPANY_NAME);
        s.tag(Tags.CONTACTS_JOB_TITLE);
        s.tag(Tags.CONTACTS_EMAIL1_ADDRESS);
        s.tag(Tags.CONTACTS_EMAIL2_ADDRESS);
        s.tag(Tags.CONTACTS_EMAIL3_ADDRESS);
        s.tag(Tags.CONTACTS_BUSINESS2_TELEPHONE_NUMBER);
        s.tag(Tags.CONTACTS_BUSINESS_TELEPHONE_NUMBER);
        s.tag(Tags.CONTACTS2_MMS);
        s.tag(Tags.CONTACTS_BUSINESS_FAX_NUMBER);
        s.tag(Tags.CONTACTS2_COMPANY_MAIN_PHONE);
        s.tag(Tags.CONTACTS_HOME_FAX_NUMBER);
        s.tag(Tags.CONTACTS_HOME_TELEPHONE_NUMBER);
        s.tag(Tags.CONTACTS_HOME2_TELEPHONE_NUMBER);
        s.tag(Tags.CONTACTS_MOBILE_TELEPHONE_NUMBER);
        s.tag(Tags.CONTACTS_CAR_TELEPHONE_NUMBER);
        s.tag(Tags.CONTACTS_RADIO_TELEPHONE_NUMBER);
        s.tag(Tags.CONTACTS_PAGER_NUMBER);
        s.tag(Tags.CONTACTS_ASSISTANT_TELEPHONE_NUMBER);
        s.tag(Tags.CONTACTS2_IM_ADDRESS);
        s.tag(Tags.CONTACTS2_IM_ADDRESS_2);
        s.tag(Tags.CONTACTS2_IM_ADDRESS_3);
        s.tag(Tags.CONTACTS_BUSINESS_ADDRESS_CITY);
        s.tag(Tags.CONTACTS_BUSINESS_ADDRESS_COUNTRY);
        s.tag(Tags.CONTACTS_BUSINESS_ADDRESS_POSTAL_CODE);
        s.tag(Tags.CONTACTS_BUSINESS_ADDRESS_STATE);
        s.tag(Tags.CONTACTS_BUSINESS_ADDRESS_STREET);
        s.tag(Tags.CONTACTS_HOME_ADDRESS_CITY);
        s.tag(Tags.CONTACTS_HOME_ADDRESS_COUNTRY);
        s.tag(Tags.CONTACTS_HOME_ADDRESS_POSTAL_CODE);
        s.tag(Tags.CONTACTS_HOME_ADDRESS_STATE);
        s.tag(Tags.CONTACTS_HOME_ADDRESS_STREET);
        s.tag(Tags.CONTACTS_OTHER_ADDRESS_CITY);
        s.tag(Tags.CONTACTS_OTHER_ADDRESS_COUNTRY);
        s.tag(Tags.CONTACTS_OTHER_ADDRESS_POSTAL_CODE);
        s.tag(Tags.CONTACTS_OTHER_ADDRESS_STATE);
        s.tag(Tags.CONTACTS_OTHER_ADDRESS_STREET);
        s.tag(Tags.CONTACTS_YOMI_COMPANY_NAME);
        s.tag(Tags.CONTACTS_YOMI_FIRST_NAME);
        s.tag(Tags.CONTACTS_YOMI_LAST_NAME);
        s.tag(Tags.CONTACTS2_NICKNAME);
        s.tag(Tags.CONTACTS_ASSISTANT_NAME);
        s.tag(Tags.CONTACTS2_MANAGER_NAME);
        s.tag(Tags.CONTACTS_SPOUSE);
        s.tag(Tags.CONTACTS_DEPARTMENT);
        s.tag(Tags.CONTACTS_TITLE);
        s.tag(Tags.CONTACTS_OFFICE_LOCATION);
        s.tag(Tags.CONTACTS2_CUSTOMER_ID);
        s.tag(Tags.CONTACTS2_GOVERNMENT_ID);
        s.tag(Tags.CONTACTS2_ACCOUNT_NAME);
        s.tag(Tags.CONTACTS_ANNIVERSARY);
        s.tag(Tags.CONTACTS_BIRTHDAY);
        s.tag(Tags.CONTACTS_WEBPAGE);
        s.tag(Tags.CONTACTS_PICTURE);
        s.end(); // SYNC_SUPPORTED
    }

    @Override
    protected void setNonInitialSyncOptions(final Serializer s) throws IOException {
        setPimSyncOptions(s, null);
    }

    @Override
    protected void setUpsyncCommands(final Serializer s) throws IOException {

    }
}
