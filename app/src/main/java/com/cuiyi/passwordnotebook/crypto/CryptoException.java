package com.cuiyi.passwordnotebook.crypto;

/**
 * Raised when an encryption or decryption operation cannot be completed.
 *
 * Callers must not swallow this and continue: the old code returned the
 * plaintext when encryption failed, which could write a secret to disk in the
 * clear without anyone noticing.
 */
public class CryptoException extends Exception {

    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
