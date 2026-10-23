package com.releasesentinel.egress;

public final class DeliveryExceptions {
    private DeliveryExceptions() { }
    public static class RetryableDeliveryException extends RuntimeException { public RetryableDeliveryException(String message, Throwable cause) { super(message, cause); } public RetryableDeliveryException(String message) { super(message); } }
    public static class PermanentDeliveryException extends RuntimeException { public PermanentDeliveryException(String message) { super(message); } }
}
