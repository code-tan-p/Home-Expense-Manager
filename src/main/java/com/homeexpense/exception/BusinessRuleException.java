package com.homeexpense.exception;

/** A request that breaks a household business rule. The message is written for the user. */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
