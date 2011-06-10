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

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Encapsulate a response to an HTTP POST
 */
public class EasResponse {
    final HttpResponse mResponse;
    private final HttpEntity mEntity;
    private final int mLength;
    private InputStream mInputStream;
    private boolean mClosed;

    public EasResponse(HttpResponse response) {
        mResponse = response;
        mEntity = mResponse.getEntity();
        if (mEntity !=  null) {
            mLength = (int)mEntity.getContentLength();
        } else {
            mLength = 0;
        }
    }

    /**
     * Return an appropriate input stream for the response, either a GZIPInputStream, for
     * compressed data, or a generic InputStream otherwise
     * @return the input stream for the response
     */
    public InputStream getInputStream() {
        if (mInputStream != null || mClosed) {
            throw new IllegalStateException("Can't reuse stream or get closed stream");
        } else if (mEntity == null) {
            throw new IllegalStateException("Can't get input stream without entity");
        }
        InputStream is = null;
        try {
            // Get the default input stream for the entity
            is = mEntity.getContent();
            Header ceHeader = mResponse.getFirstHeader("Content-Encoding");
            if (ceHeader != null) {
                String encoding = ceHeader.getValue();
                // If we're gzip encoded, wrap appropriately
                if (encoding.toLowerCase().equals("gzip")) {
                    is = new GZIPInputStream(is);
                }
            }
        } catch (IllegalStateException e1) {
        } catch (IOException e1) {
        }
        mInputStream = is;
        return is;
    }

    public boolean isEmpty() {
        return mLength == 0;
    }

    public int getStatus() {
        return mResponse.getStatusLine().getStatusCode();
    }

    public Header getHeader(String name) {
        return mResponse.getFirstHeader(name);
    }

    public int getLength() {
        return mLength;
    }

    public void close() {
        if (mEntity != null && !mClosed) {
            try {
                mEntity.consumeContent();
            } catch (IOException e) {
                // No harm, no foul
            }
        }
        mClosed = true;
    }
}