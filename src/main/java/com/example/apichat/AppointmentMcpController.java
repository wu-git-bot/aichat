package com.example.apichat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/mcp")
public class AppointmentMcpController {

    private final AppointmentToolService appointmentToolService;
    private final ObjectMapper objectMapper;

    public AppointmentMcpController(AppointmentToolService appointmentToolService,
                                    ObjectMapper objectMapper) {
        this.appointmentToolService = appointmentToolService;
        this.objectMapper = objectMapper;
    }

    @GetMapping({"", "/"})
    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", "appointment-mcp");
        result.put("status", "ok");
        result.put("message", "Use POST /mcp with JSON-RPC methods: initialize, tools/list, tools/call");
        return result;
    }

    @PostMapping({"", "/"})
    public ResponseEntity<Map<String, Object>> handle(@RequestBody Map<String, Object> request) {
        Object id = request.get("id");
        String method = String.valueOf(request.getOrDefault("method", ""));

        try {
            switch (method) {
                case "initialize":
                    return ResponseEntity.ok(success(id, buildInitializeResult()));
                case "notifications/initialized":
                    return ResponseEntity.ok(success(id, Map.of()));
                case "tools/list":
                    return ResponseEntity.ok(success(id, buildToolListResult()));
                case "tools/call":
                    return ResponseEntity.ok(success(id, handleToolCall(request)));
                default:
                    return ResponseEntity.ok(error(id, -32601, "Method not found: " + method));
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(error(id, -32602, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(error(id, -32000, e.getMessage()));
        }
    }

    private Map<String, Object> handleToolCall(Map<String, Object> request) {
        Map<String, Object> params = asMap(request.get("params"));
        String toolName = String.valueOf(params.getOrDefault("name", ""));
        Map<String, Object> args = asArgsMap(params.get("arguments"));

        switch (toolName) {
            case "create_appointment":
                return okTool(appointmentToolService.createAppointment(args));
            case "list_appointments":
                return okTool(appointmentToolService.listAppointments(args));
            case "reschedule_appointment":
                return okTool(appointmentToolService.rescheduleAppointment(args));
            case "cancel_appointment":
                return okTool(appointmentToolService.cancelAppointment(args));
            case "suggest_appointment_slots":
                return okTool(appointmentToolService.suggestSlots(args));
            default:
                return errTool("Unknown tool: " + toolName);
        }
    }

    private Map<String, Object> buildInitializeResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2024-11-05");
        result.put("capabilities", Map.of("tools", Map.of()));
        result.put("serverInfo", Map.of("name", "appointment-mcp", "version", "1.0.0"));
        return result;
    }

    private Map<String, Object> buildToolListResult() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool(
                "create_appointment",
                "Create a new appointment",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string"),
                                "email", Map.of("type", "string"),
                                "date", Map.of("type", "string", "description", "yyyy-MM-dd"),
                                "time", Map.of("type", "string"),
                                "reason", Map.of("type", "string"),
                                "expert", Map.of("type", "string")
                        ),
                        "required", List.of("date", "time")
                )));
        tools.add(tool(
                "list_appointments",
                "List appointments by optional date/status filter",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "id", Map.of("type", "string"),
                                "date", Map.of("type", "string", "description", "yyyy-MM-dd"),
                                "time", Map.of("type", "string"),
                                "status", Map.of("type", "string", "description", "BOOKED or CANCELED"),
                                "email", Map.of("type", "string"),
                                "name", Map.of("type", "string")
                        )
                )));
        tools.add(tool(
                "reschedule_appointment",
                "Reschedule an existing appointment",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "id", Map.of("type", "integer"),
                                "date", Map.of("type", "string", "description", "yyyy-MM-dd"),
                                "time", Map.of("type", "string"),
                                "expert", Map.of("type", "string")
                        ),
                        "required", List.of("id", "date", "time")
                )));
        tools.add(tool(
                "cancel_appointment",
                "Cancel an appointment",
                Map.of(
                        "type", "object",
                        "properties", Map.of("id", Map.of("type", "integer")),
                        "required", List.of("id")
                )));
        tools.add(tool(
                "suggest_appointment_slots",
                "Suggest available slots",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "date", Map.of("type", "string", "description", "yyyy-MM-dd"),
                                "fromTime", Map.of("type", "string"),
                                "expert", Map.of("type", "string"),
                                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 10)
                        ),
                        "required", List.of("date")
                )));
        return Map.of("tools", tools);
    }

    private Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("description", description);
        map.put("inputSchema", inputSchema);
        return map;
    }


    private Map<String, Object> success(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    private Map<String, Object> okTool(Object payload) {
        return Map.of("content", List.of(Map.of("type", "text", "text", asJson(payload))));
    }

    private Map<String, Object> errTool(String message) {
        return Map.of(
                "isError", true,
                "content", List.of(Map.of("type", "text", "text", message))
        );
    }

    private String asJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?>) {
            return (Map<String, Object>) value;
        }
        return Map.of();
    }

    private Map<String, Object> asArgsMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?>) {
            return asMap(value);
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return Map.of();
            }
            try {
                return objectMapper.readValue(trimmed, Map.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("invalid arguments json");
            }
        }
        throw new IllegalArgumentException("arguments must be object or json string");
    }

}
