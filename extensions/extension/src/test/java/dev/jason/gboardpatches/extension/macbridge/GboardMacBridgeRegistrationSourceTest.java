package dev.jason.gboardpatches.extension.macbridge;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GboardMacBridgeRegistrationSourceTest {
    @Test
    public void settingsEntrySitsAtTheRootAfterFtp() throws Exception {
        String registry = read("src/main/java/dev/jason/gboardpatches/extension/"
                + "settings/GboardPatchesSettingsFeatureRegistry.java");

        int ftp = registry.indexOf("new GboardLanFtpSettingsFeature(context)");
        int mac = registry.indexOf("new GboardMacBridgeSettingsFeature(context)");
        int settingsHomepage = registry.indexOf("new GboardSettingsHomepageSettingsFeature()");
        Assert.assertTrue(ftp >= 0);
        Assert.assertTrue(ftp < mac);
        Assert.assertTrue(mac < settingsHomepage);
    }

    @Test
    public void markerMatchesThePatchAndGatesTheToolbarContribution() throws Exception {
        String availability = read("src/main/java/dev/jason/gboardpatches/extension/"
                + "settings/GboardPatchesFeatureAvailability.java");
        String accessPoints = read("src/main/java/dev/jason/gboardpatches/extension/"
                + "accesspoint/GboardAccessPointContributions1803Runtime.java");

        Assert.assertTrue(availability.contains("\"dev.jason.gboardpatches.feature.mac_bridge\""));
        int synchronization = accessPoints.indexOf(
                "synchronizeControllerOrderCatalog(controller, context);");
        int registration = accessPoints.indexOf(
                "GboardMacBridgeAccessPoint1803Contribution.INSTANCE.register(");
        Assert.assertTrue(synchronization >= 0);
        Assert.assertTrue(registration > synchronization);
        Assert.assertTrue(accessPoints.contains(
                "GboardMacBridgeAccessPoint1803Contribution.INSTANCE\n"
                        + "                        .extendOrderCatalog(context, result)"));
    }

    @Test
    public void pairingSecretsStayOutOfTheSharedSettingsFile() throws Exception {
        String store = read("src/main/java/dev/jason/gboardpatches/extension/"
                + "macbridge/MacBridgeStore.java");

        Assert.assertTrue(store.contains("PREF_FILE = \"gboard_patches_mac_bridge\""));
        Assert.assertFalse(store.contains("GboardPatchesSettings"));
        Assert.assertTrue(store.contains("MacBridgeSecrets.seal(token)"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8);
    }
}
