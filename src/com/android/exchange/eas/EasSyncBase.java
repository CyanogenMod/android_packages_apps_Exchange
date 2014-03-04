package com.android.exchange.eas;

import android.content.Context;
import android.text.format.DateUtils;
import android.util.SparseArray;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.Mailbox;
import com.android.exchange.CommandStatusException;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;
import com.android.exchange.adapter.AbstractSyncParser;
import com.android.exchange.adapter.Parser;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;
import com.android.mail.utils.LogUtils;

import org.apache.http.HttpEntity;

import java.io.IOException;

/**
 * Performs an EAS sync operation for one folder (excluding mail upsync).
 * TODO: Merge with {@link EasSync}, which currently handles mail upsync.
 */
public class EasSyncBase extends EasOperation {

    private static final String TAG = Eas.LOG_TAG;

    public static final int RESULT_DONE = 0;
    public static final int RESULT_MORE_AVAILABLE = 1;

    private final boolean mInitialSync;
    private final Mailbox mMailbox;

    private int mNumWindows;

    /**
     * {@link EasSyncCollectionTypeBase} classes currently are stateless, so there's no need to
     * create one per operation instance. We just maintain a single instance of each in a map and
     * grab it as necessary.
     */
    private static final SparseArray<EasSyncCollectionTypeBase> COLLECTION_TYPE_HANDLERS;
    static {
        COLLECTION_TYPE_HANDLERS = new SparseArray<EasSyncCollectionTypeBase>(3);
        // TODO: As the subclasses are created, add them to the map.
        COLLECTION_TYPE_HANDLERS.put(Mailbox.TYPE_MAIL, new EasSyncMail());
        COLLECTION_TYPE_HANDLERS.put(Mailbox.TYPE_CALENDAR, null);
        COLLECTION_TYPE_HANDLERS.put(Mailbox.TYPE_CONTACTS, null);
    }

    // TODO: Convert to accountId when ready to convert to EasService.
    public EasSyncBase(final Context context, final Account account, final Mailbox mailbox) {
        super(context, account);
        // TODO: This works for email, but not necessarily for other types.
        mInitialSync = EmailContent.isInitialSyncKey(getSyncKey());
        mMailbox = mailbox;
    }

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

    @Override
    protected String getCommand() {
        return "Sync";
    }

    @Override
    protected HttpEntity getRequestEntity() throws IOException {
        final String className = Eas.getFolderClass(mMailbox.mType);
        final String syncKey = getSyncKey();
        LogUtils.d(TAG, "Syncing account %d mailbox %d (class %s) with syncKey %s", mAccount.mId,
                mMailbox.mId, className, syncKey);

        final EasSyncCollectionTypeBase collectionTypeHandler =
                getCollectionTypeHandler(mMailbox.mType);
        if (collectionTypeHandler == null) {
            throw new IllegalStateException("No handler for collection type: "
                    + Integer.toString(mMailbox.mType));
        }

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
        collectionTypeHandler.setSyncOptions(mContext, s, getProtocolVersion(), mAccount, mMailbox,
                mInitialSync, mNumWindows);
        s.end().end().end().done();

        return makeEntity(s);
    }

    @Override
    protected int handleResponse(final EasResponse response)
            throws IOException, CommandStatusException {
        try {
            final AbstractSyncParser parser =
                    getCollectionTypeHandler(mMailbox.mType).getParser(mContext, mAccount, mMailbox,
                            response.getInputStream());
            final boolean moreAvailable = parser.parse();
            if (moreAvailable) {
                return RESULT_MORE_AVAILABLE;
            }
        } catch (final Parser.EmptyStreamException e) {
            // This indicates a compressed response which was empty, which is OK.
        }
        return RESULT_DONE;
    }

    @Override
    public int performOperation() {
        int result = RESULT_MORE_AVAILABLE;
        mNumWindows = 1;
        String key = getSyncKey();
        while (result == RESULT_MORE_AVAILABLE) {
            result = super.performOperation();
            // TODO: Clear pending request queue.
            final String newKey = getSyncKey();
            if (result == RESULT_MORE_AVAILABLE && key.equals(newKey)) {
                LogUtils.e(TAG,
                        "Server has more data but we have the same key: %s numWindows: %d",
                        key, mNumWindows);
                mNumWindows++;
            } else {
                mNumWindows = 1;
            }
        }
        return result;
    }

    @Override
    protected long getTimeout() {
        if (mInitialSync) {
            return 120 * DateUtils.SECOND_IN_MILLIS;
        }
        return super.getTimeout();
    }

    /**
     * Get an instance of the correct {@link EasSyncCollectionTypeBase} for a specific collection
     * type.
     * @param type The type of the {@link Mailbox} that we're trying to sync.
     * @return An {@link EasSyncCollectionTypeBase} appropriate for this type.
     */
    private static EasSyncCollectionTypeBase getCollectionTypeHandler(final int type) {
        EasSyncCollectionTypeBase typeHandler = COLLECTION_TYPE_HANDLERS.get(type);
        if (typeHandler == null) {
            // Treat mail as the default; this also means we don't bother mapping every type of
            // mail folder.
            // TODO: Should we just do that?
            typeHandler = COLLECTION_TYPE_HANDLERS.get(Mailbox.TYPE_MAIL);
        }
        return typeHandler;
    }
}
