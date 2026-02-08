package com.omnilink.alexa;

import net.homeip.mleclerc.omnilink.CommunicationException;

import java.util.List;

/**
 * Standalone test program that invokes OmniLink discovery and prints
 * the list of devices (units) found.
 * <p>
 * Set environment variables before running: OMNI_HOST, OMNI_PRIVATE_KEY,
 * and optionally OMNI_PORT (default 4369), OMNI_LOGIN_CODE, OMNI_PROTOCOL,
 * OMNI_SYSTEM_TYPE.
 */
public class DiscoveryTest {

    public static void main(String[] args) {
        System.out.println("OmniLink Discovery Test");
        System.out.println("-----------------------");
        try {
            try (OmniLinkClient client = OmniLinkClient.fromEnvironment()) {
                List<OmniLinkClient.UnitInfo> units = client.discoverUnits();
                System.out.println("Found " + units.size() + " unit(s):");
                System.out.println();
                for (OmniLinkClient.UnitInfo unit : units) {
                    System.out.printf("  #%-3d  %-30s  endpointId: %s%n",
                            unit.getNumber(),
                            unit.getFriendlyName(),
                            unit.getEndpointId());
                }
                if (units.isEmpty()) {
                    System.out.println("  (no units)");
                }
            }
        } catch (CommunicationException e) {
            System.err.println("OmniLink communication error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
