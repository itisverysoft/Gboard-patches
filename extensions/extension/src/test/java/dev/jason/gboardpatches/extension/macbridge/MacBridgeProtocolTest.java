package dev.jason.gboardpatches.extension.macbridge;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class MacBridgeProtocolTest {
    @Test
    public void helloCarriesTheTokenAndProtocolVersion() throws Exception {
        JSONObject hello = new JSONObject(MacBridgeProtocol.hello("Pixel (Gboard)", "secret"));

        Assert.assertEquals("hello", hello.getString("type"));
        Assert.assertEquals(1, hello.getInt("protoVersion"));
        Assert.assertEquals("secret", hello.getString("token"));
        Assert.assertEquals("android", hello.getString("platform"));
        Assert.assertEquals("Pixel (Gboard)", hello.getString("deviceName"));
        Assert.assertFalse(hello.has("nonce"));
    }

    @Test
    public void pairingHelloCarriesTheCodeInsteadOfAToken() throws Exception {
        JSONObject hello = new JSONObject(MacBridgeProtocol.pairingHello("Pixel", "482913"));

        Assert.assertEquals("482913", hello.getString("nonce"));
        Assert.assertFalse(hello.has("token"));
    }

    @Test
    public void clipAsksTheMacToTypeAndReportsItsAge() throws Exception {
        JSONObject clip = new JSONObject(MacBridgeProtocol.clip("id-1", "line\nnext", 1234L));

        Assert.assertEquals("clip", clip.getString("type"));
        Assert.assertEquals("id-1", clip.getString("id"));
        Assert.assertEquals("line\nnext", clip.getString("text"));
        Assert.assertTrue(clip.getBoolean("insert"));
        Assert.assertEquals(1234L, clip.getLong("age"));
        Assert.assertEquals(0L, new JSONObject(MacBridgeProtocol.clip("id", "t", -5L)).getLong("age"));
    }

    @Test
    public void everyFrameIsOneLine() {
        Assert.assertFalse(MacBridgeProtocol.clip("id", "a\nb\r\nc", 0L).contains("\n"));
        Assert.assertFalse(MacBridgeProtocol.clipAck("id").contains("\n"));
    }

    @Test
    public void parseReadsFieldsAndIgnoresGarbage() {
        MacBridgeProtocol.Message clip = MacBridgeProtocol.parse(
                "{\"type\":\"clipAck\",\"protoVersion\":1,\"id\":\"x\",\"inserted\":true}");
        MacBridgeProtocol.Message welcome = MacBridgeProtocol.parse(
                "{\"type\":\"welcome\",\"protoVersion\":1,\"macName\":\"\"}");

        Assert.assertEquals("clipAck", clip.type);
        Assert.assertEquals("x", clip.string("id"));
        Assert.assertTrue(clip.flag("inserted"));
        Assert.assertNull(welcome.string("macName"));
        Assert.assertNull(MacBridgeProtocol.parse("not json"));
        Assert.assertNull(MacBridgeProtocol.parse(null));
        Assert.assertEquals("", MacBridgeProtocol.parse("{}").type);
    }
}
