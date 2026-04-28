package com.example.apichat;

import com.alibaba.cloud.ai.dashscope.agent.DashScopeAgent;
import com.alibaba.cloud.ai.dashscope.agent.DashScopeAgentOptions;
import com.alibaba.cloud.ai.dashscope.api.DashScopeAgentApi;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/ai")
public class BailianAgentStreamController {

    private static final Logger logger = LoggerFactory.getLogger(BailianAgentStreamController.class);

    private static final List<String> TIME_SLOTS = List.of(
            "8:00-9:00",
            "9:00-10:00",
            "10:00-11:00",
            "14:00-15:00",
            "15:00-16:00",
            "16:00-17:00"
    );

    private static final Pattern EMAIL_PATTERN = Pattern.compile("([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})");
    private static final Pattern CN_DATE_PATTERN = Pattern.compile("(\\d{1,2})月(\\d{1,2})[日号]?");
    private static final Pattern NAME_PATTERN = Pattern.compile("(?:我叫|我是|姓名[:：]?)([\\u4e00-\\u9fa5A-Za-z]{2,20})");
    private static final Pattern EXPERT_PATTERN = Pattern.compile("(?:专家|医生|咨询师)[:：]?([\\u4e00-\\u9fa5A-Za-z]{2,20})");
    private static final Pattern REASON_PATTERN = Pattern.compile("(?:原因|事由|咨询内容)[:：]\\s*([^，。,.]{2,80})");
    private static final Pattern ID_PATTERN = Pattern.compile("(?:编号|id)[:： ]?(\\d+)", Pattern.CASE_INSENSITIVE);

    private final DashScopeAgent agent;
    private final AppointmentMcpController appointmentMcpController;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.dashscope.agent.app-id}")
    private String appId;

    public BailianAgentStreamController(DashScopeAgentApi dashscopeAgentApi,
                                        AppointmentMcpController appointmentMcpController,
                                        ObjectMapper objectMapper) {
        this.agent = new DashScopeAgent(
                dashscopeAgentApi,
                DashScopeAgentOptions.builder()
                        .withSessionId("session-001")
                        .withIncrementalOutput(true)
                        .withHasThoughts(false)
                        .build()
        );
        this.appointmentMcpController = appointmentMcpController;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/bailian/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(value = "message", defaultValue = "hello") String message) {
        SseEmitter emitter = new SseEmitter(0L);

        if (isQueryIntent(message)) {
            handleQueryIntent(emitter);
            return emitter;
        }
        if (isCancelIntent(message)) {
            handleCancelIntent(message, emitter);
            return emitter;
        }
        if (isRescheduleIntent(message)) {
            handleRescheduleIntent(message, emitter);
            return emitter;
        }
        if (isCreateIntent(message)) {
            handleCreateIntent(message, emitter);
            return emitter;
        }

        try {
            Prompt prompt = new Prompt(message, DashScopeAgentOptions.builder().withAppId(appId).build());
            agent.stream(prompt).subscribe(
                    response -> {
                        try {
                            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                                logger.warn("empty stream response from model");
                                return;
                            }

                            String content = response.getResult().getOutput().getText();
                            if (content == null || content.trim().isEmpty()) {
                                return;
                            }

                            emitter.send(SseEmitter.event().name("chunk").data(content));
                        } catch (Exception e) {
                            logger.error("failed to send sse chunk", e);
                            sendModelError(emitter, "stream output failed: " + e.getMessage());
                        }
                    },
                    error -> {
                        logger.error("agent stream failed", error);
                        sendModelError(emitter, "model call failed: " + error.getClass().getSimpleName() + " - " + error.getMessage());
                    },
                    () -> sendDone(emitter)
            );
        } catch (Exception e) {
            logger.error("failed to start stream", e);
            sendModelError(emitter, "model start failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }

        return emitter;
    }

    private void handleQueryIntent(SseEmitter emitter) {
        try {
            List<Map<String, Object>> appointments = findCurrentUserAppointments();
            if (appointments.isEmpty()) {
                sendMessage(emitter, "当前没有查到你的预约记录。");
                return;
            }

            String content = appointments.stream()
                    .sorted(Comparator.comparing(item -> String.valueOf(item.get("date")) + String.valueOf(item.get("time"))))
                    .map(item -> "编号:" + item.get("id")
                            + " 日期:" + item.get("date")
                            + " 时间:" + item.get("time")
                            + " 专家:" + safe(item.get("expert"))
                            + " 状态:" + safe(item.get("status"))
                            + " 原因:" + safe(item.get("reason")))
                    .collect(Collectors.joining(" | "));
            sendMessage(emitter, "查到这些预约: " + content);
        } catch (IllegalArgumentException e) {
            sendMessage(emitter, "查询失败: " + e.getMessage());
        }
    }

    private void handleCreateIntent(String message, SseEmitter emitter) {
        AppointmentDraft draft = parseAppointmentDraft(message);
        List<String> missing = draft.missingFields();
        if (!missing.isEmpty()) {
            sendMessage(emitter, "可以帮你预约，但还缺这些信息: " + String.join("、", missing)
                    + "。请按这个格式补充: 姓名:张三 邮箱:zhangsan@example.com 日期:2026-04-08 时间:9:00-10:00 原因:焦虑咨询");
            return;
        }

        try {
            Map<String, Object> result = callMcpMap("create_appointment", draft.toArgsMap());
            if (hasSuggestions(result)) {
                sendMessage(emitter, buildSuggestionMessage(String.valueOf(result.get("message")), result.get("suggestions")));
                return;
            }

            sendMessage(emitter, "预约已创建。编号:" + result.get("id")
                    + "，日期:" + result.get("date")
                    + "，时间:" + result.get("time")
                    + "，专家:" + result.get("expert")
                    + "，状态:" + result.get("status") + "。");
        } catch (IllegalArgumentException e) {
            sendMessage(emitter, "预约失败: " + e.getMessage());
        }
    }

    private void handleCancelIntent(String message, SseEmitter emitter) {
        try {
            Map<String, Object> target = resolveTargetAppointment(message, true);
            if (target == null) {
                sendMessage(emitter, "没有匹配到可取消的预约。请提供预约编号，或先说“查询我的预约”。");
                return;
            }
            if (!"BOOKED".equals(String.valueOf(target.get("status")))) {
                sendMessage(emitter, "这个预约当前状态是 " + safe(target.get("status")) + "，不能重复取消。");
                return;
            }

            Map<String, Object> result = callMcpMap("cancel_appointment", Map.of("id", target.get("id")));
            sendMessage(emitter, "预约已取消。编号:" + result.get("id")
                    + "，日期:" + result.get("date")
                    + "，时间:" + result.get("time") + "。");
        } catch (IllegalArgumentException e) {
            sendMessage(emitter, "取消失败: " + e.getMessage());
        }
    }

    private void handleRescheduleIntent(String message, SseEmitter emitter) {
        try {
            RescheduleSegments segments = splitRescheduleMessage(message);
            Map<String, Object> target = resolveTargetAppointment(segments.currentPart, true);
            if (target == null) {
                sendMessage(emitter, "没有匹配到可改期的预约。请提供预约编号，或先说“查询我的预约”。");
                return;
            }
            if (!"BOOKED".equals(String.valueOf(target.get("status")))) {
                sendMessage(emitter, "这个预约当前状态是 " + safe(target.get("status")) + "，不能改期。");
                return;
            }

            AppointmentDraft draft = parseAppointmentDraft(segments.targetPart);
            if (draft.date == null || draft.time == null || draft.time.isBlank()) {
                sendMessage(emitter, "改期需要新的日期和时间段。请按这个格式补充: 编号:"
                        + target.get("id") + " 日期:2026-04-09 时间:14:00-15:00");
                return;
            }

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("id", target.get("id"));
            args.put("date", draft.date.toString());
            args.put("time", draft.time);
            args.put("expert", draft.expert.isBlank() ? safe(target.get("expert")) : draft.expert);

            Map<String, Object> result = callMcpMap("reschedule_appointment", args);
            if (hasSuggestions(result)) {
                sendMessage(emitter, buildSuggestionMessage(String.valueOf(result.get("message")), result.get("suggestions")));
                return;
            }

            sendMessage(emitter, "预约已改期。编号:" + result.get("id")
                    + "，新日期:" + result.get("date")
                    + "，新时间:" + result.get("time")
                    + "，专家:" + result.get("expert") + "。");
        } catch (IllegalArgumentException e) {
            sendMessage(emitter, "改期失败: " + e.getMessage());
        }
    }

    private Map<String, Object> resolveTargetAppointment(String message, boolean bookedOnly) {
        Long id = extractId(message);
        if (id != null) {
            Map<String, Object> byId = findCurrentUserAppointments().stream()
                    .filter(item -> String.valueOf(id).equals(String.valueOf(item.get("id"))))
                    .findFirst()
                    .orElse(null);
            if (byId != null) {
                return byId;
            }
        }

        AppointmentDraft draft = parseAppointmentDraft(message);
        List<Map<String, Object>> mine = new ArrayList<>(findCurrentUserAppointments());
        if (bookedOnly) {
            mine = mine.stream()
                    .filter(item -> "BOOKED".equals(String.valueOf(item.get("status"))))
                    .collect(Collectors.toList());
        }
        if (draft.date != null) {
            mine = mine.stream()
                    .filter(item -> draft.date.toString().equals(String.valueOf(item.get("date"))))
                    .collect(Collectors.toList());
        }
        if (draft.time != null && !draft.time.isBlank()) {
            mine = mine.stream()
                    .filter(item -> draft.time.equals(String.valueOf(item.get("time"))))
                    .collect(Collectors.toList());
        }

        return mine.size() == 1 ? mine.get(0) : null;
    }

    private List<Map<String, Object>> findCurrentUserAppointments() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return List.of();
        }

        String principal = authentication.getName().trim();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ownerUsername", principal);
        return callMcpList("list_appointments", args);
    }

    private Map<String, Object> callMcpMap(String toolName, Map<String, Object> arguments) {
        Object payload = callMcp(toolName, arguments);
        if (!(payload instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("unexpected MCP result");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) payload;
        return map;
    }

    private List<Map<String, Object>> callMcpList(String toolName, Map<String, Object> arguments) {
        Object payload = callMcp(toolName, arguments);
        if (!(payload instanceof List<?>)) {
            throw new IllegalArgumentException("unexpected MCP result");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) payload;
        return list;
    }

    private Object callMcp(String toolName, Map<String, Object> arguments) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", "chat-" + System.nanoTime());
        request.put("method", "tools/call");
        request.put("params", Map.of(
                "name", toolName,
                "arguments", arguments
        ));

        ResponseEntity<Map<String, Object>> response = appointmentMcpController.handle(request);
        Map<String, Object> body = response.getBody();
        if (body == null) {
            throw new IllegalArgumentException("empty MCP response");
        }
        if (body.containsKey("error")) {
            Object error = body.get("error");
            throw new IllegalArgumentException(String.valueOf(error));
        }

        Object result = body.get("result");
        if (!(result instanceof Map<?, ?> resultMap)) {
            throw new IllegalArgumentException("missing MCP result");
        }

        if (Boolean.TRUE.equals(resultMap.get("isError"))) {
            throw new IllegalArgumentException(extractToolText(resultMap));
        }

        String text = extractToolText(resultMap);
        try {
            return objectMapper.readValue(text, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid MCP payload");
        }
    }

    @SuppressWarnings("unchecked")
    private String extractToolText(Map<?, ?> resultMap) {
        Object content = resultMap.get("content");
        if (!(content instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("missing MCP content");
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> item)) {
            throw new IllegalArgumentException("invalid MCP content");
        }
        Object text = item.get("text");
        if (text == null) {
            throw new IllegalArgumentException("missing MCP text");
        }
        return String.valueOf(text);
    }

    private RescheduleSegments splitRescheduleMessage(String message) {
        if (message == null || message.isBlank()) {
            return new RescheduleSegments("", "");
        }

        String[] separators = {"改到", "改成", "换到", "调整到", "reschedule to", "change to"};
        String lowered = message.toLowerCase(Locale.ROOT);
        for (String separator : separators) {
            int index = lowered.indexOf(separator.toLowerCase(Locale.ROOT));
            if (index >= 0) {
                String currentPart = message.substring(0, index).trim();
                String targetPart = message.substring(index + separator.length()).trim();
                return new RescheduleSegments(currentPart, targetPart);
            }
        }

        return new RescheduleSegments(message, message);
    }

    private AppointmentDraft parseAppointmentDraft(String message) {
        AppointmentDraft draft = new AppointmentDraft();
        draft.date = extractDate(message);
        draft.time = extractTimeSlot(message);
        draft.email = extractEmail(message);
        draft.name = extractName(message);
        draft.reason = extractReason(message);
        draft.expert = extractExpert(message);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getName() != null) {
            String username = authentication.getName().trim();
            if ((draft.email == null || draft.email.isBlank()) && username.contains("@")) {
                draft.email = username;
            }
            if (draft.name == null || draft.name.isBlank()) {
                draft.name = username;
            }
        }

        if (draft.reason == null || draft.reason.isBlank()) {
            draft.reason = "在线预约";
        }
        if (draft.expert == null) {
            draft.expert = "";
        }
        return draft;
    }

    private boolean isCreateIntent(String message) {
        return containsAny(message, "预约", "预定", "挂号", "约一个", "帮我约", "appointment", "book", "booking")
                && !isCancelIntent(message) && !isRescheduleIntent(message) && !isQueryIntent(message);
    }

    private boolean isQueryIntent(String message) {
        return containsAny(message, "查预约", "查询预约", "我的预约", "预约记录", "帮我查", "list appointments", "my appointment");
    }

    private boolean isCancelIntent(String message) {
        return containsAny(message, "取消预约", "取消我的预约", "撤销预约", "cancel appointment", "cancel booking");
    }

    private boolean isRescheduleIntent(String message) {
        return containsAny(message, "改期", "改到", "改成", "换到", "换个时间", "reschedule", "change appointment");
    }

    private boolean containsAny(String message, String... keywords) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private LocalDate extractDate(String message) {
        if (message == null) {
            return null;
        }

        Matcher iso = ISO_DATE_PATTERN.matcher(message);
        if (iso.find()) {
            try {
                return LocalDate.parse(iso.group(1));
            } catch (Exception ignored) {
            }
        }

        LocalDate today = LocalDate.now();
        if (message.contains("后天")) {
            return today.plusDays(2);
        }
        if (message.contains("明天")) {
            return today.plusDays(1);
        }
        if (message.contains("今天")) {
            return today;
        }

        Matcher cn = CN_DATE_PATTERN.matcher(message);
        if (cn.find()) {
            try {
                int month = Integer.parseInt(cn.group(1));
                int day = Integer.parseInt(cn.group(2));
                LocalDate candidate = LocalDate.of(today.getYear(), month, day);
                if (candidate.isBefore(today.minusDays(1))) {
                    candidate = candidate.plusYears(1);
                }
                return candidate;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String extractTimeSlot(String message) {
        if (message == null) {
            return null;
        }
        for (String slot : TIME_SLOTS) {
            if (message.contains(slot)) {
                return slot;
            }
        }
        if (message.contains("8点")) {
            return "8:00-9:00";
        }
        if (message.contains("9点")) {
            return "9:00-10:00";
        }
        if (message.contains("10点")) {
            return "10:00-11:00";
        }
        if (message.contains("14点") || message.contains("下午2点")) {
            return "14:00-15:00";
        }
        if (message.contains("15点") || message.contains("下午3点")) {
            return "15:00-16:00";
        }
        if (message.contains("16点") || message.contains("下午4点")) {
            return "16:00-17:00";
        }
        return null;
    }

    private String extractEmail(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = EMAIL_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractName(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = NAME_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractExpert(String message) {
        if (message == null) {
            return "";
        }
        Matcher matcher = EXPERT_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String extractReason(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = REASON_PATTERN.matcher(message);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        if (message.contains("焦虑")) {
            return "焦虑咨询";
        }
        if (message.contains("失眠")) {
            return "失眠咨询";
        }
        if (message.contains("抑郁")) {
            return "情绪咨询";
        }
        return null;
    }

    private Long extractId(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = ID_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean hasSuggestions(Map<String, Object> result) {
        return result != null && result.containsKey("suggestions");
    }

    @SuppressWarnings("unchecked")
    private String buildSuggestionMessage(String prefix, Object suggestionsValue) {
        List<Map<String, String>> suggestions = suggestionsValue instanceof List<?> ? (List<Map<String, String>>) suggestionsValue : List.of();
        if (suggestions.isEmpty()) {
            return prefix + " 暂时没有可推荐的备选时段。";
        }
        String suggestionText = suggestions.stream()
                .map(item -> item.get("date") + " " + item.get("time") + " (" + item.get("expert") + ")")
                .collect(Collectors.joining(" | "));
        return prefix + " 可选时段: " + suggestionText;
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void sendMessage(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("chunk").data(message));
            sendDone(emitter);
        } catch (Exception e) {
            logger.error("failed to send message", e);
            emitter.complete();
        }
    }

    private void sendDone(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
        } catch (Exception ignored) {
        }
        emitter.complete();
    }

    private void sendModelError(SseEmitter emitter, String msg) {
        try {
            emitter.send(SseEmitter.event().name("model_error").data(msg));
        } catch (Exception ignored) {
        }
        emitter.complete();
    }

    private static class AppointmentDraft {
        private String name;
        private String email;
        private LocalDate date;
        private String time;
        private String reason;
        private String expert;

        private List<String> missingFields() {
            List<String> missing = new ArrayList<>();
            if (name == null || name.isBlank()) {
                missing.add("姓名");
            }
            if (email == null || email.isBlank()) {
                missing.add("邮箱");
            }
            if (date == null) {
                missing.add("日期");
            }
            if (time == null || time.isBlank()) {
                missing.add("时间段");
            }
            return missing;
        }

        private Map<String, Object> toArgsMap() {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("name", name);
            args.put("email", email);
            args.put("date", date == null ? "" : date.toString());
            args.put("time", time);
            args.put("reason", reason);
            args.put("expert", expert);
            return args;
        }
    }

    private static class RescheduleSegments {
        private final String currentPart;
        private final String targetPart;

        private RescheduleSegments(String currentPart, String targetPart) {
            this.currentPart = currentPart;
            this.targetPart = targetPart;
        }
    }
}
