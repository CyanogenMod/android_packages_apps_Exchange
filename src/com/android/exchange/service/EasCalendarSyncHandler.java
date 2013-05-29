package com.android.exchange.service;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.CalendarContract;
import android.provider.SyncStateContract;

import com.android.emailcommon.TrafficFlags;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;
import com.android.exchange.Eas;
import com.android.exchange.adapter.AbstractSyncParser;
import com.android.exchange.adapter.CalendarSyncAdapter;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.utility.CalendarUtilities;

import java.io.IOException;
import java.io.InputStream;

/**
 *
 */
public class EasCalendarSyncHandler extends EasSyncHandler {

    private static final String CALENDAR_SELECTION = CalendarContract.Calendars.ACCOUNT_NAME +
            "=? AND " + CalendarContract.Calendars.ACCOUNT_TYPE + "=?";
    private static final int CALENDAR_SELECTION_ID = 0;

    private final android.accounts.Account mAccountManagerAccount;
    private final long mCalendarId;

    public EasCalendarSyncHandler(final Context context, final ContentResolver contentResolver,
            final android.accounts.Account accountManagerAccount, final Account account,
            final Mailbox mailbox, final Bundle syncExtras, final SyncResult syncResult) {
        super(context, contentResolver, account, mailbox, syncExtras, syncResult);
        mAccountManagerAccount = accountManagerAccount;
        final Cursor c = mContentResolver.query(CalendarContract.Calendars.CONTENT_URI,
                new String[] {CalendarContract.Calendars._ID}, CALENDAR_SELECTION,
                new String[] {mAccount.mEmailAddress, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE}, null);
        if (c == null) {
            mCalendarId = -1;
        } else {
            try {
                if (c.moveToFirst()) {
                    mCalendarId = c.getLong(CALENDAR_SELECTION_ID);
                } else {
                    mCalendarId = CalendarUtilities.createCalendar(mContext, mContentResolver,
                            mAccount, mMailbox);
                }
            } finally {
                c.close();
            }
        }
    }

    @Override
    protected int getTrafficFlag() {
        return TrafficFlags.DATA_CALENDAR;
    }

    private Uri asSyncAdapter(final Uri uri) {
        return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(
                        CalendarContract.Calendars.ACCOUNT_NAME, mAccount.mEmailAddress)
                .appendQueryParameter(
                        CalendarContract.Calendars.ACCOUNT_TYPE, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE)
                .build();
    }

    @Override
    protected String getSyncKey() {
        // mMailbox.mSyncKey is bogus since state is stored by the calendar provider, so we
        // need to fetch the data from there.
        // However, we need for that value to be reasonable, so we set it here once we fetch it.
        final ContentProviderClient client = mContentResolver.acquireContentProviderClient(
                CalendarContract.CONTENT_URI);
        try {
            final byte[] data = SyncStateContract.Helpers.get(client,
                    asSyncAdapter(CalendarContract.SyncState.CONTENT_URI), mAccountManagerAccount);
            if (data == null || data.length == 0) {
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
        return "Calendar";
    }


    @Override
    protected AbstractSyncParser getParser(final InputStream is) throws IOException {
        return new CalendarSyncAdapter.EasCalendarSyncParser(mContext, mContentResolver, is,
                mMailbox, mAccount, mAccountManagerAccount, mCalendarId);
    }

    @Override
    protected void setInitialSyncOptions(final Serializer s) throws IOException {
        // Nothing to do for Calendar.
    }

    @Override
    protected void setNonInitialSyncOptions(final Serializer s) throws IOException {
        setPimSyncOptions(s, Eas.FILTER_2_WEEKS);
    }

    @Override
    protected void setUpsyncCommands(final Serializer s) throws IOException {

    }

}
