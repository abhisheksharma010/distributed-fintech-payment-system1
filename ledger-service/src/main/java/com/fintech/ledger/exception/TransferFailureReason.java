package com.fintech.ledger.exception;

public enum TransferFailureReason {

    ACCOUNT_NOT_FOUND,

    ACCOUNT_INACTIVE,

    INSUFFICIENT_BALANCE,

    CURRENCY_MISMATCH,

    INVALID_TRANSFER
}