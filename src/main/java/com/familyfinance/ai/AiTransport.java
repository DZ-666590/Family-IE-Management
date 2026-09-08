package com.familyfinance.ai;

import java.net.URI;

/** Package boundary for mockable provider I/O; never log credentials or bodies. */
interface AiTransport {
    byte[] exchange(URI endpoint, String key, byte[] body);
}
