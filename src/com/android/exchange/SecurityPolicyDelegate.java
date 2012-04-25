/*
 * Copyright (C) 2011 The Android Open Source Project
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

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.service.PolicyServiceProxy;

import android.content.Context;
import android.os.RemoteException;

public class SecurityPolicyDelegate {

    public static boolean isActive(Context context, Policy policy) {
        try {
            return new PolicyServiceProxy(context).isActive(policy);
        } catch (RemoteException e) {
        }
        return false;
    }

    public static void policiesRequired(Context context, long accountId) {
        try {
            new PolicyServiceProxy(context).policiesRequired(accountId);
        } catch (RemoteException e) {
            throw new IllegalStateException("PolicyService transaction failed");
        }
    }

    public static void policiesUpdated(Context context, long accountId) {
        try {
            new PolicyServiceProxy(context).policiesUpdated(accountId);
        } catch (RemoteException e) {
            throw new IllegalStateException("PolicyService transaction failed");
        }
    }

    public static void setAccountHoldFlag(Context context, Account account, boolean newState) {
        try {
            new PolicyServiceProxy(context).setAccountHoldFlag(account.mId, newState);
        } catch (RemoteException e) {
            throw new IllegalStateException("PolicyService transaction failed");
        }
    }

    public static boolean isActiveAdmin(Context context) {
        try {
            return new PolicyServiceProxy(context).isActiveAdmin();
        } catch (RemoteException e) {
        }
        return false;
    }

    public static void remoteWipe(Context context) {
        try {
            new PolicyServiceProxy(context).remoteWipe();
        } catch (RemoteException e) {
            throw new IllegalStateException("PolicyService transaction failed");
        }
    }

    public static boolean isSupported(Context context, Policy policy) {
        try {
            return new PolicyServiceProxy(context).isSupported(policy);
        } catch (RemoteException e) {
        }
        return false;
     }

    public static Policy clearUnsupportedPolicies(Context context, Policy policy) {
        try {
            return new PolicyServiceProxy(context).clearUnsupportedPolicies(policy);
        } catch (RemoteException e) {
        }
        throw new IllegalStateException("PolicyService transaction failed");
    }
}
