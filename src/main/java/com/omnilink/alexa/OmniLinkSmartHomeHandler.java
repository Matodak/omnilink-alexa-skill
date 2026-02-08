package com.omnilink.alexa;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import net.homeip.mleclerc.omnilink.CommunicationException;
import net.homeip.mleclerc.omnilink.enumeration.UnitControlEnum;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AWS Lambda handler for Alexa Smart Home skill that controls HAI Omni IIe units
 * via the OmniLink API (lib/omnilink.jar).
 * <p>
 * Environment variables: OMNI_HOST, OMNI_PORT (default 4369), OMNI_PRIVATE_KEY,
 * OMNI_LOGIN_CODE (for HAI_OMNI_LINK), OMNI_PROTOCOL (HAI_OMNI_LINK or HAI_OMNI_LINK_II),
 * OMNI_SYSTEM_TYPE (default AEGIS_2000).
 */
public class OmniLinkSmartHomeHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        if (context != null) {
            context.getLogger().log("Lambda invoked");
        }
        if (input == null || input.isEmpty()) {
            if (context != null) context.getLogger().log("Empty request body");
            return buildErrorResponse(null, "INVALID_DIRECTIVE", "Empty request");
        }

        Object dirObj = input.get("directive");
        if (!(dirObj instanceof Map)) {
            if (context != null) context.getLogger().log("Missing directive");
            return buildErrorResponse(null, "INVALID_DIRECTIVE", "Missing directive");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> directive = (Map<String, Object>) dirObj;

        String namespace = getHeaderString(directive, "namespace");
        String name = getHeaderString(directive, "name");
        if (context != null) {
            context.getLogger().log("Directive: " + namespace + "." + name);
        }
        String messageId = getHeaderString(directive, "messageId");
        String correlationToken = getHeaderString(directive, "correlationToken");
        Map<String, Object> endpoint = getEndpoint(directive);
        String endpointId = endpoint != null ? getString(endpoint, "endpointId") : null;

        try {
            if ("Alexa.Discovery".equals(namespace) && "Discover".equals(name)) {
                return handleDiscover(messageId, context);
            }
            if ("Alexa.PowerController".equals(namespace)) {
                if ("TurnOn".equals(name)) {
                    return handlePowerController(endpointId, true, messageId, correlationToken, endpoint);
                }
                if ("TurnOff".equals(name)) {
                    return handlePowerController(endpointId, false, messageId, correlationToken, endpoint);
                }
            }
            if ("Alexa".equals(namespace) && "ReportState".equals(name)) {
                return handleReportState(endpointId, messageId, correlationToken, endpoint);
            }
            if ("Alexa.PowerController".equals(namespace) && "ReportState".equals(name)) {
                return handleReportState(endpointId, messageId, correlationToken, endpoint);
            }
            return buildErrorResponse(messageId, "INVALID_DIRECTIVE", "Unsupported: " + namespace + "." + name);
        } catch (CommunicationException e) {
            if (context != null) {
                context.getLogger().log("OmniLink error: " + e.getMessage());
            }
            return buildErrorResponse(messageId, "BRIDGE_UNREACHABLE", e.getMessage());
        } catch (Exception e) {
            if (context != null) {
                context.getLogger().log("Error: " + e.getMessage());
            }
            return buildErrorResponse(messageId, "INTERNAL_ERROR", e.getMessage());
        }
    }

    private Map<String, Object> handleDiscover(String messageId, Context context) throws CommunicationException, Exception {
        List<Map<String, Object>> endpoints = new ArrayList<>();
        if (context != null) context.getLogger().log("Discovery: connecting to Omni");
        try (OmniLinkClient client = OmniLinkClient.fromEnvironment()) {
            List<OmniLinkClient.UnitInfo> units = client.discoverUnits();
            if (context != null) context.getLogger().log("Discovery: found " + units.size() + " units");
            for (OmniLinkClient.UnitInfo unit : units) {
                Map<String, Object> ep = new HashMap<>();
                ep.put("endpointId", unit.getEndpointId());
                ep.put("manufacturerName", "HAI / Leviton");
                ep.put("description", "Omni IIe unit: " + unit.getFriendlyName());
                ep.put("friendlyName", unit.getFriendlyName());
                ep.put("displayCategories", List.of("LIGHT"));
                ep.put("cookie", Map.of());
                ep.put("capabilities", List.of(
                    powerControllerCapability(),
                    alexaInterfaceCapability(),
                    endpointHealthCapability()
                ));
                endpoints.add(ep);
            }
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("endpoints", endpoints);

        if (context != null && endpoints.isEmpty()) {
            context.getLogger().log("Discovery: returning 0 endpoints (Alexa will say no devices found)");
        }
        return buildEvent("Alexa.Discovery", "Discover.Response", messageId, payload, null);
    }

    private Map<String, Object> handlePowerController(String endpointId, boolean turnOn, String messageId,
                                          String correlationToken, Map<String, Object> endpoint) throws CommunicationException, Exception {
        int unitNumber = OmniLinkClient.UnitInfo.unitNumberFromEndpointId(endpointId);
        UnitControlEnum desiredState = turnOn ? UnitControlEnum.ON : UnitControlEnum.OFF;
        try (OmniLinkClient client = OmniLinkClient.fromEnvironment()) {
            client.setUnitPower(unitNumber, desiredState);
        }
        String powerState = desiredState.toString().toUpperCase(); 
        List<Map<String, Object>> properties = List.of(
            property("Alexa.PowerController", "powerState", powerState)
        );
        return buildResponse(messageId, correlationToken, endpoint, properties);
    }

    private Map<String, Object> handleReportState(String endpointId, String messageId, String correlationToken,
                                     Map<String, Object> endpoint) throws CommunicationException, Exception {
        int unitNumber = OmniLinkClient.UnitInfo.unitNumberFromEndpointId(endpointId);
        String powerState;
        try (OmniLinkClient client = OmniLinkClient.fromEnvironment()) {
            UnitControlEnum state = client.getUnitState(unitNumber);
            powerState = state.toString().toUpperCase();
        }
        List<Map<String, Object>> properties = List.of(
            property("Alexa.PowerController", "powerState", powerState)
        );
        return buildStateReport(messageId, correlationToken, endpoint, properties);
    }

    private static Map<String, Object> powerControllerCapability() {
        Map<String, Object> props = new HashMap<>();
        props.put("supported", List.of(Map.of("name", "powerState")));
        props.put("proactivelyReported", true);
        props.put("retrievable", true);
        return Map.of(
            "type", "AlexaInterface",
            "interface", "Alexa.PowerController",
            "version", "3",
            "properties", props
        );
    }

    private static Map<String, Object> endpointHealthCapability() {
        Map<String, Object> props = new HashMap<>();
        props.put("supported", List.of(Map.of("name", "connectivity")));
        props.put("proactivelyReported", true);
        props.put("retrievable", true);
        return Map.of(
            "type", "AlexaInterface",
            "interface", "Alexa.EndpointHealth",
            "version", "3.1",
            "properties", props
        );
    }

    private static Map<String, Object> alexaInterfaceCapability() {
        return Map.of(
            "type", "AlexaInterface",
            "interface", "Alexa",
            "version", "3"
        );
    }

    private static Map<String, Object> property(String namespace, String name, String value) {
        return Map.of(
            "namespace", namespace,
            "name", name,
            "value", value,
            "timeOfSample", Instant.now().toString(),
            "uncertaintyInMilliseconds", 0
        );
    }

    private Map<String, Object> buildResponse(String messageId, String correlationToken, Map<String, Object> endpoint,
                                 List<Map<String, Object>> contextProperties) {
        Map<String, Object> header = new HashMap<>();
        header.put("namespace", "Alexa");
        header.put("name", "Response");
        header.put("messageId", uuid());
        if (correlationToken != null) header.put("correlationToken", correlationToken);
        header.put("payloadVersion", "3");

        Map<String, Object> payload = new HashMap<>();
        Map<String, Object> endpointMap = endpointToMap(endpoint);

        Map<String, Object> event = new HashMap<>();
        event.put("header", header);
        if (endpointMap != null) event.put("endpoint", endpointMap);
        event.put("payload", payload);
        Map<String, Object> context = new HashMap<>();
        context.put("properties", contextProperties);
        return Map.of("event", event, "context", context);
    }

    private Map<String, Object> buildStateReport(String messageId, String correlationToken, Map<String, Object> endpoint,
                                    List<Map<String, Object>> contextProperties) {
        Map<String, Object> header = new HashMap<>();
        header.put("namespace", "Alexa");
        header.put("name", "StateReport");
        header.put("messageId", uuid());
        if (correlationToken != null) header.put("correlationToken", correlationToken);
        header.put("payloadVersion", "3");

        Map<String, Object> payload = new HashMap<>();
        Map<String, Object> endpointMap = endpointToMap(endpoint);
        Map<String, Object> event = new HashMap<>();
        event.put("header", header);
        event.put("endpoint", endpointMap);
        event.put("payload", payload);
        Map<String, Object> context = new HashMap<>();
        context.put("properties", contextProperties);
        return Map.of("event", event, "context", context);
    }

    private Map<String, Object> buildEvent(String namespace, String name, String messageId,
                              Map<String, Object> payload, Map<String, Object> context) {
        Map<String, Object> header = new HashMap<>();
        header.put("namespace", namespace);
        header.put("name", name);
        header.put("messageId", messageId != null ? messageId : uuid());
        header.put("payloadVersion", "3");

        Map<String, Object> event = new HashMap<>();
        event.put("header", header);
        event.put("payload", payload);
        if (context != null) {
            return Map.of("event", event, "context", context);
        }
        return Map.of("event", event);
    }

    private Map<String, Object> buildErrorResponse(String messageId, String type, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", type);
        payload.put("message", message);
        Map<String, Object> header = new HashMap<>();
        header.put("namespace", "Alexa");
        header.put("name", "ErrorResponse");
        header.put("messageId", messageId != null ? messageId : uuid());
        header.put("payloadVersion", "3");
        Map<String, Object> event = new HashMap<>();
        event.put("header", header);
        event.put("payload", payload);
        return Map.of("event", event);
    }

    private static Map<String, Object> getEndpoint(Map<String, Object> directive) {
        if (directive == null) return null;
        Object endObj = directive.get("endpoint");
        if (endObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) endObj;
            return m;
        }
        return null;
    }

    private static Map<String, Object> endpointToMap(Map<String, Object> endpoint) {
        if (endpoint == null) return null;
        Map<String, Object> m = new HashMap<>();
        if (endpoint.get("scope") != null) m.put("scope", endpoint.get("scope"));
        String eid = getString(endpoint, "endpointId");
        if (eid != null) m.put("endpointId", eid);
        return m.isEmpty() ? null : m;
    }

    private static String getHeaderString(Map<String, Object> directive, String key) {
        if (directive == null) return null;
        Object headerObj = directive.get("header");
        if (!(headerObj instanceof Map)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> header = (Map<String, Object>) headerObj;
        return getString(header, key);
    }

    private static String getString(Map<String, Object> n, String key) {
        if (n == null) return null;
        Object v = n.get(key);
        return v != null ? v.toString() : null;
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
