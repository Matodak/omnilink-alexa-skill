package com.omnilink.alexa;

import net.homeip.mleclerc.omnilink.CommunicationException;
import net.homeip.mleclerc.omnilink.MessageManager;
import net.homeip.mleclerc.omnilink.NetworkCommunication;
import net.homeip.mleclerc.omnilink.enumeration.NameTypeEnum;
import net.homeip.mleclerc.omnilink.enumeration.ProtocolTypeEnum;
import net.homeip.mleclerc.omnilink.enumeration.SystemTypeEnum;
import net.homeip.mleclerc.omnilink.enumeration.UnitControlEnum;
import net.homeip.mleclerc.omnilink.message.LoginControl;
import net.homeip.mleclerc.omnilink.message.LogoutControl;
import net.homeip.mleclerc.omnilink.message.UnitCommand;
import net.homeip.mleclerc.omnilink.message.UnitStatusReport;
import net.homeip.mleclerc.omnilink.message.UnitStatusRequest;
import net.homeip.mleclerc.omnilink.message.ReadNameReport;
import net.homeip.mleclerc.omnilink.message.ReadNameRequest;
import net.homeip.mleclerc.omnilink.message.UploadNameMessageReport.NameInfo;
import net.homeip.mleclerc.omnilink.EndOfDataException;

import java.util.ArrayList;
import java.util.List;

/**
 * Client for sending commands to the HAI Omni IIe via the OmniLink API.
 * Configuration is read from environment variables.
 */
public class OmniLinkClient implements AutoCloseable {

    public static final String ENV_OMNI_HOST = "OMNI_HOST";
    public static final String ENV_OMNI_PORT = "OMNI_PORT";
    public static final String ENV_OMNI_PRIVATE_KEY = "OMNI_PRIVATE_KEY";
    public static final String ENV_OMNI_LOGIN_CODE = "OMNI_LOGIN_CODE";
    public static final String ENV_OMNI_PROTOCOL = "OMNI_PROTOCOL";
    public static final String ENV_OMNI_SYSTEM_TYPE = "OMNI_SYSTEM_TYPE";

    private final MessageManager comm;
    private final boolean useLogin;

    public OmniLinkClient(String host, int port, String privateKey, String loginCode,
                          ProtocolTypeEnum protocolType, SystemTypeEnum systemType) throws CommunicationException, Exception {
        System.out.println("OmniLinkClient constructor: host=" + host + ", port=" + port + ", privateKey=" + privateKey + ", loginCode=" + loginCode + ", protocolType=" + protocolType + ", systemType=" + systemType);

        int timeout = NetworkCommunication.DEFAULT_TIMEOUT;
        comm = new NetworkCommunication(systemType, host, port, timeout, privateKey, protocolType);
        comm.open();
        useLogin = (protocolType == ProtocolTypeEnum.HAI_OMNI_LINK);
        if (useLogin && loginCode != null && !loginCode.isEmpty()) {
            comm.execute(new LoginControl(loginCode));
        }
    }

    /**
     * Create client from environment variables.
     */
    public static OmniLinkClient fromEnvironment() throws CommunicationException, Exception {
        String host = System.getenv(ENV_OMNI_HOST);
        String portStr = System.getenv(ENV_OMNI_PORT);
        String privateKey = System.getenv(ENV_OMNI_PRIVATE_KEY);
        String loginCode = System.getenv(ENV_OMNI_LOGIN_CODE);
        String protocolStr = System.getenv(ENV_OMNI_PROTOCOL);
        String systemTypeStr = System.getenv(ENV_OMNI_SYSTEM_TYPE);

        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("Missing " + ENV_OMNI_HOST);
        }
        int port = 4369;
        if (portStr != null && !portStr.isEmpty()) {
            port = Integer.parseInt(portStr);
        }
        if (privateKey == null || privateKey.isEmpty()) {
            throw new IllegalArgumentException("Missing " + ENV_OMNI_PRIVATE_KEY);
        }

        ProtocolTypeEnum protocolType = protocolStr != null && "HAI_OMNI_LINK_II".equalsIgnoreCase(protocolStr)
                ? ProtocolTypeEnum.HAI_OMNI_LINK_II
                : ProtocolTypeEnum.HAI_OMNI_LINK;

        // SystemTypeEnum is custom (no valueOf); use AEGIS_2000 or HAI_OMNI_IIE for Omni IIe
        SystemTypeEnum systemType = SystemTypeEnum.AEGIS_2000;
        if (systemTypeStr != null && "HAI_OMNI_IIE".equalsIgnoreCase(systemTypeStr)) {
            systemType = SystemTypeEnum.HAI_OMNI_IIE;
        }

        // Display the parameters passed on OmniLinkClient constructor
        return new OmniLinkClient(host, port, privateKey, loginCode, protocolType, systemType);
    }

    /**
     * Discover controllable units: fetches names from system and returns unit number and friendly name.
     */
    public List<UnitInfo> discoverUnits() throws CommunicationException, Exception {
        List<UnitInfo> units = new ArrayList<>();
		int objectNo = 0;
		while (true) {
			try {
                System.out.println("DiscoverUnits: reading name for objectNo=" + objectNo);
				ReadNameReport readNameResponse = (ReadNameReport) comm.execute(new ReadNameRequest(NameTypeEnum.UNIT, objectNo));
				NameInfo info = readNameResponse.getInfo();
                if (info.getNumber() > 64) 
                    break; // flags starting at 65
                String name = info.getText();
                if (name == null) name = "Unit " + info.getNumber();
                else name = name.trim().isEmpty() ? "Unit " + info.getNumber() : name.trim();
                units.add(new UnitInfo(info.getNumber(), name));
				objectNo = info.getObjectNo();
			} catch(EndOfDataException ex) {
				// Reached the end of the properties
				break;
			}
		}
        return units;
    }

    /**
     * Get current power state of a unit (ON/OFF).
     */
    @SuppressWarnings("unchecked")
    public UnitControlEnum getUnitState(int unitNumber) throws CommunicationException, Exception {
        UnitStatusReport report = (UnitStatusReport) comm.execute(new UnitStatusRequest(unitNumber));
        List<UnitStatusReport.UnitStatusInfo> list = new ArrayList<>(report.getInfoList());
        if (list.isEmpty()) throw new CommunicationException("No unit status information found");
        return list.get(0).getCondition();
    }

    /**
     * Turn a unit on or off.
     */
    public void setUnitPower(int unitNumber, UnitControlEnum state) throws CommunicationException, Exception {
        comm.execute(new UnitCommand(unitNumber, state));
    }

    public void turnOn(int unitNumber) throws CommunicationException, Exception {
        setUnitPower(unitNumber, UnitControlEnum.ON);
    }

    public void turnOff(int unitNumber) throws CommunicationException, Exception {
        setUnitPower(unitNumber, UnitControlEnum.OFF);
    }

    @Override
    public void close() {
        if (comm != null) {
            System.out.println("OmniLinkClient closing communication");
            try {
                if (useLogin) {
                    comm.execute(new LogoutControl());
                }
            } catch (Throwable ignored) {
                // best effort
            }
            try {
                comm.close();
            } catch (Throwable ignored) {
                // best effort
            }
        }
    }

    public static final class UnitInfo {
        private final int number;
        private final String friendlyName;

        public UnitInfo(int number, String friendlyName) {
            this.number = number;
            this.friendlyName = friendlyName != null ? friendlyName : "Unit " + number;
        }

        public int getNumber() {
            return number;
        }

        public String getFriendlyName() {
            return friendlyName;
        }

        public String getEndpointId() {
            return "unit-" + number;
        }

        public static int unitNumberFromEndpointId(String endpointId) {
            if (endpointId != null && endpointId.startsWith("unit-")) {
                return Integer.parseInt(endpointId.substring("unit-".length()));
            }
            throw new IllegalArgumentException("Invalid endpoint id: " + endpointId);
        }
    }
}
