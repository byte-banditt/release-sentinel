package com.releasesentinel.egress;

import org.junit.jupiter.api.Test;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;

class WebhookDeliveryTest {
    @Test void signatureChangesWithBodyAndUsesHexHmac() {
        String one = WebhookDelivery.sign("secret", "1.{\"a\":1}");
        String two = WebhookDelivery.sign("secret", "1.{\"a\":2}");
        assertEquals(64, one.length());
        assertFalse(MessageDigest.isEqual(one.getBytes(), two.getBytes()));
    }
}
