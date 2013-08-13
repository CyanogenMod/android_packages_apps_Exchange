package com.android.exchange.service;

import android.content.ContentValues;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;

import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.PolicyServiceProxy;
import com.android.exchange.CommandStatusException;
import com.android.exchange.CommandStatusException.CommandStatus;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;
import com.android.exchange.adapter.FolderSyncParser;
import com.android.exchange.adapter.ProvisionParser;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.SettingsParser;
import com.android.exchange.adapter.Tags;
import com.android.mail.utils.LogUtils;
import com.google.common.collect.Sets;

import org.apache.http.Header;
import org.apache.http.HttpStatus;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.HashSet;

/**
 * Base class to perform the various requests needed to validate or sync an account.
 * "Account sync" consists primarily of syncing all folders for this account, but also includes
 * handling the protocol version, security policies, and other authentication issues.
 */
public class EasAccountValidator extends EasServerConnection {
    /** Logging tag. */
    private static final String TAG = "EasAccountValidator";

    /**
     * The maximum number of redirects we permit before giving up. Ideally the server should not
     * send us on a chase like this, so this is here to prevent infinite recursion in a bad case.
     */
    private static final int MAX_REDIRECTS = 3;

    public static final String EAS_12_POLICY_TYPE = "MS-EAS-Provisioning-WBXML";
    public static final String EAS_2_POLICY_TYPE = "MS-WAP-Provisioning-XML";

    /** The EAS protocol Provision status for "we implement all of the policies" */
    private static final String PROVISION_STATUS_OK = "1";
    /** The EAS protocol Provision status meaning "we partially implement the policies" */
    private static final String PROVISION_STATUS_PARTIAL = "2";

    /** Set of Exchange protocol versions we understand. */
    private static final HashSet<String> SUPPORTED_PROTOCOL_VERSIONS = Sets.newHashSet(
            Eas.SUPPORTED_PROTOCOL_EX2003,
            Eas.SUPPORTED_PROTOCOL_EX2007, Eas.SUPPORTED_PROTOCOL_EX2007_SP1,
            Eas.SUPPORTED_PROTOCOL_EX2010, Eas.SUPPORTED_PROTOCOL_EX2010_SP1);

    /** The number of times we've been redirected so far. */
    private int mRedirectCount;

    /**
     * An exception type used exclusively within this class -- some sub-functions throw this to
     * signal that a response from the Exchange server indicated that we should be using a different
     * host. This exception is caught
     */
    private static class RedirectException extends Exception {
        public final String mRedirectAddress;
        public RedirectException(final EasResponse resp) {
            mRedirectAddress = resp.getRedirectAddress();
        }
    }

    private EasAccountValidator(final Context context, final Account account,
            final HostAuth hostAuth) {
        super(context, account, hostAuth);
        mRedirectCount = 0;
    }

    protected EasAccountValidator(final Context context, final Account account) {
        this(context, account, HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv));
    }

    public EasAccountValidator(final Context context, final HostAuth hostAuth) {
        this(context, new Account(), hostAuth);
        mAccount.mEmailAddress = hostAuth.mLogin;
    }

    /**
     * Update our account's protocol version based on the server's supported versions.
     * @param versionHeader The {@link Header} for the server's supported versions.
     * @return Whether we found a suitable protocol version.
     */
    private boolean setProtocolVersion(final Header versionHeader) {
        // The string is a comma separated list of EAS versions in ascending order
        // e.g. 1.0,2.0,2.5,12.0,12.1,14.0,14.1
        final String supportedVersions = versionHeader.getValue();
        LogUtils.i(TAG, "Server supports versions: %s", supportedVersions);
        final String[] supportedVersionsArray = supportedVersions.split(",");
        // Find the most recent version we support
        String newProtocolVersion = null;
        for (final String version: supportedVersionsArray) {
            if (SUPPORTED_PROTOCOL_VERSIONS.contains(version)) {
                newProtocolVersion = version;
            }
        }
        if (newProtocolVersion == null) {
            LogUtils.w(TAG, "No supported EAS versions: %s", supportedVersions);
            // TODO: if mAccount.isSaved(), we should delete the account.
            return false;
        }

        // Update our account with the new protocol version.
        final boolean protocolChanged = !newProtocolVersion.equals(mAccount.mProtocolVersion);
        if (protocolChanged) {
            mAccount.mProtocolVersion = newProtocolVersion;
            setProtocolVersion(newProtocolVersion);
        }

        // Fixup search flags, if they're not set.
        final boolean flagsChanged;
        if (getProtocolVersion() >= 12.0) {
            int oldFlags = mAccount.mFlags;
            mAccount.mFlags |= Account.FLAGS_SUPPORTS_GLOBAL_SEARCH + Account.FLAGS_SUPPORTS_SEARCH;
            flagsChanged = (oldFlags != mAccount.mFlags);
        } else {
            flagsChanged = false;
        }

        // Write account back to DB if needed.
        if ((protocolChanged || flagsChanged) && mAccount.isSaved()) {
            final ContentValues cv = new ContentValues();
            if (protocolChanged) {
                cv.put(AccountColumns.PROTOCOL_VERSION, mAccount.mProtocolVersion);
            }
            if (flagsChanged) {
                cv.put(AccountColumns.FLAGS, mAccount.mFlags);
            }
            mAccount.update(mContext, cv);
        }
        return true;
    }

    /**
     * Make an OPTIONS request to determine the protocol version to use, and update our account to
     * use the most recent protocol that both we and the server understand.
     * @return A status code for getting the protocol version. If NO_ERROR, then mAccount will be
     *     updated to the best version we mutually understand.
     */
    private int getServerProtocolVersion() throws IOException, RedirectException {
        final EasResponse resp = sendHttpClientOptions();
        try {
            final int code = resp.getStatus();
            LogUtils.d(TAG, "Validation (OPTIONS) response: %d", code);
            if (code == HttpStatus.SC_OK) {
                // No exception means successful validation
                final Header commands = resp.getHeader("MS-ASProtocolCommands");
                final Header versions = resp.getHeader("ms-asprotocolversions");
                final boolean hasProtocolVersion;
                if (commands == null || versions == null) {
                    LogUtils.e(TAG, "OPTIONS response without commands or versions");
                    hasProtocolVersion = false;
                } else {
                    hasProtocolVersion = setProtocolVersion(versions);
                }
                if (!hasProtocolVersion) {
                    return MessagingException.PROTOCOL_VERSION_UNSUPPORTED;
                }
                return MessagingException.NO_ERROR;
            }
            if (resp.isAuthError()) {
                return resp.isMissingCertificate()
                        ? MessagingException.CLIENT_CERTIFICATE_REQUIRED
                        : MessagingException.AUTHENTICATION_FAILED;
            }
            if (code == HttpStatus.SC_INTERNAL_SERVER_ERROR) {
                return MessagingException.AUTHENTICATION_FAILED_OR_SERVER_ERROR;
            }
            if (resp.isRedirectError()) {
                throw new RedirectException(resp);
            }
            // TODO Need to catch other kinds of errors (e.g. policy) For now, report code.
            LogUtils.w(TAG, "Validation failed, reporting I/O error: %d", code);
            return MessagingException.IOERROR;
        } finally {
            resp.close();
        }
    }

    /**
     * Send a FolderSync request and handle the response. Depending on isStatusOnly, this either
     * simply verifies that the response is valid, or it will also actually sync the folders.
     * @param isStatusOnly If true, this is only a validation, otherwise it's a full sync.
     * @return A status code indicating the result of this check.
     * @throws IOException
     * @throws CommandStatusException
     * @throws RedirectException
     */
    private int doFolderSync(final boolean isStatusOnly)
            throws IOException, CommandStatusException, RedirectException {
        LogUtils.i(TAG, "FolderSync (%s) for %s, %s, ssl = %s", isStatusOnly ? "validate" : "sync",
                mHostAuth.mAddress, mHostAuth.mLogin, mHostAuth.shouldUseSsl() ? "1" : "0");

        // Send "0" as the sync key for new accounts; otherwise, use the current key
        final String syncKey = mAccount.mSyncKey != null ? mAccount.mSyncKey : "0";
        final Serializer s = new Serializer();
        s.start(Tags.FOLDER_FOLDER_SYNC).start(Tags.FOLDER_SYNC_KEY).text(syncKey)
            .end().end().done();
        final EasResponse resp = sendHttpClientPost("FolderSync", s.toByteArray());
        final int resultCode;
        try {
            final int code = resp.getStatus();
            LogUtils.d(TAG, "FolderSync response: %d", code);
            if (code == HttpStatus.SC_OK) {
                // We need to parse the result to see if we've got a provisioning issue
                // (EAS 14.0 only)
                if (!resp.isEmpty()) {
                    new FolderSyncParser(mContext, mContext.getContentResolver(),
                            resp.getInputStream(), mAccount, isStatusOnly).parse();
                }
                resultCode = MessagingException.NO_ERROR;
            } else if (code == HttpStatus.SC_FORBIDDEN) {
                // For validation only, we take 403 as ACCESS_DENIED (the account isn't
                // authorized, possibly due to device type)
                resultCode = MessagingException.ACCESS_DENIED;
            } else if (resp.isProvisionError()) {
                // The device needs to have security policies enforced
                throw new CommandStatusException(CommandStatus.NEEDS_PROVISIONING);
            } else if (code == HttpStatus.SC_NOT_FOUND) {
                // We get a 404 from OWA addresses (which are NOT EAS addresses)
                resultCode = MessagingException.PROTOCOL_VERSION_UNSUPPORTED;
            } else if (code == HttpStatus.SC_UNAUTHORIZED) {
                resultCode = resp.isMissingCertificate()
                        ? MessagingException.CLIENT_CERTIFICATE_REQUIRED
                        : MessagingException.AUTHENTICATION_FAILED;
            } else if (resp.isRedirectError()) {
                throw new RedirectException(resp);
            } else {
                resultCode = MessagingException.UNSPECIFIED_EXCEPTION;
            }
        } finally {
            resp.close();
        }
        return resultCode;
    }

    /**
     * Send a Settings request to the server and process the response.
     * @return Whether the request succeeded.
     * @throws IOException
     */
    private boolean sendSettings() throws IOException {
        final Serializer s = new Serializer();
        s.start(Tags.SETTINGS_SETTINGS);
        s.start(Tags.SETTINGS_DEVICE_INFORMATION).start(Tags.SETTINGS_SET);
        s.data(Tags.SETTINGS_MODEL, Build.MODEL);
        s.data(Tags.SETTINGS_OS, "Android " + Build.VERSION.RELEASE);
        s.data(Tags.SETTINGS_USER_AGENT, USER_AGENT);
        s.end().end().end().done(); // SETTINGS_SET, SETTINGS_DEVICE_INFORMATION, SETTINGS_SETTINGS
        final EasResponse resp = sendHttpClientPost("Settings", s.toByteArray());
        try {
            if (resp.getStatus() == HttpStatus.SC_OK) {
                return new SettingsParser(resp.getInputStream()).parse();
            }
        } finally {
            resp.close();
        }
        // On failures, simply return false
        return false;
    }

    private String getPolicyType() {
        return (getProtocolVersion() >= Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) ?
                EAS_12_POLICY_TYPE : EAS_2_POLICY_TYPE;
    }

    /**
     * Acknowledge a remote wipe command from the server.
     * @param tempKey The security key of our current (temporary) policy.
     * @throws IOException
     */
    private void acknowledgeRemoteWipe(final String tempKey)
            throws IOException {
        acknowledgeProvisionImpl(tempKey, PROVISION_STATUS_OK, true);
    }

    /**
     * Acknowledge that we've set the required policy.
     * @param tempKey The security key of our current (temporary) policy.
     * @param result One of {@link #PROVISION_STATUS_OK} or {@link #PROVISION_STATUS_PARTIAL}
     *               indicating how well we enforce the policy.
     * @return A new security sync key, or null on failure.
     * @throws IOException
     */
    private String acknowledgeProvision(final String tempKey, final String result)
            throws IOException {
        return acknowledgeProvisionImpl(tempKey, result, false);
    }

    /**
     * Common function doing the work for acknowledging remote wipes or provisioning.
     * @param tempKey The security key of our current (temporary) policy.
     * @param status One of {@link #PROVISION_STATUS_OK} or {@link #PROVISION_STATUS_PARTIAL}
     *               indicating how well we enforce the policy.
     * @param remoteWipe Whether this is a remote wipe.
     * @return A new security sync key, or null on failure.
     * @throws IOException
     */
    private String acknowledgeProvisionImpl(final String tempKey, final String status,
            final boolean remoteWipe) throws IOException {
        final Serializer s = new Serializer();
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
            if (resp.getStatus() == HttpStatus.SC_OK) {
                final ProvisionParser pp = new ProvisionParser(mContext, resp.getInputStream());
                if (pp.parse()) {
                    // Return the final policy key from the ProvisionParser
                    final String result =
                            (pp.getSecuritySyncKey() == null) ? "failed" : "confirmed";
                    LogUtils.i(TAG, "Provision %s for %s set", result,
                            PROVISION_STATUS_PARTIAL.equals(status) ? "PART" : "FULL");
                    return pp.getSecuritySyncKey();
                }
            }
        } finally {
            resp.close();
        }
        // On failures, log issue and return null
        LogUtils.i(TAG, "Provisioning failed for %s set",
                PROVISION_STATUS_PARTIAL.equals(status) ? "PART" : "FULL");
        return null;
    }

    /**
     * Send an Exchange Provision request, and process the response to see if we can handle the
     * provisioning requirements returned by the server.
     * @return A {@link ProvisionParser} for the response, or null if we can't handle it.
     * @throws IOException
     */
    private ProvisionParser canProvision() throws IOException {
        final Serializer s = new Serializer();
        s.start(Tags.PROVISION_PROVISION);
        if (getProtocolVersion() >= Eas.SUPPORTED_PROTOCOL_EX2010_SP1_DOUBLE) {
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
        s.start(Tags.PROVISION_POLICY);
        s.data(Tags.PROVISION_POLICY_TYPE, getPolicyType());
        s.end().end().end().done(); // PROVISION_POLICY, PROVISION_POLICIES, PROVISION_PROVISION
        final EasResponse resp = sendHttpClientPost("Provision", s.toByteArray());
        try {
            int code = resp.getStatus();
            if (code == HttpStatus.SC_OK) {
                final ProvisionParser pp = new ProvisionParser(mContext, resp.getInputStream());
                if (pp.parse()) {
                    // The PolicySet in the ProvisionParser will have the requirements for all KNOWN
                    // policies.  If others are required, hasSupportablePolicySet will be false
                    if (pp.hasSupportablePolicySet() &&
                            getProtocolVersion() == Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE) {
                        // In EAS 14.0, we need the final security key in order to use the settings
                        // command
                        final String policyKey = acknowledgeProvision(pp.getSecuritySyncKey(),
                                PROVISION_STATUS_OK);
                        if (policyKey != null) {
                            pp.setSecuritySyncKey(policyKey);
                        }
                    } else if (!pp.hasSupportablePolicySet())  {
                        // Try to acknowledge using the "partial" status (i.e. we can partially
                        // accommodate the required policies).  The server will agree to this if the
                        // "allow non-provisionable devices" setting is enabled on the server
                        LogUtils.i(TAG, "PolicySet is NOT fully supportable");
                        if (acknowledgeProvision(pp.getSecuritySyncKey(),
                                PROVISION_STATUS_PARTIAL) != null) {
                            // The server's ok with our inability to support policies, so we'll
                            // clear them
                            pp.clearUnsupportablePolicies();
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
     * Process the provisioning requirements that's returned by the server in response to a
     * Provision request.
     * @param pp  A {@link ProvisionParser} for the server response to the Provision request.
     * @return Whether we successfully handled the provisioning requirements.
     * @throws IOException
     */
    private boolean tryProvision(final ProvisionParser pp) throws IOException {
        if (pp == null) return false;
        // Get the policies from ProvisionParser
        final Policy policy = pp.getPolicy();
        final Policy oldPolicy;
        // Grab the old policy (if any)
        if (mAccount.mPolicyKey > 0) {
            oldPolicy = Policy.restorePolicyWithId(mContext, mAccount.mPolicyKey);
        } else {
            oldPolicy = null;
        }
        // Update the account with a null policyKey (the key we've gotten is
        // temporary and cannot be used for syncing)
        PolicyServiceProxy.setAccountPolicy(mContext, mAccount.mId, policy, null);
        // Make sure mAccount is current (with latest policy key)
        mAccount.refresh(mContext);
        if (pp.getRemoteWipe()) {
            // We've gotten a remote wipe command
            LogUtils.i(TAG, "!!! Remote wipe request received");
            // Start by setting the account to security hold
            PolicyServiceProxy.setAccountHoldFlag(mContext, mAccount, true);

            // First, we've got to acknowledge it, but wrap the wipe in try/catch so that
            // we wipe the device regardless of any errors in acknowledgment
            try {
                LogUtils.i(TAG, "!!! Acknowledging remote wipe to server");
                acknowledgeRemoteWipe(pp.getSecuritySyncKey());
            } catch (Exception e) {
                // Because remote wipe is such a high priority task, we don't want to
                // circumvent it if there's an exception in acknowledgment
            }
            // Then, tell SecurityPolicy to wipe the device
            LogUtils.i(TAG, "!!! Executing remote wipe");
            PolicyServiceProxy.remoteWipe(mContext);
            return false;
        } else if (pp.hasSupportablePolicySet() && PolicyServiceProxy.isActive(mContext, policy)) {
            // See if the required policies are in force; if they are, acknowledge the policies
            // to the server and get the final policy key
            // NOTE: For EAS 14.0, we already have the acknowledgment in the ProvisionParser
            String securitySyncKey;
            if (getProtocolVersion() == Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE) {
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
                PolicyServiceProxy.setAccountPolicy(mContext, mAccount.mId, policy,
                        securitySyncKey);
                return true;
            }
        }
        return false;
    }

    /**
     * Do the heavy lifting for validation and sync:
     * - HTTP OPTIONS request to get protocol version from the server, if we don't already have it.
     * - Exchange FolderSync request to get the folder info.
     * (And exception handling for those operations.)
     * Validation differs from sync in four ways:
     * - Validation registers the client cert.
     * - Validation doesn't save the FolderSync results.
     * - Validation doesn't attempt to set device policies.
     * - Validation must populate a bundle with the results of the validation.
     * @param bundle If this is a validation call, this will be non-null, and this function will
     *               write the results to it (specifically it writes
     *               {@link EmailServiceProxy#VALIDATE_BUNDLE_RESULT_CODE},
     *               {@link EmailServiceProxy#VALIDATE_BUNDLE_PROTOCOL_VERSION}, and
     *               {@link EmailServiceProxy#VALIDATE_BUNDLE_POLICY_SET} (when there's a policy to
     *               be had).
     *               If this is a sync call, bundle will be null.
     *               This function also uses the null/not null status to differentiate behavior in
     *               the few places where validation and sync don't do the same thing.
     */
    protected void doValidationOrSync(final Bundle bundle) {
        LogUtils.i(TAG, "Performing %s: %s, %s, ssl = %s", bundle != null ? "validation" : "sync",
                mHostAuth.mAddress, mHostAuth.mLogin, mHostAuth.shouldUseSsl() ? "1" : "0");

        if (bundle != null) {
            if (mHostAuth.mClientCertAlias != null) {
                try {
                    getClientConnectionManager().registerClientCert(mContext, mHostAuth);
                } catch (final CertificateException e) {
                    // The client certificate the user specified is invalid/inaccessible.
                    bundle.putInt(EmailServiceProxy.VALIDATE_BUNDLE_RESULT_CODE,
                            MessagingException.CLIENT_CERTIFICATE_ERROR);
                    return;
                }
            }
        }

        int resultCode;

        // Need a nested try here because the provisioning exception handler can throw IOException.
        try {
            try {
                // TODO: also want to check protocol version at least once in a while after setup.
                if (mAccount.mProtocolVersion == null) {
                    final int optionsResult = getServerProtocolVersion();
                    if (optionsResult != MessagingException.NO_ERROR) {
                        if (bundle != null) {
                            bundle.putInt(EmailServiceProxy.VALIDATE_BUNDLE_RESULT_CODE,
                                    optionsResult);
                        }
                        return;
                    }
                    if (bundle != null) {
                        bundle.putString(EmailServiceProxy.VALIDATE_BUNDLE_PROTOCOL_VERSION,
                                mAccount.mProtocolVersion);
                    }
                }
                resultCode = doFolderSync(bundle != null);
            } catch (final CommandStatusException e) {
                final int status = e.mStatus;
                if (CommandStatus.isNeedsProvisioning(status)) {
                    // Get the policies and see if we are able to support them
                    final ProvisionParser pp = canProvision();
                    if (pp != null && pp.hasSupportablePolicySet()) {
                        // Set the proper result code and save the PolicySet in our Bundle
                        if (bundle != null) {
                            resultCode = MessagingException.SECURITY_POLICIES_REQUIRED;
                            bundle.putParcelable(EmailServiceProxy.VALIDATE_BUNDLE_POLICY_SET,
                                    pp.getPolicy());
                            if (getProtocolVersion() == Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE) {
                                mAccount.mSecuritySyncKey = pp.getSecuritySyncKey();
                                if (!sendSettings()) {
                                    LogUtils.i(TAG, "Denied access: %s",
                                            CommandStatus.toString(status));
                                    resultCode = MessagingException.ACCESS_DENIED;
                                }
                            }
                        } else if (tryProvision(pp)) {
                            resultCode = MessagingException.NO_ERROR;
                        } else {
                            resultCode = MessagingException.GENERAL_SECURITY;
                        }
                    } else {
                        // If not, set the proper code (the account will not be created)
                        resultCode = MessagingException.SECURITY_POLICIES_UNSUPPORTED;
                        if (bundle != null) {
                            bundle.putParcelable(EmailServiceProxy.VALIDATE_BUNDLE_POLICY_SET,
                                    pp.getPolicy());
                        }
                    }
                } else if (CommandStatus.isDeniedAccess(status)) {
                    LogUtils.i(TAG, "Denied access: %s", CommandStatus.toString(status));
                    resultCode = MessagingException.ACCESS_DENIED;
                } else if (CommandStatus.isTransientError(status)) {
                    LogUtils.i(TAG, "Transient error: %s", CommandStatus.toString(status));
                    resultCode = MessagingException.IOERROR;
                } else {
                    LogUtils.i(TAG, "Unexpected response: %s", CommandStatus.toString(status));
                    resultCode = MessagingException.UNSPECIFIED_EXCEPTION;
                }
            } catch (final RedirectException e) {
                // We handle a limited number of redirects by recursion.
                if (mRedirectCount < MAX_REDIRECTS && e.mRedirectAddress != null) {
                    ++mRedirectCount;
                    redirectHostAuth(e.mRedirectAddress);
                    if (bundle != null) {
                        bundle.putString(EmailServiceProxy.VALIDATE_BUNDLE_REDIRECT_ADDRESS,
                                e.mRedirectAddress);
                    }
                    doValidationOrSync(bundle);
                    return;
                } else {
                    resultCode = MessagingException.UNSPECIFIED_EXCEPTION;
                }
            }
        } catch (final IOException e) {
            final Throwable cause = e.getCause();
            if (cause != null && cause instanceof CertificateException) {
                // This could be because the server's certificate failed to validate.
                resultCode = MessagingException.GENERAL_SECURITY;
            } else {
                resultCode = MessagingException.IOERROR;
            }
        }

        if (bundle != null) {
            bundle.putInt(EmailServiceProxy.VALIDATE_BUNDLE_RESULT_CODE, resultCode);
        }
    }


    /**
     * Perform the actual validation.
     * @return The validation response.
     */
    public Bundle validate() {
        final Bundle bundle = new Bundle();
        doValidationOrSync(bundle);
        return bundle;
    }
}
