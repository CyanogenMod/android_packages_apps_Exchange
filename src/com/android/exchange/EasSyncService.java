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

package com.android.exchange;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Entity;
import android.database.Cursor;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Events;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.Xml;

import com.android.emailcommon.TrafficFlags;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.mail.MeetingInfo;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.mail.PackedString;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.HostAuthColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.provider.ProviderUnavailableException;
import com.android.emailcommon.service.EmailServiceConstants;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.utility.EmailClientConnectionManager;
import com.android.emailcommon.utility.Utility;
import com.android.exchange.CommandStatusException.CommandStatus;
import com.android.exchange.adapter.AbstractSyncAdapter;
import com.android.exchange.adapter.AccountSyncAdapter;
import com.android.exchange.adapter.AttachmentLoader;
import com.android.exchange.adapter.CalendarSyncAdapter;
import com.android.exchange.adapter.ContactsSyncAdapter;
import com.android.exchange.adapter.EmailSyncAdapter;
import com.android.exchange.adapter.FolderSyncParser;
import com.android.exchange.adapter.GalParser;
import com.android.exchange.adapter.MeetingResponseParser;
import com.android.exchange.adapter.MoveItemsParser;
import com.android.exchange.adapter.Parser.EasParserException;
import com.android.exchange.adapter.Parser.EmptyStreamException;
import com.android.exchange.adapter.PingParser;
import com.android.exchange.adapter.ProvisionParser;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.SettingsParser;
import com.android.exchange.adapter.Tags;
import com.android.exchange.provider.GalResult;
import com.android.exchange.provider.MailboxUtilities;
import com.android.exchange.utility.CalendarUtilities;
import com.google.common.annotations.VisibleForTesting;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.Thread.State;
import java.net.URI;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashMap;

public class EasSyncService extends AbstractSyncService {
    // DO NOT CHECK IN SET TO TRUE
    public static final boolean DEBUG_GAL_SERVICE = false;

    private static final String WHERE_ACCOUNT_KEY_AND_SERVER_ID =
        MailboxColumns.ACCOUNT_KEY + "=? and " + MailboxColumns.SERVER_ID + "=?";
    private static final String WHERE_ACCOUNT_AND_SYNC_INTERVAL_PING =
        MailboxColumns.ACCOUNT_KEY + "=? and " + MailboxColumns.SYNC_INTERVAL +
        '=' + Mailbox.CHECK_INTERVAL_PING;
    private static final String AND_FREQUENCY_PING_PUSH_AND_NOT_ACCOUNT_MAILBOX = " AND " +
        MailboxColumns.SYNC_INTERVAL + " IN (" + Mailbox.CHECK_INTERVAL_PING +
        ',' + Mailbox.CHECK_INTERVAL_PUSH + ") AND " + MailboxColumns.TYPE + "!=\"" +
        Mailbox.TYPE_EAS_ACCOUNT_MAILBOX + '\"';
    private static final String WHERE_PUSH_HOLD_NOT_ACCOUNT_MAILBOX =
        MailboxColumns.ACCOUNT_KEY + "=? and " + MailboxColumns.SYNC_INTERVAL +
        '=' + Mailbox.CHECK_INTERVAL_PUSH_HOLD;

    static private final String PING_COMMAND = "Ping";
    // Command timeout is the the time allowed for reading data from an open connection before an
    // IOException is thrown.  After a small added allowance, our watchdog alarm goes off (allowing
    // us to detect a silently dropped connection).  The allowance is defined below.
    static public final int COMMAND_TIMEOUT = 30*SECONDS;
    // Connection timeout is the time given to connect to the server before reporting an IOException
    static private final int CONNECTION_TIMEOUT = 20*SECONDS;
    // The extra time allowed beyond the COMMAND_TIMEOUT before which our watchdog alarm triggers
    static private final int WATCHDOG_TIMEOUT_ALLOWANCE = 30*SECONDS;

    // The amount of time the account mailbox will sleep if there are no pingable mailboxes
    // This could happen if the sync time is set to "never"; we always want to check in from time
    // to time, however, for folder list/policy changes
    static private final int ACCOUNT_MAILBOX_SLEEP_TIME = 20*MINUTES;
    static private final String ACCOUNT_MAILBOX_SLEEP_TEXT =
        "Account mailbox sleeping for " + (ACCOUNT_MAILBOX_SLEEP_TIME / MINUTES) + "m";

    static private final String AUTO_DISCOVER_SCHEMA_PREFIX =
        "http://schemas.microsoft.com/exchange/autodiscover/mobilesync/";
    static private final String AUTO_DISCOVER_PAGE = "/autodiscover/autodiscover.xml";
    static private final int EAS_REDIRECT_CODE = 451;

    static public final int INTERNAL_SERVER_ERROR_CODE = 500;

    static public final String EAS_12_POLICY_TYPE = "MS-EAS-Provisioning-WBXML";
    static public final String EAS_2_POLICY_TYPE = "MS-WAP-Provisioning-XML";

    static public final int MESSAGE_FLAG_MOVED_MESSAGE = 1 << Message.FLAG_SYNC_ADAPTER_SHIFT;

    /**
     * We start with an 8 minute timeout, and increase/decrease by 3 minutes at a time.  There's
     * no point having a timeout shorter than 5 minutes, I think; at that point, we can just let
     * the ping exception out.  The maximum I use is 17 minutes, which is really an empirical
     * choice; too long and we risk silent connection loss and loss of push for that period.  Too
     * short and we lose efficiency/battery life.
     *
     * If we ever have to drop the ping timeout, we'll never increase it again.  There's no point
     * going into hysteresis; the NAT timeout isn't going to change without a change in connection,
     * which will cause the sync service to be restarted at the starting heartbeat and going through
     * the process again.
     */
    static private final int PING_MINUTES = 60; // in seconds
    static private final int PING_FUDGE_LOW = 10;
    static private final int PING_STARTING_HEARTBEAT = (8*PING_MINUTES)-PING_FUDGE_LOW;
    static private final int PING_HEARTBEAT_INCREMENT = 3*PING_MINUTES;

    // Maximum number of times we'll allow a sync to "loop" with MoreAvailable true before
    // forcing it to stop.  This number has been determined empirically.
    static private final int MAX_LOOPING_COUNT = 100;

    static private final int PROTOCOL_PING_STATUS_COMPLETED = 1;

    // The amount of time we allow for a thread to release its post lock after receiving an alert
    static private final int POST_LOCK_TIMEOUT = 10*SECONDS;

    // Fallbacks (in minutes) for ping loop failures
    static private final int MAX_PING_FAILURES = 1;
    static private final int PING_FALLBACK_INBOX = 5;
    static private final int PING_FALLBACK_PIM = 25;

    // The EAS protocol Provision status for "we implement all of the policies"
    static private final String PROVISION_STATUS_OK = "1";
    // The EAS protocol Provision status meaning "we partially implement the policies"
    static private final String PROVISION_STATUS_PARTIAL = "2";

    static /*package*/ final String DEVICE_TYPE = "Android";
    static private final String USER_AGENT = DEVICE_TYPE + '/' + Build.VERSION.RELEASE + '-' +
        Eas.CLIENT_VERSION;

    // Reasonable default
    public String mProtocolVersion = Eas.DEFAULT_PROTOCOL_VERSION;
    public Double mProtocolVersionDouble;
    protected String mDeviceId = null;
    @VisibleForTesting
    String mAuthString = null;
    @VisibleForTesting
    String mUserString = null;
    @VisibleForTesting
    String mBaseUriString = null;
    public String mHostAddress;
    public String mUserName;
    public String mPassword;

    // The parameters for the connection must be modified through setConnectionParameters
    private boolean mSsl = true;
    private boolean mTrustSsl = false;
    private String mClientCertAlias = null;

    public ContentResolver mContentResolver;
    private final String[] mBindArguments = new String[2];
    private ArrayList<String> mPingChangeList;
    // The HttpPost in progress
    private volatile HttpPost mPendingPost = null;
    // Our heartbeat when we are waiting for ping boxes to be ready
    /*package*/ int mPingForceHeartbeat = 2*PING_MINUTES;
    // The minimum heartbeat we will send
    /*package*/ int mPingMinHeartbeat = (5*PING_MINUTES)-PING_FUDGE_LOW;
    // The maximum heartbeat we will send
    /*package*/ int mPingMaxHeartbeat = (30*PING_MINUTES)-PING_FUDGE_LOW;
    // The ping time (in seconds)
    /*package*/ int mPingHeartbeat = PING_STARTING_HEARTBEAT;
    // The longest successful ping heartbeat
    private int mPingHighWaterMark = 0;
    // Whether we've ever lowered the heartbeat
    /*package*/ boolean mPingHeartbeatDropped = false;
    // Whether a POST was aborted due to alarm (watchdog alarm)
    private boolean mPostAborted = false;
    // Whether a POST was aborted due to reset
    private boolean mPostReset = false;
    // Whether or not the sync service is valid (usable)
    public boolean mIsValid = true;

    // Whether the most recent upsync failed (status 7)
    public boolean mUpsyncFailed = false;

    public EasSyncService(Context _context, Mailbox _mailbox) {
        super(_context, _mailbox);
        mContentResolver = _context.getContentResolver();
        if (mAccount == null) {
            mIsValid = false;
            return;
        }
        HostAuth ha = HostAuth.restoreHostAuthWithId(_context, mAccount.mHostAuthKeyRecv);
        if (ha == null) {
            mIsValid = false;
            return;
        }
        mSsl = (ha.mFlags & HostAuth.FLAG_SSL) != 0;
        mTrustSsl = (ha.mFlags & HostAuth.FLAG_TRUST_ALL) != 0;
    }

    private EasSyncService(String prefix) {
        super(prefix);
    }

    public EasSyncService() {
        this("EAS Validation");
    }

    /**
     * Try to wake up a sync thread that is waiting on an HttpClient POST and has waited past its
     * socket timeout without having thrown an Exception
     *
     * @return true if the POST was successfully stopped; false if we've failed and interrupted
     * the thread
     */
    @Override
    public boolean alarm() {
        HttpPost post;
        if (mThread == null) return true;
        String threadName = mThread.getName();

        // Synchronize here so that we are guaranteed to have valid mPendingPost and mPostLock
        // executePostWithTimeout (which executes the HttpPost) also uses this lock
        synchronized(getSynchronizer()) {
            // Get a reference to the current post lock
            post = mPendingPost;
            if (post != null) {
                if (Eas.USER_LOG) {
                    URI uri = post.getURI();
                    if (uri != null) {
                        String query = uri.getQuery();
                        if (query == null) {
                            query = "POST";
                        }
                        userLog(threadName, ": Alert, aborting ", query);
                    } else {
                        userLog(threadName, ": Alert, no URI?");
                    }
                }
                // Abort the POST
                mPostAborted = true;
                post.abort();
            } else {
                // If there's no POST, we're done
                userLog("Alert, no pending POST");
                return true;
            }
        }

        // Wait for the POST to finish
        try {
            Thread.sleep(POST_LOCK_TIMEOUT);
        } catch (InterruptedException e) {
        }

        State s = mThread.getState();
        if (Eas.USER_LOG) {
            userLog(threadName + ": State = " + s.name());
        }

        synchronized (getSynchronizer()) {
            // If the thread is still hanging around and the same post is pending, let's try to
            // stop the thread with an interrupt.
            if ((s != State.TERMINATED) && (mPendingPost != null) && (mPendingPost == post)) {
                mStop = true;
                mThread.interrupt();
                userLog("Interrupting...");
                // Let the caller know we had to interrupt the thread
                return false;
            }
        }
        // Let the caller know that the alarm was handled normally
        return true;
    }

    @Override
    public void reset() {
        synchronized(getSynchronizer()) {
            if (mPendingPost != null) {
                URI uri = mPendingPost.getURI();
                if (uri != null) {
                    String query = uri.getQuery();
                    if (query.startsWith("Cmd=Ping")) {
                        userLog("Reset, aborting Ping");
                        mPostReset = true;
                        mPendingPost.abort();
                    }
                }
            }
        }
    }

    @Override
    public void stop() {
        mStop = true;
        synchronized(getSynchronizer()) {
            if (mPendingPost != null) {
                mPendingPost.abort();
            }
        }
    }

    @Override
    public void addRequest(Request request) {
        // Don't allow duplicates of requests; just refuse them
        if (mRequestQueue.contains(request)) return;
        // Add the request
        super.addRequest(request);
    }

    private void setupProtocolVersion(EasSyncService service, Header versionHeader)
            throws MessagingException {
        // The string is a comma separated list of EAS versions in ascending order
        // e.g. 1.0,2.0,2.5,12.0,12.1,14.0,14.1
        String supportedVersions = versionHeader.getValue();
        userLog("Server supports versions: ", supportedVersions);
        String[] supportedVersionsArray = supportedVersions.split(",");
        String ourVersion = null;
        // Find the most recent version we support
        for (String version: supportedVersionsArray) {
            if (version.equals(Eas.SUPPORTED_PROTOCOL_EX2003) ||
                    version.equals(Eas.SUPPORTED_PROTOCOL_EX2007) ||
                    version.equals(Eas.SUPPORTED_PROTOCOL_EX2007_SP1) ||
                    version.equals(Eas.SUPPORTED_PROTOCOL_EX2010) ||
                    version.equals(Eas.SUPPORTED_PROTOCOL_EX2010_SP1)) {
                ourVersion = version;
            }
        }
        // If we don't support any of the servers supported versions, throw an exception here
        // This will cause validation to fail
        if (ourVersion == null) {
            Log.w(TAG, "No supported EAS versions: " + supportedVersions);
            throw new MessagingException(MessagingException.PROTOCOL_VERSION_UNSUPPORTED);
        } else {
            // Debug code for testing EAS 14.0; disables support for EAS 14.1
            // "adb shell setprop log.tag.Exchange14 VERBOSE"
            if (ourVersion.equals(Eas.SUPPORTED_PROTOCOL_EX2010_SP1) &&
                    Log.isLoggable("Exchange14", Log.VERBOSE)) {
                ourVersion = Eas.SUPPORTED_PROTOCOL_EX2010;
            }
            service.mProtocolVersion = ourVersion;
            service.mProtocolVersionDouble = Eas.getProtocolVersionDouble(ourVersion);
            Account account = service.mAccount;
            if (account != null) {
                account.mProtocolVersion = ourVersion;
                // Fixup search flags, if they're not set
                if (service.mProtocolVersionDouble >= 12.0 &&
                        (account.mFlags & Account.FLAGS_SUPPORTS_SEARCH) == 0) {
                    if (account.isSaved()) {
                        ContentValues cv = new ContentValues();
                        account.mFlags |=
                            Account.FLAGS_SUPPORTS_GLOBAL_SEARCH + Account.FLAGS_SUPPORTS_SEARCH;
                        cv.put(AccountColumns.FLAGS, account.mFlags);
                        account.update(service.mContext, cv);
                    }
                }
            }
        }
    }

    /**
     * Create an EasSyncService for the specified account
     *
     * @param context the caller's context
     * @param account the account
     * @return the service, or null if the account is on hold or hasn't been initialized
     */
    public static EasSyncService setupServiceForAccount(Context context, Account account) {
        // Just return null if we're on security hold
        if ((account.mFlags & Account.FLAGS_SECURITY_HOLD) != 0) {
            return null;
        }
        // If there's no protocol version, we're not initialized
        String protocolVersion = account.mProtocolVersion;
        if (protocolVersion == null) {
            return null;
        }
        EasSyncService svc = new EasSyncService("OutOfBand");
        HostAuth ha = HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv);
        svc.mProtocolVersion = protocolVersion;
        svc.mProtocolVersionDouble = Eas.getProtocolVersionDouble(protocolVersion);
        svc.mContext = context;
        svc.mHostAddress = ha.mAddress;
        svc.mUserName = ha.mLogin;
        svc.mPassword = ha.mPassword;
        try {
            svc.setConnectionParameters(
                    (ha.mFlags & HostAuth.FLAG_SSL) != 0,
                    (ha.mFlags & HostAuth.FLAG_TRUST_ALL) != 0,
                    ha.mClientCertAlias);
            svc.mDeviceId = ExchangeService.getDeviceId(context);
        } catch (IOException e) {
            return null;
        } catch (CertificateException e) {
            return null;
        }
        svc.mAccount = account;
        return svc;
    }

    /**
     * Get a redirect address and validate against it
     * @param resp the EasResponse to our POST
     * @param hostAuth the HostAuth we're using to validate
     * @return true if we have an updated HostAuth (with redirect address); false otherwise
     */
    private boolean getValidateRedirect(EasResponse resp, HostAuth hostAuth) {
        Header locHeader = resp.getHeader("X-MS-Location");
        if (locHeader != null) {
            String loc;
            try {
                loc = locHeader.getValue();
                // Reset our host address and uncache our base uri
                mHostAddress = Uri.parse(loc).getHost();
                mBaseUriString = null;
                hostAuth.mAddress = mHostAddress;
                userLog("Redirecting to: " + loc);
                return true;
            } catch (RuntimeException e) {
                // Just don't crash if the Uri is illegal
            }
        }
        return false;
    }

    private static final int MAX_REDIRECTS = 3;
    private int mRedirectCount = 0;

    @Override
    public Bundle validateAccount(HostAuth hostAuth, Context context) {
        Bundle bundle = new Bundle();
        int resultCode = MessagingException.NO_ERROR;
        try {
            userLog("Testing EAS: ", hostAuth.mAddress, ", ", hostAuth.mLogin,
                    ", ssl = ", hostAuth.shouldUseSsl() ? "1" : "0");
            mContext = context;
            mHostAddress = hostAuth.mAddress;
            mUserName = hostAuth.mLogin;
            mPassword = hostAuth.mPassword;

            setConnectionParameters(
                    hostAuth.shouldUseSsl(),
                    hostAuth.shouldTrustAllServerCerts(),
                    hostAuth.mClientCertAlias);
            mDeviceId = ExchangeService.getDeviceId(context);
            mAccount = new Account();
            mAccount.mEmailAddress = hostAuth.mLogin;
            EasResponse resp = sendHttpClientOptions();
            try {
                int code = resp.getStatus();
                userLog("Validation (OPTIONS) response: " + code);
                if (code == HttpStatus.SC_OK) {
                    // No exception means successful validation
                    Header commands = resp.getHeader("MS-ASProtocolCommands");
                    Header versions = resp.getHeader("ms-asprotocolversions");
                    // Make sure we've got the right protocol version set up
                    try {
                        if (commands == null || versions == null) {
                            userLog("OPTIONS response without commands or versions");
                            // We'll treat this as a protocol exception
                            throw new MessagingException(0);
                        }
                        setupProtocolVersion(this, versions);
                    } catch (MessagingException e) {
                        bundle.putInt(EmailServiceProxy.VALIDATE_BUNDLE_RESULT_CODE,
                                MessagingException.PROTOCOL_VERSION_UNSUPPORTED);
                        return bundle;
                    }

                    // Run second test here for provisioning failures using FolderSync
                    userLog("Try folder sync");
                    // Send "0" as the sync key for new accounts; otherwise, use the current key
                    String syncKey = "0";
                    Account existingAccount = Utility.findExistingAccount(
                            context, -1L, hostAuth.mAddress, hostAuth.mLogin);
                    if (existingAccount != null && existingAccount.mSyncKey != null) {
                        syncKey = existingAccount.mSyncKey;
                    }
                    Serializer s = new Serializer();
                    s.start(Tags.FOLDER_FOLDER_SYNC).start(Tags.FOLDER_SYNC_KEY).text(syncKey)
                        .end().end().done();
                    resp = sendHttpClientPost("FolderSync", s.toByteArray());
                    code = resp.getStatus();
                    // Handle HTTP error responses accordingly
                    if (code == HttpStatus.SC_FORBIDDEN) {
                        // For validation only, we take 403 as ACCESS_DENIED (the account isn't
                        // authorized, possibly due to device type)
                        resultCode = MessagingException.ACCESS_DENIED;
                    } else if (EasResponse.isProvisionError(code)) {
                        // The device needs to have security policies enforced
                        throw new CommandStatusException(CommandStatus.NEEDS_PROVISIONING);
                    } else if (code == HttpStatus.SC_NOT_FOUND) {
                        // We get a 404 from OWA addresses (which are NOT EAS addresses)
                        resultCode = MessagingException.PROTOCOL_VERSION_UNSUPPORTED;
                    } else if (code == HttpStatus.SC_UNAUTHORIZED) {
                        resultCode = resp.isMissingCertificate()
                                ? MessagingException.CLIENT_CERTIFICATE_REQUIRED
                                : MessagingException.AUTHENTICATION_FAILED;
                    } else if (code != HttpStatus.SC_OK) {
                        if ((code == EAS_REDIRECT_CODE) && (mRedirectCount++ < MAX_REDIRECTS) &&
                                getValidateRedirect(resp, hostAuth)) {
                            return validateAccount(hostAuth, context);
                        }
                        // Fail generically with anything other than success
                        userLog("Unexpected response for FolderSync: ", code);
                        resultCode = MessagingException.UNSPECIFIED_EXCEPTION;
                    } else {
                        // We need to parse the result to see if we've got a provisioning issue
                        // (EAS 14.0 only)
                        if (!resp.isEmpty()) {
                            InputStream is = resp.getInputStream();
                            // Create the parser with statusOnly set to true; we only care about
                            // seeing if a CommandStatusException is thrown (indicating a
                            // provisioning failure)
                            new FolderSyncParser(is, new AccountSyncAdapter(this), true).parse();
                        }
                        userLog("Validation successful");
                    }
                } else if (EasResponse.isAuthError(code)) {
                    userLog("Authentication failed");
                    resultCode = resp.isMissingCertificate()
                            ? MessagingException.CLIENT_CERTIFICATE_REQUIRED
                            : MessagingException.AUTHENTICATION_FAILED;
                } else if (code == INTERNAL_SERVER_ERROR_CODE) {
                    // For Exchange 2003, this could mean an authentication failure OR server error
                    userLog("Internal server error");
                    resultCode = MessagingException.AUTHENTICATION_FAILED_OR_SERVER_ERROR;
                } else {
                    if ((code == EAS_REDIRECT_CODE) && (mRedirectCount++ < MAX_REDIRECTS) &&
                            getValidateRedirect(resp, hostAuth)) {
                        return validateAccount(hostAuth, context);
                    }
                    // TODO Need to catch other kinds of errors (e.g. policy) For now, report code.
                    userLog("Validation failed, reporting I/O error: ", code);
                    resultCode = MessagingException.IOERROR;
                }
            } catch (CommandStatusException e) {
                int status = e.mStatus;
                if (CommandStatus.isNeedsProvisioning(status)) {
                    // Get the policies and see if we are able to support them
                    ProvisionParser pp = canProvision();
                    if (pp != null && pp.hasSupportablePolicySet()) {
                        // Set the proper result code and save the PolicySet in our Bundle
                        resultCode = MessagingException.SECURITY_POLICIES_REQUIRED;
                        bundle.putParcelable(EmailServiceProxy.VALIDATE_BUNDLE_POLICY_SET,
                                pp.getPolicy());
                        if (mProtocolVersionDouble == Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE) {
                            mAccount.mSecuritySyncKey = pp.getSecuritySyncKey();
                            if (!sendSettings()) {
                                userLog("Denied access: ", CommandStatus.toString(status));
                                resultCode = MessagingException.ACCESS_DENIED;
                            }
                        }
                    } else
                        // If not, set the proper code (the account will not be created)
                        resultCode = MessagingException.SECURITY_POLICIES_UNSUPPORTED;
                        bundle.putStringArray(
                                EmailServiceProxy.VALIDATE_BUNDLE_UNSUPPORTED_POLICIES,
                                ((pp == null) ? null : pp.getUnsupportedPolicies()));
                } else if (CommandStatus.isDeniedAccess(status)) {
                    userLog("Denied access: ", CommandStatus.toString(status));
                    resultCode = MessagingException.ACCESS_DENIED;
                } else if (CommandStatus.isTransientError(status)) {
                    userLog("Transient error: ", CommandStatus.toString(status));
                    resultCode = MessagingException.IOERROR;
                } else {
                    userLog("Unexpected response: ", CommandStatus.toString(status));
                    resultCode = MessagingException.UNSPECIFIED_EXCEPTION;
                }
            } finally {
                resp.close();
           }
        } catch (IOException e) {
            Throwable cause = e.getCause();
            if (cause != null && cause instanceof CertificateException) {
                // This could be because the server's certificate failed to validate.
                userLog("CertificateException caught: ", e.getMessage());
                resultCode = MessagingException.GENERAL_SECURITY;
            }
            userLog("IOException caught: ", e.getMessage());
            resultCode = MessagingException.IOERROR;
        } catch (CertificateException e) {
            // This occurs if the client certificate the user specified is invalid/inaccessible.
            userLog("CertificateException caught: ", e.getMessage());
            resultCode = MessagingException.CLIENT_CERTIFICATE_ERROR;
        }
        bundle.putInt(EmailServiceProxy.VALIDATE_BUNDLE_RESULT_CODE, resultCode);
        return bundle;
    }

    /**
     * Gets the redirect location from the HTTP headers and uses that to modify the HttpPost so that
     * it can be reused
     *
     * @param resp the HttpResponse that indicates a redirect (451)
     * @param post the HttpPost that was originally sent to the server
     * @return the HttpPost, updated with the redirect location
     */
    private HttpPost getRedirect(HttpResponse resp, HttpPost post) {
        Header locHeader = resp.getFirstHeader("X-MS-Location");
        if (locHeader != null) {
            String loc = locHeader.getValue();
            // If we've gotten one and it shows signs of looking like an address, we try
            // sending our request there
            if (loc != null && loc.startsWith("http")) {
                post.setURI(URI.create(loc));
                return post;
            }
        }
        return null;
    }

    /**
     * Send the POST command to the autodiscover server, handling a redirect, if necessary, and
     * return the HttpResponse.  If we get a 401 (unauthorized) error and we're using the
     * full email address, try the bare user name instead (e.g. foo instead of foo@bar.com)
     *
     * @param client the HttpClient to be used for the request
     * @param post the HttpPost we're going to send
     * @param canRetry whether we can retry using the bare name on an authentication failure (401)
     * @return an HttpResponse from the original or redirect server
     * @throws IOException on any IOException within the HttpClient code
     * @throws MessagingException
     */
    private EasResponse postAutodiscover(HttpClient client, HttpPost post, boolean canRetry)
            throws IOException, MessagingException {
        userLog("Posting autodiscover to: " + post.getURI());
        EasResponse resp = executePostWithTimeout(client, post, COMMAND_TIMEOUT);
        int code = resp.getStatus();
        // On a redirect, try the new location
        if (code == EAS_REDIRECT_CODE) {
            post = getRedirect(resp.mResponse, post);
            if (post != null) {
                userLog("Posting autodiscover to redirect: " + post.getURI());
                return executePostWithTimeout(client, post, COMMAND_TIMEOUT);
            }
        // 401 (Unauthorized) is for true auth errors when used in Autodiscover
        } else if (code == HttpStatus.SC_UNAUTHORIZED) {
            if (canRetry && mUserName.contains("@")) {
                // Try again using the bare user name
                int atSignIndex = mUserName.indexOf('@');
                mUserName = mUserName.substring(0, atSignIndex);
                cacheAuthUserAndBaseUriStrings();
                userLog("401 received; trying username: ", mUserName);
                // Recreate the basic authentication string and reset the header
                post.removeHeaders("Authorization");
                post.setHeader("Authorization", mAuthString);
                return postAutodiscover(client, post, false);
            }
            throw new MessagingException(MessagingException.AUTHENTICATION_FAILED);
        // 403 (and others) we'll just punt on
        } else if (code != HttpStatus.SC_OK) {
            // We'll try the next address if this doesn't work
            userLog("Code: " + code + ", throwing IOException");
            throw new IOException();
        }
        return resp;
    }

    /**
     * Use the Exchange 2007 AutoDiscover feature to try to retrieve server information using
     * only an email address and the password
     *
     * @param userName the user's email address
     * @param password the user's password
     * @return a HostAuth ready to be saved in an Account or null (failure)
     */
    public Bundle tryAutodiscover(String userName, String password) throws RemoteException {
        XmlSerializer s = Xml.newSerializer();
        ByteArrayOutputStream os = new ByteArrayOutputStream(1024);
        HostAuth hostAuth = new HostAuth();
        Bundle bundle = new Bundle();
        bundle.putInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE,
                MessagingException.NO_ERROR);
        try {
            // Build the XML document that's sent to the autodiscover server(s)
            s.setOutput(os, "UTF-8");
            s.startDocument("UTF-8", false);
            s.startTag(null, "Autodiscover");
            s.attribute(null, "xmlns", AUTO_DISCOVER_SCHEMA_PREFIX + "requestschema/2006");
            s.startTag(null, "Request");
            s.startTag(null, "EMailAddress").text(userName).endTag(null, "EMailAddress");
            s.startTag(null, "AcceptableResponseSchema");
            s.text(AUTO_DISCOVER_SCHEMA_PREFIX + "responseschema/2006");
            s.endTag(null, "AcceptableResponseSchema");
            s.endTag(null, "Request");
            s.endTag(null, "Autodiscover");
            s.endDocument();
            String req = os.toString();

            // Initialize the user name and password
            mUserName = userName;
            mPassword = password;
            // Make sure the authentication string is recreated and cached
            cacheAuthUserAndBaseUriStrings();

            // Split out the domain name
            int amp = userName.indexOf('@');
            // The UI ensures that userName is a valid email address
            if (amp < 0) {
                throw new RemoteException();
            }
            String domain = userName.substring(amp + 1);

            // There are up to four attempts here; the two URLs that we're supposed to try per the
            // specification, and up to one redirect for each (handled in postAutodiscover)
            // Note: The expectation is that, of these four attempts, only a single server will
            // actually be identified as the autodiscover server.  For the identified server,
            // we may also try a 2nd connection with a different format (bare name).

            // Try the domain first and see if we can get a response
            HttpPost post = new HttpPost("https://" + domain + AUTO_DISCOVER_PAGE);
            setHeaders(post, false);
            post.setHeader("Content-Type", "text/xml");
            post.setEntity(new StringEntity(req));
            HttpClient client = getHttpClient(COMMAND_TIMEOUT);
            EasResponse resp;
            try {
                resp = postAutodiscover(client, post, true /*canRetry*/);
            } catch (IOException e1) {
                userLog("IOException in autodiscover; trying alternate address");
                // We catch the IOException here because we have an alternate address to try
                post.setURI(URI.create("https://autodiscover." + domain + AUTO_DISCOVER_PAGE));
                // If we fail here, we're out of options, so we let the outer try catch the
                // IOException and return null
                resp = postAutodiscover(client, post, true /*canRetry*/);
            }

            try {
                // Get the "final" code; if it's not 200, just return null
                int code = resp.getStatus();
                userLog("Code: " + code);
                if (code != HttpStatus.SC_OK) return null;

                InputStream is = resp.getInputStream();
                // The response to Autodiscover is regular XML (not WBXML)
                // If we ever get an error in this process, we'll just punt and return null
                XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                XmlPullParser parser = factory.newPullParser();
                parser.setInput(is, "UTF-8");
                int type = parser.getEventType();
                if (type == XmlPullParser.START_DOCUMENT) {
                    type = parser.next();
                    if (type == XmlPullParser.START_TAG) {
                        String name = parser.getName();
                        if (name.equals("Autodiscover")) {
                            hostAuth = new HostAuth();
                            parseAutodiscover(parser, hostAuth);
                            // On success, we'll have a server address and login
                            if (hostAuth.mAddress != null) {
                                // Fill in the rest of the HostAuth
                                // We use the user name and password that were successful during
                                // the autodiscover process
                                hostAuth.mLogin = mUserName;
                                hostAuth.mPassword = mPassword;
                                // Note: there is no way we can auto-discover the proper client
                                // SSL certificate to use, if one is needed.
                                hostAuth.mPort = 443;
                                hostAuth.mProtocol = "eas";
                                hostAuth.mFlags =
                                    HostAuth.FLAG_SSL | HostAuth.FLAG_AUTHENTICATE;
                                bundle.putParcelable(
                                        EmailServiceProxy.AUTO_DISCOVER_BUNDLE_HOST_AUTH, hostAuth);
                            } else {
                                bundle.putInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE,
                                        MessagingException.UNSPECIFIED_EXCEPTION);
                            }
                        }
                    }
                }
            } catch (XmlPullParserException e1) {
                // This would indicate an I/O error of some sort
                // We will simply return null and user can configure manually
            } finally {
               resp.close();
            }
        // There's no reason at all for exceptions to be thrown, and it's ok if so.
        // We just won't do auto-discover; user can configure manually
       } catch (IllegalArgumentException e) {
             bundle.putInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE,
                     MessagingException.UNSPECIFIED_EXCEPTION);
       } catch (IllegalStateException e) {
            bundle.putInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE,
                    MessagingException.UNSPECIFIED_EXCEPTION);
       } catch (IOException e) {
            userLog("IOException in Autodiscover", e);
            bundle.putInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE,
                    MessagingException.IOERROR);
        } catch (MessagingException e) {
            bundle.putInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE,
                    MessagingException.AUTODISCOVER_AUTHENTICATION_FAILED);
        }
        return bundle;
    }

    void parseServer(XmlPullParser parser, HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        boolean mobileSync = false;
        while (true) {
            int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals("Server")) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if (name.equals("Type")) {
                    if (parser.nextText().equals("MobileSync")) {
                        mobileSync = true;
                    }
                } else if (mobileSync && name.equals("Url")) {
                    String url = parser.nextText().toLowerCase();
                    // This will look like https://<server address>/Microsoft-Server-ActiveSync
                    // We need to extract the <server address>
                    if (url.startsWith("https://") &&
                            url.endsWith("/microsoft-server-activesync")) {
                        int lastSlash = url.lastIndexOf('/');
                        hostAuth.mAddress = url.substring(8, lastSlash);
                        userLog("Autodiscover, server: " + hostAuth.mAddress);
                    }
                }
            }
        }
    }

    void parseSettings(XmlPullParser parser, HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        while (true) {
            int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals("Settings")) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if (name.equals("Server")) {
                    parseServer(parser, hostAuth);
                }
            }
        }
    }

    void parseAction(XmlPullParser parser, HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        while (true) {
            int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals("Action")) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if (name.equals("Error")) {
                    // Should parse the error
                } else if (name.equals("Redirect")) {
                    Log.d(TAG, "Redirect: " + parser.nextText());
                } else if (name.equals("Settings")) {
                    parseSettings(parser, hostAuth);
                }
            }
        }
    }

    void parseUser(XmlPullParser parser, HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        while (true) {
            int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals("User")) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if (name.equals("EMailAddress")) {
                    String addr = parser.nextText();
                    userLog("Autodiscover, email: " + addr);
                } else if (name.equals("DisplayName")) {
                    String dn = parser.nextText();
                    userLog("Autodiscover, user: " + dn);
                }
            }
        }
    }

    void parseResponse(XmlPullParser parser, HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        while (true) {
            int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals("Response")) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if (name.equals("User")) {
                    parseUser(parser, hostAuth);
                } else if (name.equals("Action")) {
                    parseAction(parser, hostAuth);
                }
            }
        }
    }

    void parseAutodiscover(XmlPullParser parser, HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        while (true) {
            int type = parser.nextTag();
            if (type == XmlPullParser.END_TAG && parser.getName().equals("Autodiscover")) {
                break;
            } else if (type == XmlPullParser.START_TAG && parser.getName().equals("Response")) {
                parseResponse(parser, hostAuth);
            }
        }
    }

    /**
     * Contact the GAL and obtain a list of matching accounts
     * @param context caller's context
     * @param accountId the account Id to search
     * @param filter the characters entered so far
     * @return a result record or null for no data
     *
     * TODO: shorter timeout for interactive lookup
     * TODO: make watchdog actually work (it doesn't understand our service w/Mailbox == 0)
     * TODO: figure out why sendHttpClientPost() hangs - possibly pool exhaustion
     */
    static public GalResult searchGal(Context context, long accountId, String filter, int limit) {
        Account acct = Account.restoreAccountWithId(context, accountId);
        if (acct != null) {
            EasSyncService svc = setupServiceForAccount(context, acct);
            if (svc == null) return null;
            try {
                Serializer s = new Serializer();
                s.start(Tags.SEARCH_SEARCH).start(Tags.SEARCH_STORE);
                s.data(Tags.SEARCH_NAME, "GAL").data(Tags.SEARCH_QUERY, filter);
                s.start(Tags.SEARCH_OPTIONS);
                s.data(Tags.SEARCH_RANGE, "0-" + Integer.toString(limit - 1));
                s.end().end().end().done();
                EasResponse resp = svc.sendHttpClientPost("Search", s.toByteArray());
                try {
                    int code = resp.getStatus();
                    if (code == HttpStatus.SC_OK) {
                        InputStream is = resp.getInputStream();
                        try {
                            GalParser gp = new GalParser(is, svc);
                            if (gp.parse()) {
                                return gp.getGalResult();
                            }
                        } finally {
                            is.close();
                        }
                    } else {
                        svc.userLog("GAL lookup returned " + code);
                    }
                } finally {
                    resp.close();
                }
            } catch (IOException e) {
                // GAL is non-critical; we'll just go on
                svc.userLog("GAL lookup exception " + e);
            }
        }
        return null;
    }
    /**
     * Send an email responding to a Message that has been marked as a meeting request.  The message
     * will consist a little bit of event information and an iCalendar attachment
     * @param msg the meeting request email
     */
    private void sendMeetingResponseMail(Message msg, int response) {
        // Get the meeting information; we'd better have some...
        if (msg.mMeetingInfo == null) return;
        PackedString meetingInfo = new PackedString(msg.mMeetingInfo);

        // This will come as "First Last" <box@server.blah>, so we use Address to
        // parse it into parts; we only need the email address part for the ics file
        Address[] addrs = Address.parse(meetingInfo.get(MeetingInfo.MEETING_ORGANIZER_EMAIL));
        // It shouldn't be possible, but handle it anyway
        if (addrs.length != 1) return;
        String organizerEmail = addrs[0].getAddress();

        String dtStamp = meetingInfo.get(MeetingInfo.MEETING_DTSTAMP);
        String dtStart = meetingInfo.get(MeetingInfo.MEETING_DTSTART);
        String dtEnd = meetingInfo.get(MeetingInfo.MEETING_DTEND);

        // What we're doing here is to create an Entity that looks like an Event as it would be
        // stored by CalendarProvider
        ContentValues entityValues = new ContentValues();
        Entity entity = new Entity(entityValues);

        // Fill in times, location, title, and organizer
        entityValues.put("DTSTAMP",
                CalendarUtilities.convertEmailDateTimeToCalendarDateTime(dtStamp));
        entityValues.put(Events.DTSTART, Utility.parseEmailDateTimeToMillis(dtStart));
        entityValues.put(Events.DTEND, Utility.parseEmailDateTimeToMillis(dtEnd));
        entityValues.put(Events.EVENT_LOCATION, meetingInfo.get(MeetingInfo.MEETING_LOCATION));
        entityValues.put(Events.TITLE, meetingInfo.get(MeetingInfo.MEETING_TITLE));
        entityValues.put(Events.ORGANIZER, organizerEmail);

        // Add ourselves as an attendee, using our account email address
        ContentValues attendeeValues = new ContentValues();
        attendeeValues.put(Attendees.ATTENDEE_RELATIONSHIP,
                Attendees.RELATIONSHIP_ATTENDEE);
        attendeeValues.put(Attendees.ATTENDEE_EMAIL, mAccount.mEmailAddress);
        entity.addSubValue(Attendees.CONTENT_URI, attendeeValues);

        // Add the organizer
        ContentValues organizerValues = new ContentValues();
        organizerValues.put(Attendees.ATTENDEE_RELATIONSHIP,
                Attendees.RELATIONSHIP_ORGANIZER);
        organizerValues.put(Attendees.ATTENDEE_EMAIL, organizerEmail);
        entity.addSubValue(Attendees.CONTENT_URI, organizerValues);

        // Create a message from the Entity we've built.  The message will have fields like
        // to, subject, date, and text filled in.  There will also be an "inline" attachment
        // which is in iCalendar format
        int flag;
        switch(response) {
            case EmailServiceConstants.MEETING_REQUEST_ACCEPTED:
                flag = Message.FLAG_OUTGOING_MEETING_ACCEPT;
                break;
            case EmailServiceConstants.MEETING_REQUEST_DECLINED:
                flag = Message.FLAG_OUTGOING_MEETING_DECLINE;
                break;
            case EmailServiceConstants.MEETING_REQUEST_TENTATIVE:
            default:
                flag = Message.FLAG_OUTGOING_MEETING_TENTATIVE;
                break;
        }
        Message outgoingMsg =
            CalendarUtilities.createMessageForEntity(mContext, entity, flag,
                    meetingInfo.get(MeetingInfo.MEETING_UID), mAccount);
        // Assuming we got a message back (we might not if the event has been deleted), send it
        if (outgoingMsg != null) {
            EasOutboxService.sendMessage(mContext, mAccount.mId, outgoingMsg);
        }
    }

    /**
     * Responds to a move request.  The MessageMoveRequest is basically our
     * wrapper for the MoveItems service call
     * @param req the request (message id and "to" mailbox id)
     * @throws IOException
     */
    protected void messageMoveRequest(MessageMoveRequest req) throws IOException {
        // Retrieve the message and mailbox; punt if either are null
        Message msg = Message.restoreMessageWithId(mContext, req.mMessageId);
        if (msg == null) return;
        Cursor c = mContentResolver.query(ContentUris.withAppendedId(Message.UPDATED_CONTENT_URI,
                msg.mId), new String[] {MessageColumns.MAILBOX_KEY}, null, null, null);
        if (c == null) throw new ProviderUnavailableException();
        Mailbox srcMailbox = null;
        try {
            if (!c.moveToNext()) return;
            srcMailbox = Mailbox.restoreMailboxWithId(mContext, c.getLong(0));
        } finally {
            c.close();
        }
        if (srcMailbox == null) return;
        Mailbox dstMailbox = Mailbox.restoreMailboxWithId(mContext, req.mMailboxId);
        if (dstMailbox == null) return;
        Serializer s = new Serializer();
        s.start(Tags.MOVE_MOVE_ITEMS).start(Tags.MOVE_MOVE);
        s.data(Tags.MOVE_SRCMSGID, msg.mServerId);
        s.data(Tags.MOVE_SRCFLDID, srcMailbox.mServerId);
        s.data(Tags.MOVE_DSTFLDID, dstMailbox.mServerId);
        s.end().end().done();
        EasResponse resp = sendHttpClientPost("MoveItems", s.toByteArray());
        try {
            int status = resp.getStatus();
            if (status == HttpStatus.SC_OK) {
                if (!resp.isEmpty()) {
                    InputStream is = resp.getInputStream();
                    MoveItemsParser p = new MoveItemsParser(is, this);
                    p.parse();
                    int statusCode = p.getStatusCode();
                    ContentValues cv = new ContentValues();
                    if (statusCode == MoveItemsParser.STATUS_CODE_REVERT) {
                        // Restore the old mailbox id
                        cv.put(MessageColumns.MAILBOX_KEY, srcMailbox.mServerId);
                        mContentResolver.update(
                                ContentUris.withAppendedId(Message.CONTENT_URI, req.mMessageId),
                                cv, null, null);
                    } else if (statusCode == MoveItemsParser.STATUS_CODE_SUCCESS) {
                        // Update with the new server id
                        cv.put(SyncColumns.SERVER_ID, p.getNewServerId());
                        cv.put(Message.FLAGS, msg.mFlags | MESSAGE_FLAG_MOVED_MESSAGE);
                        mContentResolver.update(
                                ContentUris.withAppendedId(Message.CONTENT_URI, req.mMessageId),
                                cv, null, null);
                    }
                    if (statusCode == MoveItemsParser.STATUS_CODE_SUCCESS
                            || statusCode == MoveItemsParser.STATUS_CODE_REVERT) {
                        // If we revert or succeed, we no longer need the update information
                        // OR the now-duplicate email (the new copy will be synced down)
                        mContentResolver.delete(ContentUris.withAppendedId(
                                Message.UPDATED_CONTENT_URI, req.mMessageId), null, null);
                    } else {
                        // In this case, we're retrying, so do nothing.  The request will be
                        // handled next sync
                    }
                }
            } else if (EasResponse.isAuthError(status)) {
                throw new EasAuthenticationException();
            } else {
                userLog("Move items request failed, code: " + status);
                throw new IOException();
            }
        } finally {
            resp.close();
        }
    }

    /**
     * Responds to a meeting request.  The MeetingResponseRequest is basically our
     * wrapper for the meetingResponse service call
     * @param req the request (message id and response code)
     * @throws IOException
     */
    protected void sendMeetingResponse(MeetingResponseRequest req) throws IOException {
        // Retrieve the message and mailbox; punt if either are null
        Message msg = Message.restoreMessageWithId(mContext, req.mMessageId);
        if (msg == null) return;
        Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, msg.mMailboxKey);
        if (mailbox == null) return;
        Serializer s = new Serializer();
        s.start(Tags.MREQ_MEETING_RESPONSE).start(Tags.MREQ_REQUEST);
        s.data(Tags.MREQ_USER_RESPONSE, Integer.toString(req.mResponse));
        s.data(Tags.MREQ_COLLECTION_ID, mailbox.mServerId);
        s.data(Tags.MREQ_REQ_ID, msg.mServerId);
        s.end().end().done();
        EasResponse resp = sendHttpClientPost("MeetingResponse", s.toByteArray());
        try {
            int status = resp.getStatus();
            if (status == HttpStatus.SC_OK) {
                if (!resp.isEmpty()) {
                    InputStream is = resp.getInputStream();
                    new MeetingResponseParser(is, this).parse();
                    String meetingInfo = msg.mMeetingInfo;
                    if (meetingInfo != null) {
                        String responseRequested = new PackedString(meetingInfo).get(
                                MeetingInfo.MEETING_RESPONSE_REQUESTED);
                        // If there's no tag, or a non-zero tag, we send the response mail
                        if ("0".equals(responseRequested)) {
                            return;
                        }
                    }
                    sendMeetingResponseMail(msg, req.mResponse);
                }
            } else if (EasResponse.isAuthError(status)) {
                throw new EasAuthenticationException();
            } else {
                userLog("Meeting response request failed, code: " + status);
                throw new IOException();
            }
        } finally {
            resp.close();
       }
    }

    /**
     * Using mUserName and mPassword, lazily create the strings that are commonly used in our HTTP
     * POSTs, including the authentication header string, the base URI we use to communicate with
     * EAS, and the user information string (user, deviceId, and deviceType)
     */
    private void cacheAuthUserAndBaseUriStrings() {
        if (mAuthString == null || mUserString == null || mBaseUriString == null) {
            String safeUserName = Uri.encode(mUserName);
            String cs = mUserName + ':' + mPassword;
            mAuthString = "Basic " + Base64.encodeToString(cs.getBytes(), Base64.NO_WRAP);
            mUserString = "&User=" + safeUserName + "&DeviceId=" + mDeviceId +
                "&DeviceType=" + DEVICE_TYPE;
            String scheme =
                EmailClientConnectionManager.makeScheme(mSsl, mTrustSsl, mClientCertAlias);
            mBaseUriString = scheme + "://" + mHostAddress + "/Microsoft-Server-ActiveSync";
        }
    }

    @VisibleForTesting
    String makeUriString(String cmd, String extra) {
        cacheAuthUserAndBaseUriStrings();
        String uriString = mBaseUriString;
        if (cmd != null) {
            uriString += "?Cmd=" + cmd + mUserString;
        }
        if (extra != null) {
            uriString += extra;
        }
        return uriString;
    }

    /**
     * Set standard HTTP headers, using a policy key if required
     * @param method the method we are going to send
     * @param usePolicyKey whether or not a policy key should be sent in the headers
     */
    /*package*/ void setHeaders(HttpRequestBase method, boolean usePolicyKey) {
        method.setHeader("Authorization", mAuthString);
        method.setHeader("MS-ASProtocolVersion", mProtocolVersion);
        method.setHeader("User-Agent", USER_AGENT);
        method.setHeader("Accept-Encoding", "gzip");
        if (usePolicyKey) {
            // If there's an account in existence, use its key; otherwise (we're creating the
            // account), send "0".  The server will respond with code 449 if there are policies
            // to be enforced
            String key = "0";
            if (mAccount != null) {
                String accountKey = mAccount.mSecuritySyncKey;
                if (!TextUtils.isEmpty(accountKey)) {
                    key = accountKey;
                }
            }
            method.setHeader("X-MS-PolicyKey", key);
        }
    }

    protected void setConnectionParameters(
            boolean useSsl, boolean trustAllServerCerts, String clientCertAlias)
            throws CertificateException {

        EmailClientConnectionManager connManager = getClientConnectionManager();

        mSsl = useSsl;
        mTrustSsl = trustAllServerCerts;
        mClientCertAlias = clientCertAlias;

        // Register the new alias, if needed.
        if (mClientCertAlias != null) {
            // Ensure that the connection manager knows to use the proper client certificate
            // when establishing connections for this service.
            connManager.registerClientCert(mContext, mClientCertAlias, mTrustSsl);
        }
    }

    private EmailClientConnectionManager getClientConnectionManager() {
        return ExchangeService.getClientConnectionManager();
    }

    private HttpClient getHttpClient(int timeout) {
        HttpParams params = new BasicHttpParams();
        HttpConnectionParams.setConnectionTimeout(params, CONNECTION_TIMEOUT);
        HttpConnectionParams.setSoTimeout(params, timeout);
        HttpConnectionParams.setSocketBufferSize(params, 8192);
        HttpClient client = new DefaultHttpClient(getClientConnectionManager(), params);
        return client;
    }

    public EasResponse sendHttpClientPost(String cmd, byte[] bytes) throws IOException {
        return sendHttpClientPost(cmd, new ByteArrayEntity(bytes), COMMAND_TIMEOUT);
    }

    protected EasResponse sendHttpClientPost(String cmd, HttpEntity entity) throws IOException {
        return sendHttpClientPost(cmd, entity, COMMAND_TIMEOUT);
    }

    protected EasResponse sendPing(byte[] bytes, int heartbeat) throws IOException {
       Thread.currentThread().setName(mAccount.mDisplayName + ": Ping");
       if (Eas.USER_LOG) {
           userLog("Send ping, timeout: " + heartbeat + "s, high: " + mPingHighWaterMark + 's');
       }
       return sendHttpClientPost(PING_COMMAND, new ByteArrayEntity(bytes), (heartbeat+5)*SECONDS);
    }

    /**
     * Convenience method for executePostWithTimeout for use other than with the Ping command
     */
    protected EasResponse executePostWithTimeout(HttpClient client, HttpPost method, int timeout)
            throws IOException {
        return executePostWithTimeout(client, method, timeout, false);
    }

    /**
     * Handle executing an HTTP POST command with proper timeout, watchdog, and ping behavior
     * @param client the HttpClient
     * @param method the HttpPost
     * @param timeout the timeout before failure, in ms
     * @param isPingCommand whether the POST is for the Ping command (requires wakelock logic)
     * @return the HttpResponse
     * @throws IOException
     */
    protected EasResponse executePostWithTimeout(HttpClient client, HttpPost method, int timeout,
            boolean isPingCommand) throws IOException {
        synchronized(getSynchronizer()) {
            mPendingPost = method;
            long alarmTime = timeout + WATCHDOG_TIMEOUT_ALLOWANCE;
            if (isPingCommand) {
                ExchangeService.runAsleep(mMailboxId, alarmTime);
            } else {
                ExchangeService.setWatchdogAlarm(mMailboxId, alarmTime);
            }
        }
        try {
            return EasResponse.fromHttpRequest(getClientConnectionManager(), client, method);
        } finally {
            synchronized(getSynchronizer()) {
                if (isPingCommand) {
                    ExchangeService.runAwake(mMailboxId);
                } else {
                    ExchangeService.clearWatchdogAlarm(mMailboxId);
                }
                mPendingPost = null;
            }
        }
    }

    public EasResponse sendHttpClientPost(String cmd, HttpEntity entity, int timeout)
            throws IOException {
        HttpClient client = getHttpClient(timeout);
        boolean isPingCommand = cmd.equals(PING_COMMAND);

        // Split the mail sending commands
        String extra = null;
        boolean msg = false;
        if (cmd.startsWith("SmartForward&") || cmd.startsWith("SmartReply&")) {
            int cmdLength = cmd.indexOf('&');
            extra = cmd.substring(cmdLength);
            cmd = cmd.substring(0, cmdLength);
            msg = true;
        } else if (cmd.startsWith("SendMail&")) {
            msg = true;
        }

        String us = makeUriString(cmd, extra);
        HttpPost method = new HttpPost(URI.create(us));
        // Send the proper Content-Type header; it's always wbxml except for messages when
        // the EAS protocol version is < 14.0
        // If entity is null (e.g. for attachments), don't set this header
        if (msg && (mProtocolVersionDouble < Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE)) {
            method.setHeader("Content-Type", "message/rfc822");
        } else if (entity != null) {
            method.setHeader("Content-Type", "application/vnd.ms-sync.wbxml");
        }
        setHeaders(method, !isPingCommand);
        // NOTE
        // The next lines are added at the insistence of $VENDOR, who is seeing inappropriate
        // network activity related to the Ping command on some networks with some servers.
        // This code should be removed when the underlying issue is resolved
        if (isPingCommand) {
            method.setHeader("Connection", "close");
        }
        method.setEntity(entity);
        return executePostWithTimeout(client, method, timeout, isPingCommand);
    }

    protected EasResponse sendHttpClientOptions() throws IOException {
        cacheAuthUserAndBaseUriStrings();
        // For OPTIONS, just use the base string and the single header
        String uriString = mBaseUriString;
        HttpOptions method = new HttpOptions(URI.create(uriString));
        method.setHeader("Authorization", mAuthString);
        method.setHeader("User-Agent", USER_AGENT);
        HttpClient client = getHttpClient(COMMAND_TIMEOUT);
        return EasResponse.fromHttpRequest(getClientConnectionManager(), client, method);
    }

    private String getTargetCollectionClassFromCursor(Cursor c) {
        int type = c.getInt(Mailbox.CONTENT_TYPE_COLUMN);
        if (type == Mailbox.TYPE_CONTACTS) {
            return "Contacts";
        } else if (type == Mailbox.TYPE_CALENDAR) {
            return "Calendar";
        } else {
            return "Email";
        }
    }

    /**
     * Negotiate provisioning with the server.  First, get policies form the server and see if
     * the policies are supported by the device.  Then, write the policies to the account and
     * tell SecurityPolicy that we have policies in effect.  Finally, see if those policies are
     * active; if so, acknowledge the policies to the server and get a final policy key that we
     * use in future EAS commands and write this key to the account.
     * @return whether or not provisioning has been successful
     * @throws IOException
     */
    private boolean tryProvision() throws IOException {
        // First, see if provisioning is even possible, i.e. do we support the policies required
        // by the server
        ProvisionParser pp = canProvision();
        if (pp != null && pp.hasSupportablePolicySet()) {
            // Get the policies from ProvisionParser
            Policy policy = pp.getPolicy();
            Policy oldPolicy = null;
            // Grab the old policy (if any)
            if (mAccount.mPolicyKey > 0) {
                oldPolicy = Policy.restorePolicyWithId(mContext, mAccount.mPolicyKey);
            }
            // Update the account with a null policyKey (the key we've gotten is
            // temporary and cannot be used for syncing)
            Policy.setAccountPolicy(mContext, mAccount, policy, null);
            // Make sure mAccount is current (with latest policy key)
            mAccount.refresh(mContext);
            // Make sure that SecurityPolicy is up-to-date
            SecurityPolicyDelegate.policiesUpdated(mContext, mAccount.mId);
            if (pp.getRemoteWipe()) {
                // We've gotten a remote wipe command
                ExchangeService.alwaysLog("!!! Remote wipe request received");
                // Start by setting the account to security hold
                SecurityPolicyDelegate.setAccountHoldFlag(mContext, mAccount, true);
                // Force a stop to any running syncs for this account (except this one)
                ExchangeService.stopNonAccountMailboxSyncsForAccount(mAccount.mId);

                // If we're not the admin, we can't do the wipe, so just return
                if (!SecurityPolicyDelegate.isActiveAdmin(mContext)) {
                    ExchangeService.alwaysLog("!!! Not device admin; can't wipe");
                    return false;
                }

                // First, we've got to acknowledge it, but wrap the wipe in try/catch so that
                // we wipe the device regardless of any errors in acknowledgment
                try {
                    ExchangeService.alwaysLog("!!! Acknowledging remote wipe to server");
                    acknowledgeRemoteWipe(pp.getSecuritySyncKey());
                } catch (Exception e) {
                    // Because remote wipe is such a high priority task, we don't want to
                    // circumvent it if there's an exception in acknowledgment
                }
                // Then, tell SecurityPolicy to wipe the device
                ExchangeService.alwaysLog("!!! Executing remote wipe");
                SecurityPolicyDelegate.remoteWipe(mContext);
                return false;
            } else if (SecurityPolicyDelegate.isActive(mContext, policy)) {
                // See if the required policies are in force; if they are, acknowledge the policies
                // to the server and get the final policy key
                // NOTE: For EAS 14.0, we already have the acknowledgment in the ProvisionParser
                String securitySyncKey;
                if (mProtocolVersionDouble == Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE) {
                    securitySyncKey = pp.getSecuritySyncKey();
                } else {
                    securitySyncKey = acknowledgeProvision(pp.getSecuritySyncKey(),
                            PROVISION_STATUS_OK);
                }
                if (securitySyncKey != null) {
                    // If attachment policies have changed, fix up any affected attachment records
                    if (oldPolicy != null) {
                        if ((oldPolicy.mDontAllowAttachments != policy.mDontAllowAttachments) ||
                                (oldPolicy.mMaxAttachmentSize != policy.mMaxAttachmentSize)) {
                            Policy.setAttachmentFlagsForNewPolicy(mContext, mAccount, policy);
                        }
                    }
                    // Write the final policy key to the Account and say we've been successful
                    Policy.setAccountPolicy(mContext, mAccount, policy, securitySyncKey);
                    // Release any mailboxes that might be in a security hold
                    ExchangeService.releaseSecurityHold(mAccount);
                    return true;
                }
            } else {
                // Notify that we are blocked because of policies
                // TODO: Indicate unsupported policies here?
                SecurityPolicyDelegate.policiesRequired(mContext, mAccount.mId);
            }
        }
        return false;
    }

    private String getPolicyType() {
        return (mProtocolVersionDouble >=
            Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) ? EAS_12_POLICY_TYPE : EAS_2_POLICY_TYPE;
    }

    /**
     * Obtain a set of policies from the server and determine whether those policies are supported
     * by the device.
     * @return the ProvisionParser (holds policies and key) if we receive policies; null otherwise
     * @throws IOException
     */
    private ProvisionParser canProvision() throws IOException {
        Serializer s = new Serializer();
        s.start(Tags.PROVISION_PROVISION);
        if (mProtocolVersionDouble >= Eas.SUPPORTED_PROTOCOL_EX2010_SP1_DOUBLE) {
            // Send settings information in 14.1 and greater
            s.start(Tags.SETTINGS_DEVICE_INFORMATION).start(Tags.SETTINGS_SET);
            s.data(Tags.SETTINGS_MODEL, Build.MODEL);
            //s.data(Tags.SETTINGS_IMEI, "");
            //s.data(Tags.SETTINGS_FRIENDLY_NAME, "Friendly Name");
            s.data(Tags.SETTINGS_OS, "Android " + Build.VERSION.RELEASE);
            //s.data(Tags.SETTINGS_OS_LANGUAGE, "");
            //s.data(Tags.SETTINGS_PHONE_NUMBER, "");
            //s.data(Tags.SETTINGS_MOBILE_OPERATOR, "");
            s.data(Tags.SETTINGS_USER_AGENT, USER_AGENT);
            s.end().end();  // SETTINGS_SET, SETTINGS_DEVICE_INFORMATION
        }
        s.start(Tags.PROVISION_POLICIES);
        s.start(Tags.PROVISION_POLICY).data(Tags.PROVISION_POLICY_TYPE, getPolicyType()).end();
        s.end();  // PROVISION_POLICIES
        s.end().done(); // PROVISION_PROVISION
        EasResponse resp = sendHttpClientPost("Provision", s.toByteArray());
        try {
            int code = resp.getStatus();
            if (code == HttpStatus.SC_OK) {
                InputStream is = resp.getInputStream();
                ProvisionParser pp = new ProvisionParser(is, this);
                if (pp.parse()) {
                    // The PolicySet in the ProvisionParser will have the requirements for all KNOWN
                    // policies.  If others are required, hasSupportablePolicySet will be false
                    if (pp.hasSupportablePolicySet() &&
                            mProtocolVersionDouble == Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE) {
                        // In EAS 14.0, we need the final security key in order to use the settings
                        // command
                        String policyKey = acknowledgeProvision(pp.getSecuritySyncKey(),
                                PROVISION_STATUS_OK);
                        if (policyKey != null) {
                            pp.setSecuritySyncKey(policyKey);
                        }
                    } else if (!pp.hasSupportablePolicySet())  {
                        // Try to acknowledge using the "partial" status (i.e. we can partially
                        // accommodate the required policies).  The server will agree to this if the
                        // "allow non-provisionable devices" setting is enabled on the server
                        ExchangeService.log("PolicySet is NOT fully supportable");
                        String policyKey = acknowledgeProvision(pp.getSecuritySyncKey(),
                                PROVISION_STATUS_PARTIAL);
                        // Return either the parser (success) or null (failure)
                        if (policyKey != null) {
                            pp.clearUnsupportedPolicies();
                        }
                    }
                    return pp;
                }
            }
        } finally {
            resp.close();
        }

        // On failures, simply return null
        return null;
    }

    /**
     * Acknowledge that we support the policies provided by the server, and that these policies
     * are in force.
     * @param tempKey the initial (temporary) policy key sent by the server
     * @return the final policy key, which can be used for syncing
     * @throws IOException
     */
    private void acknowledgeRemoteWipe(String tempKey) throws IOException {
        acknowledgeProvisionImpl(tempKey, PROVISION_STATUS_OK, true);
    }

    private String acknowledgeProvision(String tempKey, String result) throws IOException {
        return acknowledgeProvisionImpl(tempKey, result, false);
    }

    private String acknowledgeProvisionImpl(String tempKey, String status,
            boolean remoteWipe) throws IOException {
        Serializer s = new Serializer();
        s.start(Tags.PROVISION_PROVISION).start(Tags.PROVISION_POLICIES);
        s.start(Tags.PROVISION_POLICY);

        // Use the proper policy type, depending on EAS version
        s.data(Tags.PROVISION_POLICY_TYPE, getPolicyType());

        s.data(Tags.PROVISION_POLICY_KEY, tempKey);
        s.data(Tags.PROVISION_STATUS, status);
        s.end().end(); // PROVISION_POLICY, PROVISION_POLICIES
        if (remoteWipe) {
            s.start(Tags.PROVISION_REMOTE_WIPE);
            s.data(Tags.PROVISION_STATUS, PROVISION_STATUS_OK);
            s.end();
        }
        s.end().done(); // PROVISION_PROVISION
        EasResponse resp = sendHttpClientPost("Provision", s.toByteArray());
        try {
            int code = resp.getStatus();
            if (code == HttpStatus.SC_OK) {
                InputStream is = resp.getInputStream();
                ProvisionParser pp = new ProvisionParser(is, this);
                if (pp.parse()) {
                    // Return the final policy key from the ProvisionParser
                    ExchangeService.log("Provision confirmation received for " +
                            (PROVISION_STATUS_PARTIAL.equals(status) ? "PART" : "FULL") + " set");
                    return pp.getSecuritySyncKey();
                }
            }
        } finally {
            resp.close();
        }
        // On failures, log issue and return null
        ExchangeService.log("Provision confirmation failed for" +
                (PROVISION_STATUS_PARTIAL.equals(status) ? "PART" : "FULL") + " set");
        return null;
    }

    private boolean sendSettings() throws IOException {
        Serializer s = new Serializer();
        s.start(Tags.SETTINGS_SETTINGS);
        s.start(Tags.SETTINGS_DEVICE_INFORMATION).start(Tags.SETTINGS_SET);
        s.data(Tags.SETTINGS_MODEL, Build.MODEL);
        s.data(Tags.SETTINGS_OS, "Android " + Build.VERSION.RELEASE);
        s.data(Tags.SETTINGS_USER_AGENT, USER_AGENT);
        s.end().end().end().done(); // SETTINGS_SET, SETTINGS_DEVICE_INFORMATION, SETTINGS_SETTINGS
        EasResponse resp = sendHttpClientPost("Settings", s.toByteArray());
        try {
            int code = resp.getStatus();
            if (code == HttpStatus.SC_OK) {
                InputStream is = resp.getInputStream();
                SettingsParser sp = new SettingsParser(is, this);
                return sp.parse();
            }
        } finally {
            resp.close();
        }
        // On failures, simply return false
        return false;
    }

    /**
     * Translate exit status code to service status code (used in callbacks)
     * @param exitStatus the service's exit status
     * @return the corresponding service status
     */
    private int exitStatusToServiceStatus(int exitStatus) {
        switch(exitStatus) {
            case EXIT_SECURITY_FAILURE:
                return EmailServiceStatus.SECURITY_FAILURE;
            case EXIT_LOGIN_FAILURE:
                return EmailServiceStatus.LOGIN_FAILED;
            default:
                return EmailServiceStatus.SUCCESS;
        }
    }

    /**
     * If possible, update the account to the new server address; report result
     * @param resp the EasResponse from the current POST
     * @return whether or not the redirect is handled and the POST should be retried
     */
    private boolean canHandleAccountMailboxRedirect(EasResponse resp) {
        userLog("AccountMailbox redirect error");
        HostAuth ha =
                HostAuth.restoreHostAuthWithId(mContext, mAccount.mHostAuthKeyRecv);
        if (ha != null && getValidateRedirect(resp, ha)) {
            // Update the account's HostAuth with new values
            ContentValues haValues = new ContentValues();
            haValues.put(HostAuthColumns.ADDRESS, ha.mAddress);
            ha.update(mContext, haValues);
            return true;
        }
        return false;
    }

    /**
     * Performs FolderSync
     *
     * @throws IOException
     * @throws EasParserException
     */
    public void runAccountMailbox() throws IOException, EasParserException {
        // Check that the account's mailboxes are consistent
        MailboxUtilities.checkMailboxConsistency(mContext, mAccount.mId);
        // Initialize exit status to success
        mExitStatus = EXIT_DONE;
        try {
            try {
                ExchangeService.callback()
                    .syncMailboxListStatus(mAccount.mId, EmailServiceStatus.IN_PROGRESS, 0);
            } catch (RemoteException e1) {
                // Don't care if this fails
            }

            if (mAccount.mSyncKey == null) {
                mAccount.mSyncKey = "0";
                userLog("Account syncKey INIT to 0");
                ContentValues cv = new ContentValues();
                cv.put(AccountColumns.SYNC_KEY, mAccount.mSyncKey);
                mAccount.update(mContext, cv);
            }

            boolean firstSync = mAccount.mSyncKey.equals("0");
            if (firstSync) {
                userLog("Initial FolderSync");
            }

            // When we first start up, change all mailboxes to push.
            ContentValues cv = new ContentValues();
            cv.put(Mailbox.SYNC_INTERVAL, Mailbox.CHECK_INTERVAL_PUSH);
            if (mContentResolver.update(Mailbox.CONTENT_URI, cv,
                    WHERE_ACCOUNT_AND_SYNC_INTERVAL_PING,
                    new String[] {Long.toString(mAccount.mId)}) > 0) {
                ExchangeService.kick("change ping boxes to push");
            }

            // Determine our protocol version, if we haven't already and save it in the Account
            // Also re-check protocol version at least once a day (in case of upgrade)
            if (mAccount.mProtocolVersion == null || firstSync ||
                   ((System.currentTimeMillis() - mMailbox.mSyncTime) > DAYS)) {
                userLog("Determine EAS protocol version");
                EasResponse resp = sendHttpClientOptions();
                try {
                    int code = resp.getStatus();
                    userLog("OPTIONS response: ", code);
                    if (code == HttpStatus.SC_OK) {
                        Header header = resp.getHeader("MS-ASProtocolCommands");
                        userLog(header.getValue());
                        header = resp.getHeader("ms-asprotocolversions");
                        try {
                            setupProtocolVersion(this, header);
                        } catch (MessagingException e) {
                            // Since we've already validated, this can't really happen
                            // But if it does, we'll rethrow this...
                            throw new IOException();
                        }
                        // Save the protocol version
                        cv.clear();
                        // Save the protocol version in the account; if we're using 12.0 or greater,
                        // set the flag for support of SmartForward
                        cv.put(Account.PROTOCOL_VERSION, mProtocolVersion);
                        mAccount.update(mContext, cv);
                        cv.clear();
                        // Save the sync time of the account mailbox to current time
                        cv.put(Mailbox.SYNC_TIME, System.currentTimeMillis());
                        mMailbox.update(mContext, cv);
                    } else if (code == EAS_REDIRECT_CODE && canHandleAccountMailboxRedirect(resp)) {
                        // Cause this to re-run
                        throw new IOException("Will retry after a brief hold...");
                    } else {
                        errorLog("OPTIONS command failed; throwing IOException");
                        throw new IOException();
                    }
                } finally {
                    resp.close();
                }
            }

            // Make sure we've upgraded flags for ICS if we're using v12.0 or later
            if (mProtocolVersionDouble >= 12.0 &&
                    (mAccount.mFlags & Account.FLAGS_SUPPORTS_SEARCH) == 0) {
                cv.clear();
                mAccount.mFlags = mAccount.mFlags | Account.FLAGS_SUPPORTS_SMART_FORWARD |
                        Account.FLAGS_SUPPORTS_SEARCH | Account.FLAGS_SUPPORTS_GLOBAL_SEARCH;
                cv.put(AccountColumns.FLAGS, mAccount.mFlags);
                mAccount.update(mContext, cv);
            }

            // Change all pushable boxes to push when we start the account mailbox
            if (mAccount.mSyncInterval == Account.CHECK_INTERVAL_PUSH) {
                cv.clear();
                cv.put(Mailbox.SYNC_INTERVAL, Mailbox.CHECK_INTERVAL_PUSH);
                if (mContentResolver.update(Mailbox.CONTENT_URI, cv,
                        ExchangeService.WHERE_IN_ACCOUNT_AND_PUSHABLE,
                        new String[] {Long.toString(mAccount.mId)}) > 0) {
                    userLog("Push account; set pushable boxes to push...");
                }
            }

            while (!mStop) {
                // If we're not allowed to sync (e.g. roaming policy), leave now
                if (!ExchangeService.canAutoSync(mAccount)) return;
                userLog("Sending Account syncKey: ", mAccount.mSyncKey);
                Serializer s = new Serializer();
                s.start(Tags.FOLDER_FOLDER_SYNC).start(Tags.FOLDER_SYNC_KEY)
                    .text(mAccount.mSyncKey).end().end().done();
                EasResponse resp = sendHttpClientPost("FolderSync", s.toByteArray());
                try {
                    if (mStop) break;
                    int code = resp.getStatus();
                    if (code == HttpStatus.SC_OK) {
                        if (!resp.isEmpty()) {
                            InputStream is = resp.getInputStream();
                            // Returns true if we need to sync again
                            if (new FolderSyncParser(is, new AccountSyncAdapter(this)).parse()) {
                                continue;
                            }
                        }
                    } else if (EasResponse.isProvisionError(code)) {
                        userLog("FolderSync provisioning error: ", code);
                        throw new CommandStatusException(CommandStatus.NEEDS_PROVISIONING);
                    } else if (EasResponse.isAuthError(code)) {
                        userLog("FolderSync auth error: ", code);
                        mExitStatus = EXIT_LOGIN_FAILURE;
                        return;
                    } else if (code == EAS_REDIRECT_CODE && canHandleAccountMailboxRedirect(resp)) {
                        // This will cause a retry of the FolderSync
                        continue;
                    } else {
                        userLog("FolderSync response error: ", code);
                    }
                } finally {
                    resp.close();
                }

                // Change all push/hold boxes to push
                cv.clear();
                cv.put(Mailbox.SYNC_INTERVAL, Account.CHECK_INTERVAL_PUSH);
                if (mContentResolver.update(Mailbox.CONTENT_URI, cv,
                        WHERE_PUSH_HOLD_NOT_ACCOUNT_MAILBOX,
                        new String[] {Long.toString(mAccount.mId)}) > 0) {
                    userLog("Set push/hold boxes to push...");
                }

                try {
                    ExchangeService.callback()
                        .syncMailboxListStatus(mAccount.mId, exitStatusToServiceStatus(mExitStatus),
                                0);
                } catch (RemoteException e1) {
                    // Don't care if this fails
                }

                // Before each run of the pingLoop, if this Account has a PolicySet, make sure it's
                // active; otherwise, clear out the key/flag.  This should cause a provisioning
                // error on the next POST, and start the security sequence over again
                String key = mAccount.mSecuritySyncKey;
                if (!TextUtils.isEmpty(key)) {
                    Policy policy = Policy.restorePolicyWithId(mContext, mAccount.mPolicyKey);
                    if ((policy != null) && !SecurityPolicyDelegate.isActive(mContext, policy)) {
                        resetSecurityPolicies();
                    }
                }

                // Wait for push notifications.
                String threadName = Thread.currentThread().getName();
                try {
                    runPingLoop();
                } catch (StaleFolderListException e) {
                    // We break out if we get told about a stale folder list
                    userLog("Ping interrupted; folder list requires sync...");
                } catch (IllegalHeartbeatException e) {
                    // If we're sending an illegal heartbeat, reset either the min or the max to
                    // that heartbeat
                    resetHeartbeats(e.mLegalHeartbeat);
                } finally {
                    Thread.currentThread().setName(threadName);
                }
            }
        } catch (CommandStatusException e) {
            // If the sync error is a provisioning failure (perhaps policies changed),
            // let's try the provisioning procedure
            // Provisioning must only be attempted for the account mailbox - trying to
            // provision any other mailbox may result in race conditions and the
            // creation of multiple policy keys.
            int status = e.mStatus;
            if (CommandStatus.isNeedsProvisioning(status)) {
                if (!tryProvision()) {
                    // Set the appropriate failure status
                    mExitStatus = EXIT_SECURITY_FAILURE;
                    return;
                }
            } else if (CommandStatus.isDeniedAccess(status)) {
                mExitStatus = EXIT_ACCESS_DENIED;
                try {
                    ExchangeService.callback().syncMailboxListStatus(mAccount.mId,
                            EmailServiceStatus.ACCESS_DENIED, 0);
                } catch (RemoteException e1) {
                    // Don't care if this fails
                }
                return;
            } else {
                userLog("Unexpected status: " + CommandStatus.toString(status));
                mExitStatus = EXIT_EXCEPTION;
            }
        } catch (IOException e) {
            // We catch this here to send the folder sync status callback
            // A folder sync failed callback will get sent from run()
            try {
                if (!mStop) {
                    // NOTE: The correct status is CONNECTION_ERROR, but the UI displays this, and
                    // it's not really appropriate for EAS as this is not unexpected for a ping and
                    // connection errors are retried in any case
                    ExchangeService.callback()
                        .syncMailboxListStatus(mAccount.mId, EmailServiceStatus.SUCCESS, 0);
                }
            } catch (RemoteException e1) {
                // Don't care if this fails
            }
            throw e;
        }
    }

    /**
     * Reset either our minimum or maximum ping heartbeat to a heartbeat known to be legal
     * @param legalHeartbeat a known legal heartbeat (from the EAS server)
     */
    /*package*/ void resetHeartbeats(int legalHeartbeat) {
        userLog("Resetting min/max heartbeat, legal = " + legalHeartbeat);
        // We are here because the current heartbeat (mPingHeartbeat) is invalid.  Depending on
        // whether the argument is above or below the current heartbeat, we can infer the need to
        // change either the minimum or maximum heartbeat
        if (legalHeartbeat > mPingHeartbeat) {
            // The legal heartbeat is higher than the ping heartbeat; therefore, our minimum was
            // too low.  We respond by raising either or both of the minimum heartbeat or the
            // force heartbeat to the argument value
            if (mPingMinHeartbeat < legalHeartbeat) {
                mPingMinHeartbeat = legalHeartbeat;
            }
            if (mPingForceHeartbeat < legalHeartbeat) {
                mPingForceHeartbeat = legalHeartbeat;
            }
            // If our minimum is now greater than the max, bring them together
            if (mPingMinHeartbeat > mPingMaxHeartbeat) {
                mPingMaxHeartbeat = legalHeartbeat;
            }
        } else if (legalHeartbeat < mPingHeartbeat) {
            // The legal heartbeat is lower than the ping heartbeat; therefore, our maximum was
            // too high.  We respond by lowering the maximum to the argument value
            mPingMaxHeartbeat = legalHeartbeat;
            // If our maximum is now less than the minimum, bring them together
            if (mPingMaxHeartbeat < mPingMinHeartbeat) {
                mPingMinHeartbeat = legalHeartbeat;
            }
        }
        // Set current heartbeat to the legal heartbeat
        mPingHeartbeat = legalHeartbeat;
        // Allow the heartbeat logic to run
        mPingHeartbeatDropped = false;
    }

    private void pushFallback(long mailboxId) {
        Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, mailboxId);
        if (mailbox == null) {
            return;
        }
        ContentValues cv = new ContentValues();
        int mins = PING_FALLBACK_PIM;
        if (mailbox.mType == Mailbox.TYPE_INBOX) {
            mins = PING_FALLBACK_INBOX;
        }
        cv.put(Mailbox.SYNC_INTERVAL, mins);
        mContentResolver.update(ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId),
                cv, null, null);
        errorLog("*** PING ERROR LOOP: Set " + mailbox.mDisplayName + " to " + mins + " min sync");
        ExchangeService.kick("push fallback");
    }

    /**
     * Simplistic attempt to determine a NAT timeout, based on experience with various carriers
     * and networks.  The string "reset by peer" is very common in these situations, so we look for
     * that specifically.  We may add additional tests here as more is learned.
     * @param message
     * @return whether this message is likely associated with a NAT failure
     */
    private boolean isLikelyNatFailure(String message) {
        if (message == null) return false;
        if (message.contains("reset by peer")) {
            return true;
        }
        return false;
    }

    private void runPingLoop() throws IOException, StaleFolderListException,
            IllegalHeartbeatException, CommandStatusException {
        int pingHeartbeat = mPingHeartbeat;
        userLog("runPingLoop");
        // Do push for all sync services here
        long endTime = System.currentTimeMillis() + (30*MINUTES);
        HashMap<String, Integer> pingErrorMap = new HashMap<String, Integer>();
        ArrayList<String> readyMailboxes = new ArrayList<String>();
        ArrayList<String> notReadyMailboxes = new ArrayList<String>();
        int pingWaitCount = 0;
        long inboxId = -1;

        while ((System.currentTimeMillis() < endTime) && !mStop) {
            // Count of pushable mailboxes
            int pushCount = 0;
            // Count of mailboxes that can be pushed right now
            int canPushCount = 0;
            // Count of uninitialized boxes
            int uninitCount = 0;

            Serializer s = new Serializer();
            Cursor c = mContentResolver.query(Mailbox.CONTENT_URI, Mailbox.CONTENT_PROJECTION,
                    MailboxColumns.ACCOUNT_KEY + '=' + mAccount.mId +
                    AND_FREQUENCY_PING_PUSH_AND_NOT_ACCOUNT_MAILBOX, null, null);
            if (c == null) throw new ProviderUnavailableException();
            notReadyMailboxes.clear();
            readyMailboxes.clear();
            // Look for an inbox, and remember its id
            if (inboxId == -1) {
                inboxId = Mailbox.findMailboxOfType(mContext, mAccount.mId, Mailbox.TYPE_INBOX);
            }
            try {
                // Loop through our pushed boxes seeing what is available to push
                while (c.moveToNext()) {
                    pushCount++;
                    // Two requirements for push:
                    // 1) ExchangeService tells us the mailbox is syncable (not running/not stopped)
                    // 2) The syncKey isn't "0" (i.e. it's synced at least once)
                    long mailboxId = c.getLong(Mailbox.CONTENT_ID_COLUMN);
                    int pingStatus = ExchangeService.pingStatus(mailboxId);
                    String mailboxName = c.getString(Mailbox.CONTENT_DISPLAY_NAME_COLUMN);
                    if (pingStatus == ExchangeService.PING_STATUS_OK) {
                        String syncKey = c.getString(Mailbox.CONTENT_SYNC_KEY_COLUMN);
                        if ((syncKey == null) || syncKey.equals("0")) {
                            // We can't push until the initial sync is done
                            pushCount--;
                            uninitCount++;
                            continue;
                        }

                        if (canPushCount++ == 0) {
                            // Initialize the Ping command
                            s.start(Tags.PING_PING)
                                .data(Tags.PING_HEARTBEAT_INTERVAL,
                                        Integer.toString(pingHeartbeat))
                                .start(Tags.PING_FOLDERS);
                        }

                        String folderClass = getTargetCollectionClassFromCursor(c);
                        s.start(Tags.PING_FOLDER)
                            .data(Tags.PING_ID, c.getString(Mailbox.CONTENT_SERVER_ID_COLUMN))
                            .data(Tags.PING_CLASS, folderClass)
                            .end();
                        readyMailboxes.add(mailboxName);
                    } else if ((pingStatus == ExchangeService.PING_STATUS_RUNNING) ||
                            (pingStatus == ExchangeService.PING_STATUS_WAITING)) {
                        notReadyMailboxes.add(mailboxName);
                    } else if (pingStatus == ExchangeService.PING_STATUS_UNABLE) {
                        pushCount--;
                        userLog(mailboxName, " in error state; ignore");
                        continue;
                    }
                }
            } finally {
                c.close();
            }

            if (Eas.USER_LOG) {
                if (!notReadyMailboxes.isEmpty()) {
                    userLog("Ping not ready for: " + notReadyMailboxes);
                }
                if (!readyMailboxes.isEmpty()) {
                    userLog("Ping ready for: " + readyMailboxes);
                }
            }

            // If we've waited 10 seconds or more, just ping with whatever boxes are ready
            // But use a shorter than normal heartbeat
            boolean forcePing = !notReadyMailboxes.isEmpty() && (pingWaitCount > 5);

            if ((canPushCount > 0) && ((canPushCount == pushCount) || forcePing)) {
                // If all pingable boxes are ready for push, send Ping to the server
                s.end().end().done();
                pingWaitCount = 0;
                mPostReset = false;
                mPostAborted = false;

                // If we've been stopped, this is a good time to return
                if (mStop) return;

                long pingTime = SystemClock.elapsedRealtime();
                try {
                    // Send the ping, wrapped by appropriate timeout/alarm
                    if (forcePing) {
                        userLog("Forcing ping after waiting for all boxes to be ready");
                    }
                    EasResponse resp =
                        sendPing(s.toByteArray(), forcePing ? mPingForceHeartbeat : pingHeartbeat);

                    try {
                        int code = resp.getStatus();
                        userLog("Ping response: ", code);

                        // If we're not allowed to sync (e.g. roaming policy), terminate gracefully
                        // now; otherwise we might start a sync based on the response
                        if (!ExchangeService.canAutoSync(mAccount)) {
                            mStop = true;
                        }

                        // Return immediately if we've been asked to stop during the ping
                        if (mStop) {
                            userLog("Stopping pingLoop");
                            return;
                        }

                        if (code == HttpStatus.SC_OK) {
                            // Make sure to clear out any pending sync errors
                            ExchangeService.removeFromSyncErrorMap(mMailboxId);
                            if (!resp.isEmpty()) {
                                InputStream is = resp.getInputStream();
                                int pingResult = parsePingResult(is, mContentResolver,
                                        pingErrorMap);
                                // if the pingResult was 2, but the pingTime was >= the heartbeat
                                // then allow the heartbeat to increase as if the result was 1
                                long pingLength = (SystemClock.elapsedRealtime() - pingTime) / 1000;

                                // If our ping completed (status = 1), and wasn't forced and we're
                                // not at the maximum, try increasing timeout by two minutes
                                if (!forcePing &&
                                    ((pingResult == PROTOCOL_PING_STATUS_COMPLETED) || 
                                     ((2 == pingResult) && (pingLength >= pingHeartbeat)))) {

                                    if (pingHeartbeat > mPingHighWaterMark) {
                                        mPingHighWaterMark = pingHeartbeat;
                                        userLog("Setting high water mark at: ", mPingHighWaterMark);
                                    }
                                    if ((pingHeartbeat < mPingMaxHeartbeat) &&
                                            !mPingHeartbeatDropped) {
                                        pingHeartbeat += PING_HEARTBEAT_INCREMENT;
                                        if (pingHeartbeat > mPingMaxHeartbeat) {
                                            pingHeartbeat = mPingMaxHeartbeat;
                                        }
                                        userLog("Increase ping heartbeat to ", pingHeartbeat, "s");
                                    }
                                }
                            } else {
                                userLog("Ping returned empty result; throwing IOException");
                                throw new IOException();
                            }
                        } else if (EasResponse.isAuthError(code)) {
                            mExitStatus = EXIT_LOGIN_FAILURE;
                            userLog("Authorization error during Ping: ", code);
                            throw new IOException();
                        } else if (EasResponse.isProvisionError(code)) {
                            userLog("Provisioning required during Ping: ", code);
                            throw new CommandStatusException(CommandStatus.NEEDS_PROVISIONING);
                        }
                    } finally {
                        resp.close();
                    }
                } catch (IOException e) {
                    String message = e.getMessage();
                    // If we get the exception that is indicative of a NAT timeout and if we
                    // haven't yet "fixed" the timeout, back off by two minutes and "fix" it
                    boolean hasMessage = message != null;
                    userLog("IOException runPingLoop: " + (hasMessage ? message : "[no message]"));
                    if (mPostReset) {
                        // Nothing to do in this case; this is ExchangeService telling us to try
                        // another ping.
                    } else if (mPostAborted || isLikelyNatFailure(message)) {
                        long pingLength = SystemClock.elapsedRealtime() - pingTime;
                        if ((pingHeartbeat > mPingMinHeartbeat) &&
                                (pingHeartbeat > mPingHighWaterMark)) {
                            pingHeartbeat -= PING_HEARTBEAT_INCREMENT;
                            mPingHeartbeatDropped = true;
                            if (pingHeartbeat < mPingMinHeartbeat) {
                                pingHeartbeat = mPingMinHeartbeat;
                            }
                            userLog("Decreased ping heartbeat to ", pingHeartbeat, "s");
                        } else if (mPostAborted) {
                            // There's no point in throwing here; this can happen in two cases
                            // 1) An alarm, which indicates minutes without activity; no sense
                            //    backing off
                            // 2) ExchangeService abort, due to sync of mailbox.  Again, we want to
                            //    keep on trying to ping
                            userLog("Ping aborted; retry");
                        } else if (pingLength < 2000) {
                            userLog("Abort or NAT type return < 2 seconds; throwing IOException");
                            throw e;
                        } else {
                            userLog("NAT type IOException");
                        }
                    } else if (hasMessage && message.contains("roken pipe")) {
                        // The "broken pipe" error (uppercase or lowercase "b") seems to be an
                        // internal error, so let's not throw an exception (which leads to delays)
                        // but rather simply run through the loop again
                    } else {
                        throw e;
                    }
                }
            } else if (forcePing) {
                // In this case, there aren't any boxes that are pingable, but there are boxes
                // waiting (for IOExceptions)
                userLog("pingLoop waiting 60s for any pingable boxes");
                sleep(60*SECONDS, true);
            } else if (pushCount > 0) {
                // If we want to Ping, but can't just yet, wait a little bit
                // TODO Change sleep to wait and use notify from ExchangeService when a sync ends
                sleep(2*SECONDS, false);
                pingWaitCount++;
                //userLog("pingLoop waited 2s for: ", (pushCount - canPushCount), " box(es)");
            } else if (uninitCount > 0) {
                // In this case, we're doing an initial sync of at least one mailbox.  Since this
                // is typically a one-time case, I'm ok with trying again every 10 seconds until
                // we're in one of the other possible states.
                userLog("pingLoop waiting for initial sync of ", uninitCount, " box(es)");
                sleep(10*SECONDS, true);
            } else if (inboxId == -1) {
                // In this case, we're still syncing mailboxes, so sleep for only a short time
                sleep(45*SECONDS, true);
            } else {
                // We've got nothing to do, so we'll check again in 20 minutes at which time
                // we'll update the folder list, check for policy changes and/or remote wipe, etc.
                // Let the device sleep in the meantime...
                userLog(ACCOUNT_MAILBOX_SLEEP_TEXT);
                sleep(ACCOUNT_MAILBOX_SLEEP_TIME, true);
            }
        }

        // Save away the current heartbeat
        mPingHeartbeat = pingHeartbeat;
    }

    private void sleep(long ms, boolean runAsleep) {
        if (runAsleep) {
            ExchangeService.runAsleep(mMailboxId, ms+(5*SECONDS));
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // Doesn't matter whether we stop early; it's the thought that counts
        } finally {
            if (runAsleep) {
                ExchangeService.runAwake(mMailboxId);
            }
        }
    }

    private int parsePingResult(InputStream is, ContentResolver cr,
            HashMap<String, Integer> errorMap)
            throws IOException, StaleFolderListException, IllegalHeartbeatException,
                CommandStatusException {
        PingParser pp = new PingParser(is, this);
        if (pp.parse()) {
            // True indicates some mailboxes need syncing...
            // syncList has the serverId's of the mailboxes...
            mBindArguments[0] = Long.toString(mAccount.mId);
            mPingChangeList = pp.getSyncList();
            for (String serverId: mPingChangeList) {
                mBindArguments[1] = serverId;
                Cursor c = cr.query(Mailbox.CONTENT_URI, Mailbox.CONTENT_PROJECTION,
                        WHERE_ACCOUNT_KEY_AND_SERVER_ID, mBindArguments, null);
                if (c == null) throw new ProviderUnavailableException();
                try {
                    if (c.moveToFirst()) {

                        /**
                         * Check the boxes reporting changes to see if there really were any...
                         * We do this because bugs in various Exchange servers can put us into a
                         * looping behavior by continually reporting changes in a mailbox, even when
                         * there aren't any.
                         *
                         * This behavior is seemingly random, and therefore we must code defensively
                         * by backing off of push behavior when it is detected.
                         *
                         * One known cause, on certain Exchange 2003 servers, is acknowledged by
                         * Microsoft, and the server hotfix for this case can be found at
                         * http://support.microsoft.com/kb/923282
                         */

                        // Check the status of the last sync
                        String status = c.getString(Mailbox.CONTENT_SYNC_STATUS_COLUMN);
                        int type = ExchangeService.getStatusType(status);
                        // This check should always be true...
                        if (type == ExchangeService.SYNC_PING) {
                            int changeCount = ExchangeService.getStatusChangeCount(status);
                            if (changeCount > 0) {
                                errorMap.remove(serverId);
                            } else if (changeCount == 0) {
                                // This means that a ping reported changes in error; we keep a count
                                // of consecutive errors of this kind
                                String name = c.getString(Mailbox.CONTENT_DISPLAY_NAME_COLUMN);
                                Integer failures = errorMap.get(serverId);
                                if (failures == null) {
                                    userLog("Last ping reported changes in error for: ", name);
                                    errorMap.put(serverId, 1);
                                } else if (failures > MAX_PING_FAILURES) {
                                    // We'll back off of push for this box
                                    pushFallback(c.getLong(Mailbox.CONTENT_ID_COLUMN));
                                    continue;
                                } else {
                                    userLog("Last ping reported changes in error for: ", name);
                                    errorMap.put(serverId, failures + 1);
                                }
                            }
                        }

                        // If there were no problems with previous sync, we'll start another one
                        ExchangeService.startManualSync(c.getLong(Mailbox.CONTENT_ID_COLUMN),
                                ExchangeService.SYNC_PING, null);
                    }
                } finally {
                    c.close();
                }
            }
        }
        return pp.getSyncStatus();
    }

    /**
     * Common code to sync E+PIM data
     *
     * @param target an EasMailbox, EasContacts, or EasCalendar object
     */
    public void sync(AbstractSyncAdapter target) throws IOException {
        Mailbox mailbox = target.mMailbox;

        boolean moreAvailable = true;
        int loopingCount = 0;
        while (!mStop && (moreAvailable || hasPendingRequests())) {
            // If we have no connectivity, just exit cleanly. ExchangeService will start us up again
            // when connectivity has returned
            if (!hasConnectivity()) {
                userLog("No connectivity in sync; finishing sync");
                mExitStatus = EXIT_DONE;
                return;
            }

            // Every time through the loop we check to see if we're still syncable
            if (!target.isSyncable()) {
                mExitStatus = EXIT_DONE;
                return;
            }

            // Now, handle various requests
            while (true) {
                Request req = null;

                if (mRequestQueue.isEmpty()) {
                    break;
                } else {
                    req = mRequestQueue.peek();
                }

                // Our two request types are PartRequest (loading attachment) and
                // MeetingResponseRequest (respond to a meeting request)
                if (req instanceof PartRequest) {
                    TrafficStats.setThreadStatsTag(
                            TrafficFlags.getAttachmentFlags(mContext, mAccount));
                    new AttachmentLoader(this, (PartRequest)req).loadAttachment();
                    TrafficStats.setThreadStatsTag(TrafficFlags.getSyncFlags(mContext, mAccount));
                } else if (req instanceof MeetingResponseRequest) {
                    sendMeetingResponse((MeetingResponseRequest)req);
                } else if (req instanceof MessageMoveRequest) {
                    messageMoveRequest((MessageMoveRequest)req);
                }

                // If there's an exception handling the request, we'll throw it
                // Otherwise, we remove the request
                mRequestQueue.remove();
            }

            // Don't sync if we've got nothing to do
            if (!moreAvailable) {
                continue;
            }

            Serializer s = new Serializer();

            String className = target.getCollectionName();
            String syncKey = target.getSyncKey();
            userLog("sync, sending ", className, " syncKey: ", syncKey);
            s.start(Tags.SYNC_SYNC)
                .start(Tags.SYNC_COLLECTIONS)
                .start(Tags.SYNC_COLLECTION);
            // The "Class" element is removed in EAS 12.1 and later versions
            if (mProtocolVersionDouble < Eas.SUPPORTED_PROTOCOL_EX2007_SP1_DOUBLE) {
                s.data(Tags.SYNC_CLASS, className);
            }
            s.data(Tags.SYNC_SYNC_KEY, syncKey)
                .data(Tags.SYNC_COLLECTION_ID, mailbox.mServerId);

            // Start with the default timeout
            int timeout = COMMAND_TIMEOUT;
            if (!syncKey.equals("0")) {
                // EAS doesn't allow GetChanges in an initial sync; sending other options
                // appears to cause the server to delay its response in some cases, and this delay
                // can be long enough to result in an IOException and total failure to sync.
                // Therefore, we don't send any options with the initial sync.
                // Set the truncation amount, body preference, lookback, etc.
                target.sendSyncOptions(mProtocolVersionDouble, s);
            } else {
                // Use enormous timeout for initial sync, which empirically can take a while longer
                timeout = 120*SECONDS;
            }
            // Send our changes up to the server
            if (mUpsyncFailed) {
                if (Eas.USER_LOG) {
                    Log.d(TAG, "Inhibiting upsync this cycle");
                }
            } else {
                target.sendLocalChanges(s);
            }

            s.end().end().end().done();
            EasResponse resp = sendHttpClientPost("Sync", new ByteArrayEntity(s.toByteArray()),
                    timeout);
            try {
                int code = resp.getStatus();
                if (code == HttpStatus.SC_OK) {
                    // In EAS 12.1, we can get "empty" sync responses, which indicate that there are
                    // no changes in the mailbox; handle that case here
                    // There are two cases here; if we get back a compressed stream (GZIP), we won't
                    // know until we try to parse it (and generate an EmptyStreamException). If we
                    // get uncompressed data, the response will be empty (i.e. have zero length)
                    boolean emptyStream = false;
                    if (!resp.isEmpty()) {
                        InputStream is = resp.getInputStream();
                        try {
                            moreAvailable = target.parse(is);
                            // If we inhibited upsync, we need yet another sync
                            if (mUpsyncFailed) {
                                moreAvailable = true;
                            }

                            if (target.isLooping()) {
                                loopingCount++;
                                userLog("** Looping: " + loopingCount);
                                // After the maximum number of loops, we'll set moreAvailable to
                                // false and allow the sync loop to terminate
                                if (moreAvailable && (loopingCount > MAX_LOOPING_COUNT)) {
                                    userLog("** Looping force stopped");
                                    moreAvailable = false;
                                }
                            } else {
                                loopingCount = 0;
                            }

                            // Cleanup clears out the updated/deleted tables, and we don't want to
                            // do that if our upsync failed; clear the flag otherwise
                            if (!mUpsyncFailed) {
                                target.cleanup();
                            } else {
                                mUpsyncFailed = false;
                            }
                        } catch (EmptyStreamException e) {
                            userLog("Empty stream detected in GZIP response");
                            emptyStream = true;
                        } catch (CommandStatusException e) {
                            // TODO 14.1
                            int status = e.mStatus;
                            if (CommandStatus.isNeedsProvisioning(status)) {
                                mExitStatus = EXIT_SECURITY_FAILURE;
                            } else if (CommandStatus.isDeniedAccess(status)) {
                                mExitStatus = EXIT_ACCESS_DENIED;
                            } else if (CommandStatus.isTransientError(status)) {
                                mExitStatus = EXIT_IO_ERROR;
                            } else {
                                mExitStatus = EXIT_EXCEPTION;
                            }
                            return;
                        }
                    } else {
                        emptyStream = true;
                    }

                    if (emptyStream) {
                        // If this happens, exit cleanly, and change the interval from push to ping
                        // if necessary
                        userLog("Empty sync response; finishing");
                        if (mMailbox.mSyncInterval == Mailbox.CHECK_INTERVAL_PUSH) {
                            userLog("Changing mailbox from push to ping");
                            ContentValues cv = new ContentValues();
                            cv.put(Mailbox.SYNC_INTERVAL, Mailbox.CHECK_INTERVAL_PING);
                            mContentResolver.update(
                                    ContentUris.withAppendedId(Mailbox.CONTENT_URI, mMailbox.mId),
                                    cv, null, null);
                        }
                        if (mRequestQueue.isEmpty()) {
                            mExitStatus = EXIT_DONE;
                            return;
                        } else {
                            continue;
                        }
                    }
                } else {
                    userLog("Sync response error: ", code);
                    if (EasResponse.isProvisionError(code)) {
                        mExitStatus = EXIT_SECURITY_FAILURE;
                    } else if (EasResponse.isAuthError(code)) {
                        mExitStatus = EXIT_LOGIN_FAILURE;
                    } else {
                        mExitStatus = EXIT_IO_ERROR;
                    }
                    return;
                }
            } finally {
                resp.close();
            }
        }
        mExitStatus = EXIT_DONE;
    }

    protected boolean setupService() {
        synchronized(getSynchronizer()) {
            mThread = Thread.currentThread();
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            TAG = mThread.getName();
        }
        // Make sure account and mailbox are always the latest from the database
        mAccount = Account.restoreAccountWithId(mContext, mAccount.mId);
        if (mAccount == null) return false;
        mMailbox = Mailbox.restoreMailboxWithId(mContext, mMailbox.mId);
        if (mMailbox == null) return false;
        HostAuth ha = HostAuth.restoreHostAuthWithId(mContext, mAccount.mHostAuthKeyRecv);
        if (ha == null) return false;
        mHostAddress = ha.mAddress;
        mUserName = ha.mLogin;
        mPassword = ha.mPassword;

        try {
            setConnectionParameters(
                    (ha.mFlags & HostAuth.FLAG_SSL) != 0,
                    (ha.mFlags & HostAuth.FLAG_TRUST_ALL) != 0,
                    ha.mClientCertAlias);
        } catch (CertificateException e) {
            userLog("Couldn't retrieve certificate for connection");
            try {
                ExchangeService.callback().syncMailboxStatus(mMailboxId,
                        EmailServiceStatus.CLIENT_CERTIFICATE_ERROR, 0);
            } catch (RemoteException e1) {
                // Don't care if this fails.
            }
            return false;
        }

        // Set up our protocol version from the Account
        mProtocolVersion = mAccount.mProtocolVersion;
        // If it hasn't been set up, start with default version
        if (mProtocolVersion == null) {
            mProtocolVersion = Eas.DEFAULT_PROTOCOL_VERSION;
        }
        mProtocolVersionDouble = Eas.getProtocolVersionDouble(mProtocolVersion);

        // Do checks to address historical policy sets.
        Policy policy = Policy.restorePolicyWithId(mContext, mAccount.mPolicyKey);
        if ((policy != null) && policy.mRequireEncryptionExternal) {
            // External storage encryption is not supported at this time. In a previous release,
            // prior to the system supporting true removable storage on Honeycomb, we accepted
            // this since we emulated external storage on partitions that could be encrypted.
            // If that was set before, we must clear it out now that the system supports true
            // removable storage (which can't be encrypted).
            resetSecurityPolicies();
        }
        return true;
    }

    /**
     * Clears out the security policies associated with the account, forcing a provision error
     * and a re-sync of the policy information for the account.
     */
    private void resetSecurityPolicies() {
        ContentValues cv = new ContentValues();
        cv.put(AccountColumns.SECURITY_FLAGS, 0);
        cv.putNull(AccountColumns.SECURITY_SYNC_KEY);
        long accountId = mAccount.mId;
        mContentResolver.update(ContentUris.withAppendedId(
                Account.CONTENT_URI, accountId), cv, null, null);
        SecurityPolicyDelegate.policiesRequired(mContext, accountId);
    }

    @Override
    public void run() {
        try {
            // Make sure account and mailbox are still valid
            if (!setupService()) return;
            // If we've been stopped, we're done
            if (mStop) return;

            // Whether or not we're the account mailbox
            try {
                mDeviceId = ExchangeService.getDeviceId(mContext);
                int trafficFlags = TrafficFlags.getSyncFlags(mContext, mAccount);
                if ((mMailbox == null) || (mAccount == null)) {
                    return;
                } else if (mMailbox.mType == Mailbox.TYPE_EAS_ACCOUNT_MAILBOX) {
                    TrafficStats.setThreadStatsTag(trafficFlags | TrafficFlags.DATA_EMAIL);
                    runAccountMailbox();
                } else {
                    AbstractSyncAdapter target;
                    if (mMailbox.mType == Mailbox.TYPE_CONTACTS) {
                        TrafficStats.setThreadStatsTag(trafficFlags | TrafficFlags.DATA_CONTACTS);
                        target = new ContactsSyncAdapter( this);
                    } else if (mMailbox.mType == Mailbox.TYPE_CALENDAR) {
                        TrafficStats.setThreadStatsTag(trafficFlags | TrafficFlags.DATA_CALENDAR);
                        target = new CalendarSyncAdapter(this);
                    } else {
                        TrafficStats.setThreadStatsTag(trafficFlags | TrafficFlags.DATA_EMAIL);
                        target = new EmailSyncAdapter(this);
                    }
                    // We loop because someone might have put a request in while we were syncing
                    // and we've missed that opportunity...
                    do {
                        if (mRequestTime != 0) {
                            userLog("Looping for user request...");
                            mRequestTime = 0;
                        }
                        String syncKey = target.getSyncKey();
                        if (mSyncReason >= ExchangeService.SYNC_CALLBACK_START ||
                                "0".equals(syncKey)) {
                            try {
                                ExchangeService.callback().syncMailboxStatus(mMailboxId,
                                        EmailServiceStatus.IN_PROGRESS, 0);
                            } catch (RemoteException e1) {
                                // Don't care if this fails
                            }
                        }
                        sync(target);
                    } while (mRequestTime != 0);
                }
            } catch (EasAuthenticationException e) {
                userLog("Caught authentication error");
                mExitStatus = EXIT_LOGIN_FAILURE;
            } catch (IOException e) {
                String message = e.getMessage();
                userLog("Caught IOException: ", (message == null) ? "No message" : message);
                mExitStatus = EXIT_IO_ERROR;
            } catch (Exception e) {
                userLog("Uncaught exception in EasSyncService", e);
            } finally {
                int status;
                ExchangeService.done(this);
                if (!mStop) {
                    userLog("Sync finished");
                    switch (mExitStatus) {
                        case EXIT_IO_ERROR:
                            status = EmailServiceStatus.CONNECTION_ERROR;
                            break;
                        case EXIT_DONE:
                            status = EmailServiceStatus.SUCCESS;
                            ContentValues cv = new ContentValues();
                            cv.put(Mailbox.SYNC_TIME, System.currentTimeMillis());
                            String s = "S" + mSyncReason + ':' + status + ':' + mChangeCount;
                            cv.put(Mailbox.SYNC_STATUS, s);
                            mContentResolver.update(ContentUris.withAppendedId(Mailbox.CONTENT_URI,
                                    mMailboxId), cv, null, null);
                            break;
                        case EXIT_LOGIN_FAILURE:
                            status = EmailServiceStatus.LOGIN_FAILED;
                            break;
                        case EXIT_SECURITY_FAILURE:
                            status = EmailServiceStatus.SECURITY_FAILURE;
                            // Ask for a new folder list. This should wake up the account mailbox; a
                            // security error in account mailbox should start provisioning
                            ExchangeService.reloadFolderList(mContext, mAccount.mId, true);
                            break;
                        case EXIT_ACCESS_DENIED:
                            status = EmailServiceStatus.ACCESS_DENIED;
                            break;
                        default:
                            status = EmailServiceStatus.REMOTE_EXCEPTION;
                            errorLog("Sync ended due to an exception.");
                            break;
                    }
                } else {
                    userLog("Stopped sync finished.");
                    status = EmailServiceStatus.SUCCESS;
                }

                // Send a callback (doesn't matter how the sync was started)
                try {
                    // Unless the user specifically asked for a sync, we don't want to report
                    // connection issues, as they are likely to be transient.  In this case, we
                    // simply report success, so that the progress indicator terminates without
                    // putting up an error banner
                    if (mSyncReason != ExchangeService.SYNC_UI_REQUEST &&
                            status == EmailServiceStatus.CONNECTION_ERROR) {
                        status = EmailServiceStatus.SUCCESS;
                    }
                    ExchangeService.callback().syncMailboxStatus(mMailboxId, status, 0);
                } catch (RemoteException e1) {
                    // Don't care if this fails
                }

                // Make sure ExchangeService knows about this
                ExchangeService.kick("sync finished");
            }
        } catch (ProviderUnavailableException e) {
            Log.e(TAG, "EmailProvider unavailable; sync ended prematurely");
        }
    }
}
