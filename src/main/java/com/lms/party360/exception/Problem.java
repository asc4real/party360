package com.lms.party360.exception;

public class Problem extends RuntimeException {

    public static Throwable badRequest(String event, String errorMessage) {
        return new Exception();
    }

    public static Exception conflict(String partyAlreadyExists, String s) {
    }

    public static Exception internal(String createPersonFailed, String s) {
    }

    public static Exception upstream(String s, String vaultRequestFailed) {
    }
}
