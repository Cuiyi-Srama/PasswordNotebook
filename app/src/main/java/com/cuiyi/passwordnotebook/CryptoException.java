package com.cuiyi.passwordnotebook;

/** Raised when encryption or decryption cannot proceed. Never swallowed. */
public class CryptoException extends Exception {

    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
