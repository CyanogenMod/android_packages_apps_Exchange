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

package com.android.exchange.provider;

import android.accounts.AccountManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Contacts.Data;
import android.provider.ContactsContract.Directory;
import android.provider.ContactsContract.DisplayNameSources;
import android.provider.ContactsContract.RawContacts;
import android.text.TextUtils;

import com.android.emailcommon.Configuration;
import com.android.emailcommon.mail.PackedString;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.service.AccountServiceProxy;
import com.android.emailcommon.utility.Utility;
import com.android.exchange.Eas;
import com.android.exchange.EasSyncService;
import com.android.exchange.R;
import com.android.exchange.provider.GalResult.GalData;
import com.android.mail.utils.LogUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * ExchangeDirectoryProvider provides real-time data from the Exchange server; at the moment, it is
 * used solely to provide GAL (Global Address Lookup) service to email address adapters
 */
public class ExchangeDirectoryProvider extends ContentProvider {
    private static final String TAG = Eas.LOG_TAG;

    public static final String EXCHANGE_GAL_AUTHORITY =
            com.android.exchange.Configuration.EXCHANGE_GAL_AUTHORITY;

    private static final int DEFAULT_CONTACT_ID = 1;
    private static final int DEFAULT_LOOKUP_LIMIT = 20;

    private static final int GAL_BASE = 0;
    private static final int GAL_DIRECTORIES = GAL_BASE;
    private static final int GAL_FILTER = GAL_BASE + 1;
    private static final int GAL_CONTACT = GAL_BASE + 2;
    private static final int GAL_CONTACT_WITH_ID = GAL_BASE + 3;
    private static final int GAL_EMAIL_FILTER = GAL_BASE + 4;
    private static final int GAL_PHONE_FILTER = GAL_BASE + 5;

    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    /*package*/ final HashMap<String, Long> mAccountIdMap = new HashMap<String, Long>();

    static {
        sURIMatcher.addURI(EXCHANGE_GAL_AUTHORITY, "directories", GAL_DIRECTORIES);
        sURIMatcher.addURI(EXCHANGE_GAL_AUTHORITY, "contacts/filter/*", GAL_FILTER);
        sURIMatcher.addURI(EXCHANGE_GAL_AUTHORITY, "contacts/lookup/*/entities", GAL_CONTACT);
        sURIMatcher.addURI(EXCHANGE_GAL_AUTHORITY, "contacts/lookup/*/#/entities",
                GAL_CONTACT_WITH_ID);
        sURIMatcher.addURI(EXCHANGE_GAL_AUTHORITY, "data/emails/filter/*", GAL_EMAIL_FILTER);
        sURIMatcher.addURI(EXCHANGE_GAL_AUTHORITY, "data/phones/filter/*", GAL_PHONE_FILTER);

    }

    @Override
    public boolean onCreate() {
        EmailContent.init(getContext());
        return true;
    }

    static class GalProjection {
        final int size;
        final HashMap<String, Integer> columnMap = new HashMap<String, Integer>();

        GalProjection(String[] projection) {
            size = projection.length;
            for (int i = 0; i < projection.length; i++) {
                columnMap.put(projection[i], i);
            }
        }
    }

    static class GalContactRow {
        private final GalProjection mProjection;
        private Object[] row;
        static long dataId = 1;

        GalContactRow(GalProjection projection, long contactId, String accountName,
                String displayName) {
            this.mProjection = projection;
            row = new Object[projection.size];

            put(Contacts.Entity.CONTACT_ID, contactId);

            // We only have one raw contact per aggregate, so they can have the same ID
            put(Contacts.Entity.RAW_CONTACT_ID, contactId);
            put(Contacts.Entity.DATA_ID, dataId++);

            put(Contacts.DISPLAY_NAME, displayName);

            // TODO alternative display name
            put(Contacts.DISPLAY_NAME_ALTERNATIVE, displayName);

            put(RawContacts.ACCOUNT_TYPE, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE);
            put(RawContacts.ACCOUNT_NAME, accountName);
            put(RawContacts.RAW_CONTACT_IS_READ_ONLY, 1);
            put(Data.IS_READ_ONLY, 1);
        }

        Object[] getRow () {
            return row;
        }

        void put(String columnName, Object value) {
            final Integer integer = mProjection.columnMap.get(columnName);
            if (integer != null) {
                row[integer] = value;
            } else {
                LogUtils.e(TAG, "Unsupported column: " + columnName);
            }
        }

        static void addEmailAddress(MatrixCursor cursor, GalProjection galProjection,
                long contactId, String accountName, String displayName, String address) {
            if (!TextUtils.isEmpty(address)) {
                final GalContactRow r = new GalContactRow(
                        galProjection, contactId, accountName, displayName);
                r.put(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE);
                r.put(Email.TYPE, Email.TYPE_WORK);
                r.put(Email.ADDRESS, address);
                cursor.addRow(r.getRow());
            }
        }

        static void addPhoneRow(MatrixCursor cursor, GalProjection projection, long contactId,
                String accountName, String displayName, int type, String number) {
            if (!TextUtils.isEmpty(number)) {
                final GalContactRow r = new GalContactRow(
                        projection, contactId, accountName, displayName);
                r.put(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE);
                r.put(Phone.TYPE, type);
                r.put(Phone.NUMBER, number);
                cursor.addRow(r.getRow());
            }
        }

        public static void addNameRow(MatrixCursor cursor, GalProjection galProjection,
                long contactId, String accountName, String displayName,
                String firstName, String lastName) {
            final GalContactRow r = new GalContactRow(
                    galProjection, contactId, accountName, displayName);
            r.put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE);
            r.put(StructuredName.GIVEN_NAME, firstName);
            r.put(StructuredName.FAMILY_NAME, lastName);
            r.put(StructuredName.DISPLAY_NAME, displayName);
            cursor.addRow(r.getRow());
        }
    }

    /**
     * Find the record id of an Account, given its name (email address)
     * @param accountName the name of the account
     * @return the record id of the Account, or -1 if not found
     */
    /*package*/ long getAccountIdByName(Context context, String accountName) {
        Long accountId = mAccountIdMap.get(accountName);
        if (accountId == null) {
            accountId = Utility.getFirstRowLong(context, Account.CONTENT_URI,
                    EmailContent.ID_PROJECTION, AccountColumns.EMAIL_ADDRESS + "=?",
                    new String[] {accountName}, null, EmailContent.ID_PROJECTION_COLUMN , -1L);
            if (accountId != -1) {
                mAccountIdMap.put(accountName, accountId);
            }
        }
        return accountId;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        LogUtils.d(TAG, "ExchangeDirectoryProvider: query: %s", uri.toString());
        final int match = sURIMatcher.match(uri);
        final MatrixCursor cursor;
        Object[] row;
        final PackedString ps;
        final String lookupKey;

        switch (match) {
            case GAL_DIRECTORIES: {
                // Assuming that GAL can be used with all exchange accounts
                final android.accounts.Account[] accounts = AccountManager.get(getContext())
                        .getAccountsByType(Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE);
                cursor = new MatrixCursor(projection);
                if (accounts != null) {
                    for (android.accounts.Account account : accounts) {
                        row = new Object[projection.length];

                        for (int i = 0; i < projection.length; i++) {
                            final String column = projection[i];
                            if (column.equals(Directory.ACCOUNT_NAME)) {
                                row[i] = account.name;
                            } else if (column.equals(Directory.ACCOUNT_TYPE)) {
                                row[i] = account.type;
                            } else if (column.equals(Directory.TYPE_RESOURCE_ID)) {
                                final String accountType = Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE;
                                final Bundle bundle = new AccountServiceProxy(getContext())
                                    .getConfigurationData(accountType);
                                // Default to the alternative name, erring on the conservative side
                                int exchangeName = R.string.exchange_name_alternate;
                                if (bundle != null && !bundle.getBoolean(
                                        Configuration.EXCHANGE_CONFIGURATION_USE_ALTERNATE_STRINGS,
                                        true)) {
                                    exchangeName = R.string.exchange_name;
                                }
                                row[i] = exchangeName;
                            } else if (column.equals(Directory.DISPLAY_NAME)) {
                                // If the account name is an email address, extract
                                // the domain name and use it as the directory display name
                                final String accountName = account.name;
                                final int atIndex = accountName.indexOf('@');
                                if (atIndex != -1 && atIndex < accountName.length() - 2) {
                                    final char firstLetter = Character.toUpperCase(
                                            accountName.charAt(atIndex + 1));
                                    row[i] = firstLetter + accountName.substring(atIndex + 2);
                                } else {
                                    row[i] = account.name;
                                }
                            } else if (column.equals(Directory.EXPORT_SUPPORT)) {
                                row[i] = Directory.EXPORT_SUPPORT_SAME_ACCOUNT_ONLY;
                            } else if (column.equals(Directory.SHORTCUT_SUPPORT)) {
                                row[i] = Directory.SHORTCUT_SUPPORT_NONE;
                            }
                        }
                        cursor.addRow(row);
                    }
                }
                return cursor;
            }

            case GAL_FILTER:
            case GAL_PHONE_FILTER:
            case GAL_EMAIL_FILTER: {
                final String filter = uri.getLastPathSegment();
                // We should have at least two characters before doing a GAL search
                if (filter == null || filter.length() < 2) {
                    return null;
                }

                final String accountName = uri.getQueryParameter(RawContacts.ACCOUNT_NAME);
                if (accountName == null) {
                    return null;
                }

                // Enforce a limit on the number of lookup responses
                final String limitString = uri.getQueryParameter(ContactsContract.LIMIT_PARAM_KEY);
                int limit = DEFAULT_LOOKUP_LIMIT;
                if (limitString != null) {
                    try {
                        limit = Integer.parseInt(limitString);
                    } catch (NumberFormatException e) {
                        limit = 0;
                    }
                    if (limit <= 0) {
                        throw new IllegalArgumentException("Limit not valid: " + limitString);
                    }
                }

                final long callingId = Binder.clearCallingIdentity();
                try {
                    // Find the account id to pass along to EasSyncService
                    final long accountId = getAccountIdByName(getContext(), accountName);
                    if (accountId == -1) {
                        // The account was deleted?
                        return null;
                    }

                    // Get results from the Exchange account
                    final GalResult galResult = EasSyncService.searchGal(getContext(), accountId,
                            filter, limit);
                    if (galResult != null) {
                        return buildGalResultCursor(
                                projection, galResult, match == GAL_PHONE_FILTER, sortOrder);
                    }
                } finally {
                    Binder.restoreCallingIdentity(callingId);
                }
                break;
            }

            case GAL_CONTACT:
            case GAL_CONTACT_WITH_ID: {
                final String accountName = uri.getQueryParameter(RawContacts.ACCOUNT_NAME);
                if (accountName == null) {
                    return null;
                }

                final GalProjection galProjection = new GalProjection(projection);
                cursor = new MatrixCursor(projection);
                // Handle the decomposition of the key into rows suitable for CP2
                final List<String> pathSegments = uri.getPathSegments();
                lookupKey = pathSegments.get(2);
                final long contactId = (match == GAL_CONTACT_WITH_ID)
                        ? Long.parseLong(pathSegments.get(3))
                        : DEFAULT_CONTACT_ID;
                ps = new PackedString(lookupKey);
                final String displayName = ps.get(GalData.DISPLAY_NAME);
                GalContactRow.addEmailAddress(cursor, galProjection, contactId,
                        accountName, displayName, ps.get(GalData.EMAIL_ADDRESS));
                GalContactRow.addPhoneRow(cursor, galProjection, contactId,
                        displayName, displayName, Phone.TYPE_HOME, ps.get(GalData.HOME_PHONE));
                GalContactRow.addPhoneRow(cursor, galProjection, contactId,
                        displayName, displayName, Phone.TYPE_WORK, ps.get(GalData.WORK_PHONE));
                GalContactRow.addPhoneRow(cursor, galProjection, contactId,
                        displayName, displayName, Phone.TYPE_MOBILE, ps.get(GalData.MOBILE_PHONE));
                GalContactRow.addNameRow(cursor, galProjection, contactId, accountName,
                        displayName, ps.get(GalData.FIRST_NAME), ps.get(GalData.LAST_NAME));
                return cursor;
            }
        }

        return null;
    }

    /*package*/ Cursor buildGalResultCursor(String[] projection, GalResult galResult,
            boolean isPhoneFilter, String sortOrder) {
        int displayNameIndex = -1;
        int displayNameSourceIndex = -1;
        int alternateDisplayNameIndex = -1;
        int emailIndex = -1;
        int emailTypeIndex = -1;
        int phoneNumberIndex = -1;
        int phoneTypeIndex = -1;
        int hasPhoneNumberIndex = -1;
        int idIndex = -1;
        int contactIdIndex = -1;
        int lookupIndex = -1;

        for (int i = 0; i < projection.length; i++) {
            final String column = projection[i];
            if (Contacts.DISPLAY_NAME.equals(column) ||
                    Contacts.DISPLAY_NAME_PRIMARY.equals(column)) {
                displayNameIndex = i;
            } else if (Contacts.DISPLAY_NAME_ALTERNATIVE.equals(column)) {
                alternateDisplayNameIndex = i;
            } else if (Contacts.DISPLAY_NAME_SOURCE.equals(column)) {
                displayNameSourceIndex = i;
            } else if (Contacts.HAS_PHONE_NUMBER.equals(column)) {
                hasPhoneNumberIndex = i;
            } else if (Contacts._ID.equals(column)) {
                idIndex = i;
            } else if (Phone.CONTACT_ID.equals(column)) {
                contactIdIndex = i;
            } else if (Contacts.LOOKUP_KEY.equals(column)) {
                lookupIndex = i;
            } else if (isPhoneFilter) {
                if (Phone.NUMBER.equals(column)) {
                    phoneNumberIndex = i;
                } else if (Phone.TYPE.equals(column)) {
                    phoneTypeIndex = i;
                }
            } else {
                // Cannot support for Email and Phone in same query, so default
                // is to return email addresses.
                if (Email.ADDRESS.equals(column)) {
                    emailIndex = i;
                } else if (Email.TYPE.equals(column)) {
                    emailTypeIndex = i;
                }
            }
        }

        final boolean useAlternateSortKey = Contacts.SORT_KEY_ALTERNATIVE.equals(sortOrder);

        final TreeMap<GalSortKey, Object[]> sortedResultsMap =
                new TreeMap<GalSortKey, Object[]>(new NameComparator());

        // id populates the _ID column and is incremented for each row in the
        // result set, so each row has a unique id.
        int id = 1;
        // contactId populates the CONTACT_ID column and is incremented for
        // each contact. For the email and phone filters, there may be more
        // than one row with the same contactId if a given contact has multiple
        // email addresses or multiple phone numbers.
        int contactId = 1;

        final Object[] row = new Object[projection.length];
        final int count = galResult.galData.size();
        for (int i = 0; i < count; i++) {
            final GalData galDataRow = galResult.galData.get(i);
            final String firstName = galDataRow.get(GalData.FIRST_NAME);
            final String lastName = galDataRow.get(GalData.LAST_NAME);
            String displayName = galDataRow.get(GalData.DISPLAY_NAME);
            final List<PhoneInfo> phones = new ArrayList<PhoneInfo>();

            addPhoneInfo(phones, galDataRow.get(GalData.WORK_PHONE), Phone.TYPE_WORK);
            addPhoneInfo(phones, galDataRow.get(GalData.OFFICE), Phone.TYPE_COMPANY_MAIN);
            addPhoneInfo(phones, galDataRow.get(GalData.HOME_PHONE), Phone.TYPE_HOME);
            addPhoneInfo(phones, galDataRow.get(GalData.MOBILE_PHONE), Phone.TYPE_MOBILE);

            // If we don't have a display name, try to create one using first and last name
            if (displayName == null) {
                if (firstName != null && lastName != null) {
                    displayName = firstName + " " + lastName;
                } else if (firstName != null) {
                    displayName = firstName;
                } else if (lastName != null) {
                    displayName = lastName;
                }
            }
            galDataRow.put(GalData.DISPLAY_NAME, displayName);

            if (displayNameIndex != -1) {
                row[displayNameIndex] = displayName;
            }

            // Try to create an alternate display name, using first and last name
            // TODO: Check with Contacts team to make sure we're using this properly
            final String alternateDisplayName;
            if (firstName != null && lastName != null) {
                alternateDisplayName = lastName + " " + firstName;
            } else {
                alternateDisplayName = displayName;
            }

            if (alternateDisplayNameIndex != -1) {
                row[alternateDisplayNameIndex] = alternateDisplayName;
            }

            if (displayNameSourceIndex >= 0) {
                row[displayNameSourceIndex] = DisplayNameSources.STRUCTURED_NAME;
            }

            final String sortName = useAlternateSortKey ? alternateDisplayName : displayName;

            if (hasPhoneNumberIndex >= 0) {
                if (phones.size() > 0) {
                    row[hasPhoneNumberIndex] = true;
                }
            }

            if (contactIdIndex != -1) {
                row[contactIdIndex] = contactId;
            }

            if (lookupIndex != -1) {
                // We use the packed string as our lookup key; it contains ALL of the gal data
                // We do this because we are not able to provide a stable id to ContactsProvider
                row[lookupIndex] = Uri.encode(galDataRow.toPackedString());
            }

            if (isPhoneFilter) {
                final Set<String> uniqueNumbers = new HashSet<String>();

                for (PhoneInfo phone : phones) {
                    if (!uniqueNumbers.add(phone.mNumber)) {
                        continue;
                    }
                    if (phoneNumberIndex >= 0) {
                        row[phoneNumberIndex] = phone.mNumber;
                    }
                    if (phoneTypeIndex >= 0) {
                        row[phoneTypeIndex] = phone.mType;
                    }
                    if (idIndex != -1) {
                        row[idIndex] = id;
                    }
                    sortedResultsMap.put(new GalSortKey(sortName, id), row.clone());
                    id++;
                }

            } else {
                if (emailIndex != -1) {
                    row[emailIndex] = galDataRow.get(GalData.EMAIL_ADDRESS);
                }
                if (emailTypeIndex >= 0) {
                    row[emailTypeIndex] = Email.TYPE_WORK;
                }

                if (idIndex != -1) {
                    row[idIndex] = id;
                }
                sortedResultsMap.put(new GalSortKey(sortName, id), row.clone());
                id++;
            }
            contactId++;
        }
        final MatrixCursor cursor = new MatrixCursor(projection, sortedResultsMap.size());
        for(Object[] result : sortedResultsMap.values()) {
            cursor.addRow(result);
        }

        return cursor;
    }

    private void addPhoneInfo(List<PhoneInfo> phones, String number, int type) {
        if (!TextUtils.isEmpty(number)) {
            phones.add(new PhoneInfo(number, type));
        }
    }

    @Override
    public String getType(Uri uri) {
        final int match = sURIMatcher.match(uri);
        switch (match) {
            case GAL_FILTER:
                return Contacts.CONTENT_ITEM_TYPE;
        }
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sort key for Gal filter results.
     *  - primary key is name
     *      for SORT_KEY_PRIMARY, this is displayName
     *      for SORT_KEY_ALTERNATIVE, this is alternativeDisplayName
     *      if no sort order is specified, this key is empty
     *  - secondary key is id, so ordering of the original results are
     *      preserved both between contacts with the same name and for
     *      multiple results within a given contact
     */
    private static class GalSortKey {
        final String sortName;
        final int    id;

        public GalSortKey(String sortName, int id) {
            this.sortName = sortName;
            this.id = id;
        }
    }

    private static class NameComparator implements Comparator<GalSortKey> {
        private final Collator collator;

        public NameComparator() {
            collator = Collator.getInstance();
            // Case insensitive sorting
            collator.setStrength(Collator.SECONDARY);
        }

        @Override
        public int compare(GalSortKey lhs, GalSortKey rhs) {
            final int res = collator.compare(lhs.sortName, rhs.sortName);
            if (res != 0) {
                return res;
            }
            if (lhs.id != rhs.id) {
                return lhs.id > rhs.id ? 1 : -1;
            }
            return 0;
        }
    }

    private static class PhoneInfo {
        private String mNumber;
        private int mType;

        private PhoneInfo(String number, int type) {
            mNumber = number;
            mType = type;
        }
    }
}
