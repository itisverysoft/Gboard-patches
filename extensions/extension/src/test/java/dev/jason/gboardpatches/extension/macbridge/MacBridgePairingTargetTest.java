package dev.jason.gboardpatches.extension.macbridge;

import org.junit.Assert;
import org.junit.Test;

public final class MacBridgePairingTargetTest {
    @Test
    public void qrLinkCarriesAddressCodeFingerprintAndName() {
        MacBridgePairingTarget target = MacBridgePairingTarget.fromLink(
                "voxbridge://pair?host=192.168.1.20&port=5679&nonce=482913"
                        + "&fp=ABCDEF0123&name=Sifat%27s%20MacBook%20Pro");

        Assert.assertNotNull(target);
        Assert.assertEquals("192.168.1.20", target.host);
        Assert.assertEquals(5679, target.port);
        Assert.assertEquals("482913", target.code);
        Assert.assertEquals("abcdef0123", target.fingerprint);
        Assert.assertEquals("Sifat's MacBook Pro", target.name);
    }

    @Test
    public void linkWithoutFingerprintTrustsOnFirstUse() {
        MacBridgePairingTarget target = MacBridgePairingTarget.fromLink(
                "  VOXBRIDGE://PAIR?host=mac.local&port=6000&nonce=111111  ");

        Assert.assertNotNull(target);
        Assert.assertNull(target.fingerprint);
        Assert.assertNull(target.name);
        Assert.assertEquals(6000, target.port);
    }

    @Test
    public void malformedLinksAreRejected() {
        Assert.assertNull(MacBridgePairingTarget.fromLink(null));
        Assert.assertNull(MacBridgePairingTarget.fromLink("https://pair?host=a&port=1&nonce=1"));
        Assert.assertNull(MacBridgePairingTarget.fromLink("voxbridge://other?host=a&port=1&nonce=1"));
        Assert.assertNull(MacBridgePairingTarget.fromLink("voxbridge://pair?host=a&port=1"));
        Assert.assertNull(MacBridgePairingTarget.fromLink("voxbridge://pair?host=a&port=0&nonce=1"));
        Assert.assertNull(MacBridgePairingTarget.fromLink("voxbridge://pair?host=a&port=70000&nonce=1"));
        Assert.assertNull(MacBridgePairingTarget.fromLink("voxbridge://pair?port=1&nonce=1"));
    }

    @Test
    public void typedAddressDefaultsToTheMacPort() {
        MacBridgePairingTarget plain = MacBridgePairingTarget.fromAddress(" 10.0.0.7 ");
        MacBridgePairingTarget withPort = MacBridgePairingTarget.fromAddress("10.0.0.7:6001");
        MacBridgePairingTarget ipv6 = MacBridgePairingTarget.fromAddress("fe80::1");

        Assert.assertEquals("10.0.0.7", plain.host);
        Assert.assertEquals(MacBridgePairingTarget.DEFAULT_PORT, plain.port);
        Assert.assertNull(plain.code);
        Assert.assertNull(plain.fingerprint);
        Assert.assertEquals(6001, withPort.port);
        Assert.assertEquals("fe80::1", ipv6.host);
        Assert.assertEquals(MacBridgePairingTarget.DEFAULT_PORT, ipv6.port);
        Assert.assertNull(MacBridgePairingTarget.fromAddress(""));
        Assert.assertNull(MacBridgePairingTarget.fromAddress("10.0.0.7:abc"));
        Assert.assertNull(MacBridgePairingTarget.fromAddress("http://10.0.0.7"));
    }

    @Test
    public void codeMustBeSixDigits() {
        Assert.assertTrue(MacBridgePairingTarget.isValidCode("482913"));
        Assert.assertTrue(MacBridgePairingTarget.isValidCode(" 482913 "));
        Assert.assertFalse(MacBridgePairingTarget.isValidCode("48291"));
        Assert.assertFalse(MacBridgePairingTarget.isValidCode("48291a"));
        Assert.assertFalse(MacBridgePairingTarget.isValidCode(null));
    }

    @Test
    public void codeIsAddedWithoutLosingThePin() {
        MacBridgePairingTarget found = new MacBridgePairingTarget(
                "10.0.0.7", 5679, "abc", null, "Studio");

        MacBridgePairingTarget withCode = found.withCode("123456");

        Assert.assertEquals("123456", withCode.code);
        Assert.assertEquals("abc", withCode.fingerprint);
        Assert.assertEquals("Studio", withCode.name);
    }
}
