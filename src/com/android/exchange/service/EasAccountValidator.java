package com.android.exchange.service;

import com.google.common.collect.Sets;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;

import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
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

import org.apache.http.Header;
import org.apache.http.HttpStatus;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.HashSet;

/**
 * Performs account validation.
 */
public class EasAccountValidator extends EasServerConnection {
    private static final String TAG = "EasAccountValidator";
    private static final int MAX_REDIRECTS = 3;

    public static final String EAS_12_POLICY_TYPE = "MS-EAS-Provisioning-WBXML";
    public static final String EAS_2_POLICY_TYPE = "MS-WAP-Provisioning-XML";

    // The EAS protocol Provision status for "we implement all of the policies"
    private static final String PROVISION_STATUS_OK = "1";
    // The EAS protocol Provision status meaning "we partially implement the policies"
    private static final String PROVISION_STATUS_PARTIAL = "2";

    // Set of Exchange protocol versions we understand.
    private static final HashSet<String> SUPPORTED_PROTOCOL_VERSIONS = Sets.newHashSet(
            Eas.SUPPORTED_PROTOCOL_EX2003,
            Eas.SUPPORTED_PROTOCOL_EX2007, Eas.SUPPORTED_PROTOCOL_EX2007_SP1,
            Eas.SUPPORTED_PROTOCOL_EX2010, Eas.SUPPORTED_PROTOCOL_EX2010_SP1);

    private String mProtocolVersion;
    private Double mProtocolVersionDouble;
    private int mRedirectCount;

    private static class RedirectException extends Exception {
        public final String mRedirectAddress;
        public RedirectException(final EasResponse resp) {
            mRedirectAddress = resp.getRedirectAddress();
        }
    }

    public EasAccountValidator(final Context context, final HostAuth hostAuth) {
        super(context, hostAuth);
        mProtocolVersion = null;
        mProtocolVersionDouble = null;
        mRedirectCount = 0;
    }

    /**
     * Get the protocol version to use, based on the server's supported versions.
     * @param versionHeader The {@link Header} for the server's supported versions.
     */
    private void setProtocolVersion(final Header versionHeader) {
        // The string is a comma separated list of EAS versions in ascending order
        // e.g. 1.0,2.0,2.5,12.0,12.1,14.0,14.1
        final String supportedVersions = versionHeader.getValue();
        LogUtils.i(TAG, "Server supports versions: %s", supportedVersions);
        final String[] supportedVersionsArray = supportedVersions.split(",");
        mProtocolVersion = null;
        // Find the most recent version we support
        for (final String version: supportedVersionsArray) {
            if (SUPPORTED_PROTOCOL_VERSIONS.contains(version)) {
                mProtocolVersion = version;
            }
        }
        if (mProtocolVersion == null) {
            LogUtils.w(TAG, "No supported EAS versions: %s", supportedVersions);
        } else {
            mProtocolVersionDouble = Eas.getProtocolVersionDouble(mProtocolVersion);
        }

        /*
        // TODO: This code may be relevant when I unify EasAccountSyncHandler with this class.
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
        */
    }

    /**
     * Make an OPTIONS request to determine the protocol version to use.
     * @return A status code for getting the protocol version. If NO_ERROR, then mProtocolVersion
     *     will be set correctly.
     */
    private int doHttpOptions() throws IOException, RedirectException {
        final EasResponse resp = sendHttpClientOptions();
        final int resultCode;
        try {
            final int code = resp.getStatus();
            LogUtils.d(TAG, "Validation (OPTIONS) response: %d", code);
            if (code == HttpStatus.SC_OK) {
                // No exception means successful validation
                final Header commands = resp.getHeader("MS-ASProtocolCommands");
                final Header versions = resp.getHeader("ms-asprotocolversions");
                if (commands == null || versions == null) {
                    LogUtils.e(TAG, "OPTIONS response without commands or versions");
                    mProtocolVersion = null;
                } else {
                    setProtocolVersion(versions);
                }
                if (mProtocolVersion == null) {
                    return MessagingException.PROTOCOL_VERSION_UNSUPPORTED;
                }
                return MessagingException.NO_ERROR;
            }
            if (EasResponse.isAuthError(code)) {
                return resp.isMissingCertificate()
                        ? MessagingException.CLIENT_CERTIFICATE_REQUIRED
                        : MessagingException.AUTHENTICATION_FAILED;
            }
            if (code == HttpStatus.SC_INTERNAL_SERVER_ERROR) {
                return MessagingException.AUTHENTICATION_FAILED_OR_SERVER_ERROR;
            }
            if (EasResponse.isRedirectError(code)) {
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
     * Send a FolderSync request to the server to verify the response -- we aren't actually
     * syncing the account at this point, just want to make sure we get valid output.
     * @param account The account we're verifying.
     * @return A status code indicating the result of this check.
     * @throws IOException
     * @throws CommandStatusException
     * @throws RedirectException
     */
    private int doFolderSync(final Account account)
            throws IOException, CommandStatusException, RedirectException {
        LogUtils.i(TAG, "Try FolderSync for %s, %s, ssl = %s", mHostAuth.mAddress, mHostAuth.mLogin,
                mHostAuth.shouldUseSsl() ? "1" : "0");

        // Send "0" as the sync key for new accounts; otherwise, use the current key
        final String syncKey = "0";
        final Serializer s = new Serializer();
        s.start(Tags.FOLDER_FOLDER_SYNC).start(Tags.FOLDER_SYNC_KEY).text(syncKey)
            .end().end().done();
        final EasResponse resp = sendHttpClientPost(account, "FolderSync", s.toByteArray());
        final int resultCode;
        try {
            final int code = resp.getStatus();
            LogUtils.d(TAG, "Validations (FolderSync) response: %d", code);
            if (code == HttpStatus.SC_OK) {
                // We need to parse the result to see if we've got a provisioning issue
                // (EAS 14.0 only)
                if (!resp.isEmpty()) {
                    // Create the parser with statusOnly set to true; we only care about
                    // seeing if a CommandStatusException is thrown (indicating a
                    // provisioning failure)
                    new FolderSyncParser(mContext, mContext.getContentResolver(),
                            resp.getInputStream(), account, true).parse();
                }
                resultCode = MessagingException.NO_ERROR;
            } else if (code == HttpStatus.SC_FORBIDDEN) {
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
            } else if (EasResponse.isRedirectError(code)) {
                throw new RedirectException(resp);
            } else {
                resultCode = MessagingException.UNSPECIFIED_EXCEPTION;
            }
        } finally {
            resp.close();
        }
        return resultCode;
    }

    private boolean sendSettings(final Account account) throws IOException {
        final Serializer s = new Serializer();
        s.start(Tags.SETTINGS_SETTINGS);
        s.start(Tags.SETTINGS_DEVICE_INFORMATION).start(Tags.SETTINGS_SET);
        s.data(Tags.SETTINGS_MODEL, Build.MODEL);
        s.data(Tags.SETTINGS_OS, "Android " + Build.VERSION.RELEASE);
        s.data(Tags.SETTINGS_USER_AGENT, USER_AGENT);
        s.end().end().end().done(); // SETTINGS_SET, SETTINGS_DEVICE_INFORMATION, SETTINGS_SETTINGS
        final EasResponse resp = sendHttpClientPost(account, "Settings", s.toByteArray());
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

    private String getPolicyType(Double protocolVersion) {
        return (protocolVersion >=
            Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) ? EAS_12_POLICY_TYPE : EAS_2_POLICY_TYPE;
    }

    private void acknowledgeRemoteWipe(final Account account, final String tempKey)
            throws IOException {
        acknowledgeProvisionImpl(account, tempKey, PROVISION_STATUS_OK, true);
    }

    private String acknowledgeProvision(final Account account, final String tempKey,
            final String result) throws IOException {
        return acknowledgeProvisionImpl(account, tempKey, result, false);
    }

    private String acknowledgeProvisionImpl(final Account account, final String tempKey,
            final String status, final boolean remoteWipe) throws IOException {
        final Serializer s = new Serializer();
        s.start(Tags.PROVISION_PROVISION).start(Tags.PROVISION_POLICIES);
        s.start(Tags.PROVISION_POLICY);

        // Use the proper policy type, depending on EAS version
        s.data(Tags.PROVISION_POLICY_TYPE, getPolicyType(mProtocolVersionDouble));

        s.data(Tags.PROVISION_POLICY_KEY, tempKey);
        s.data(Tags.PROVISION_STATUS, status);
        s.end().end(); // PROVISION_POLICY, PROVISION_POLICIES
        if (remoteWipe) {
            s.start(Tags.PROVISION_REMOTE_WIPE);
            s.data(Tags.PROVISION_STATUS, PROVISION_STATUS_OK);
            s.end();
        }
        s.end().done(); // PROVISION_PROVISION
        EasResponse resp = sendHttpClientPost(account, "Provision", s.toByteArray());
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

    public ProvisionParser canProvision(final Account account) throws IOException {
        final Serializer s = new Serializer();
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
        s.start(Tags.PROVISION_POLICY);
        s.data(Tags.PROVISION_POLICY_TYPE, getPolicyType(mProtocolVersionDouble));
        s.end().end().end().done(); // PROVISION_POLICY, PROVISION_POLICIES, PROVISION_PROVISION
        final EasResponse resp = sendHttpClientPost(account, "Provision", s.toByteArray());
        try {
            int code = resp.getStatus();
            if (code == HttpStatus.SC_OK) {
                final ProvisionParser pp = new ProvisionParser(mContext, resp.getInputStream());
                if (pp.parse()) {
                    // The PolicySet in the ProvisionParser will have the requirements for all KNOWN
                    // policies.  If others are required, hasSupportablePolicySet will be false
                    if (pp.hasSupportablePolicySet() &&
                            mProtocolVersionDouble == Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE) {
                        // In EAS 14.0, we need the final security key in order to use the settings
                        // command
                        final String policyKey =
                                acknowledgeProvision(account, pp.getSecuritySyncKey(),
                                        PROVISION_STATUS_OK);
                        if (policyKey != null) {
                            pp.setSecuritySyncKey(policyKey);
                        }
                    } else if (!pp.hasSupportablePolicySet())  {
                        // Try to acknowledge using the "partial" status (i.e. we can partially
                        // accommodate the required policies).  The server will agree to this if the
                        // "allow non-provisionable devices" setting is enabled on the server
                        LogUtils.i(TAG, "PolicySet is NOT fully supportable");
                        if (acknowledgeProvision(account, pp.getSecuritySyncKey(),
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


    public boolean tryProvision(final Account account) throws IOException {
        mProtocolVersion = account.mProtocolVersion;
        mProtocolVersionDouble = Eas.getProtocolVersionDouble(mProtocolVersion);
        // First, see if provisioning is even possible, i.e. do we support the policies required
        // by the server
        ProvisionParser pp = canProvision(account);
        if (pp == null) return false;
        // Get the policies from ProvisionParser
        Policy policy = pp.getPolicy();
        Policy oldPolicy = null;
        // Grab the old policy (if any)
        if (account.mPolicyKey > 0) {
            oldPolicy = Policy.restorePolicyWithId(mContext, account.mPolicyKey);
        }
        // Update the account with a null policyKey (the key we've gotten is
        // temporary and cannot be used for syncing)
        PolicyServiceProxy.setAccountPolicy(mContext, account.mId, policy, null);
        // Make sure mAccount is current (with latest policy key)
        account.refresh(mContext);
        if (pp.getRemoteWipe()) {
            // We've gotten a remote wipe command
            LogUtils.i(TAG, "!!! Remote wipe request received");
            // Start by setting the account to security hold
            PolicyServiceProxy.setAccountHoldFlag(mContext, account, true);

            // First, we've got to acknowledge it, but wrap the wipe in try/catch so that
            // we wipe the device regardless of any errors in acknowledgment
            try {
                LogUtils.i(TAG, "!!! Acknowledging remote wipe to server");
                acknowledgeRemoteWipe(account, pp.getSecuritySyncKey());
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
            if (mProtocolVersionDouble == Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE) {
                securitySyncKey = pp.getSecuritySyncKey();
            } else {
                securitySyncKey = acknowledgeProvision(account, pp.getSecuritySyncKey(),
                        PROVISION_STATUS_OK);
            }
            if (securitySyncKey != null) {
                // If attachment policies have changed, fix up any affected attachment records
                if (oldPolicy != null) {
                    if ((oldPolicy.mDontAllowAttachments != policy.mDontAllowAttachments) ||
                            (oldPolicy.mMaxAttachmentSize != policy.mMaxAttachmentSize)) {
                        Policy.setAttachmentFlagsForNewPolicy(mContext, account, policy);
                    }
                }
                // Write the final policy key to the Account and say we've been successful
                PolicyServiceProxy.setAccountPolicy(mContext, account.mId, policy, securitySyncKey);
                return true;
            }
        }
        return false;
    }

    /**
     * Perform the actual validation.
     * @return The validation response.
     */
    public Bundle validate() {
        LogUtils.i(TAG, "Testing EAS: %s, %s, ssl = %s", mHostAuth.mAddress, mHostAuth.mLogin,
                mHostAuth.shouldUseSsl() ? "1" : "0");
        final Bundle bundle = new Bundle();
        if (mHostAuth.mClientCertAlias != null) {
            try {
                getClientConnectionManager().registerClientCert(mContext, mHostAuth);
            } catch (final CertificateException e) {
                // The client certificate the user specified is invalid/inaccessible.
                bundle.putInt(EmailServiceProxy.VALIDATE_BUNDLE_RESULT_CODE,
                        MessagingException.CLIENT_CERTIFICATE_ERROR);
                return bundle;
            }
        }

        int resultCode;
        final Account account = new Account();
        account.mEmailAddress = mHostAuth.mLogin;
        // Need a nested try here because the provisioning exception handler can throw IOException.
        try {
            try {
                final int optionsResult = doHttpOptions();
                if (optionsResult != MessagingException.NO_ERROR) {
                    bundle.putInt(EmailServiceProxy.VALIDATE_BUNDLE_RESULT_CODE, optionsResult);
                    return bundle;
                }
                account.mProtocolVersion = mProtocolVersion;
                bundle.putString(EmailServiceProxy.VALIDATE_BUNDLE_PROTOCOL_VERSION,
                        mProtocolVersion);
                resultCode = doFolderSync(account);
            } catch (final CommandStatusException e) {
                final int status = e.mStatus;
                if (CommandStatus.isNeedsProvisioning(status)) {
                    // Get the policies and see if we are able to support them
                    final ProvisionParser pp = canProvision(account);
                    if (pp != null && pp.hasSupportablePolicySet()) {
                        // Set the proper result code and save the PolicySet in our Bundle
                        resultCode = MessagingException.SECURITY_POLICIES_REQUIRED;
                        bundle.putParcelable(EmailServiceProxy.VALIDATE_BUNDLE_POLICY_SET,
                                pp.getPolicy());
                        if (mProtocolVersionDouble == Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE) {
                            account.mSecuritySyncKey = pp.getSecuritySyncKey();
                            if (!sendSettings(account)) {
                                LogUtils.i(TAG, "Denied access: %s",
                                        CommandStatus.toString(status));
                                resultCode = MessagingException.ACCESS_DENIED;
                            }
                        }
                    } else {
                        // If not, set the proper code (the account will not be created)
                        resultCode = MessagingException.SECURITY_POLICIES_UNSUPPORTED;
                        bundle.putParcelable(EmailServiceProxy.VALIDATE_BUNDLE_POLICY_SET,
                                pp.getPolicy());
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
                // We handle a limited number of redirects by recursively calling ourself.
                if (mRedirectCount < MAX_REDIRECTS && e.mRedirectAddress != null) {
                    ++mRedirectCount;
                    redirectHostAuth(e.mRedirectAddress);
                    return validate();
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

        bundle.putInt(EmailServiceProxy.VALIDATE_BUNDLE_RESULT_CODE, resultCode);
        return bundle;
    }
}
