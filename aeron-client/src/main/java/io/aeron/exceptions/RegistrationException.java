/*
 * Copyright 2014-2019 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.exceptions;

import io.aeron.*;

/**
 * Caused when a error occurs during addition, modification, or release of client resources such as
 * {@link Publication}s, {@link Subscription}s, or {@link Counter}s.
 */
public class RegistrationException extends AeronException
{
    private final long correlationId;
    private final int errorCodeValue;
    private final ErrorCode errorCode;

    public RegistrationException(
        final long correlationId, final int errorCodeValue, final ErrorCode errorCode, final String msg)
    {
        super(msg);
        this.correlationId = correlationId;
        this.errorCode = errorCode;
        this.errorCodeValue = errorCodeValue;
    }

    /**
     * Get the correlation id of the command to register the resource action.
     *
     * @return the correlation id of the command to register the resource action.
     */
    public long correlationId()
    {
        return correlationId;
    }

    /**
     * Get the {@link ErrorCode} for the specific exception.
     *
     * @return the {@link ErrorCode} for the specific exception.
     */
    public ErrorCode errorCode()
    {
        return errorCode;
    }

    /**
     * Value of the errorCode encoded. This can provide additional information when a
     * {@link ErrorCode#UNKNOWN_CODE_VALUE} is returned.
     *
     * @return value of the errorCode encoded as an int.
     */
    public int errorCodeValue()
    {
        return errorCodeValue;
    }

    public String getMessage()
    {
        return "correlationId=" + correlationId + "errorCodeValue=" + errorCodeValue + " " + super.getMessage();
    }
}
