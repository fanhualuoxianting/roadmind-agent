package com.roadmind.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.roadmind.server.vehicle.application.VehicleApplicationService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class RuleStubPlanner {

    private static final Pattern ROUTE_PATTERN = Pattern.compile(
            "从([^，。,.]{2,30}?)(?:出发)?(?:去|前往|到)([^，。,.]{2,30})");

    private final ObjectMapper objectMapper;

    public RuleStubPlanner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ModelToolPlan plan(PlanningRequest request) {
        String message = request.message();
        RouteEntities route = extractRoute(message);
        List<ModelToolCall> calls = new ArrayList<>();

        boolean tripIntent = route != null || containsAny(message, "出发", "行程", "路线", "去", "导航");
        if (tripIntent || containsAny(message, "车辆", "车", "电量", "续航", "空调")) {
            ObjectNode arguments = objectMapper.createObjectNode()
                    .put("vehicleId", VehicleApplicationService.API_VEHICLE_ID);
            calls.add(new ModelToolCall("vehicle.get_status", arguments));
        }

        if (containsAny(message, "天气", "冷", "热", "温度", "明天", "下雨")) {
            String location = route == null ? "南京" : route.destination();
            LocalDate date = containsAny(message, "明天")
                    ? LocalDate.now(request.timezone()).plusDays(1)
                    : LocalDate.now(request.timezone());
            ObjectNode arguments = objectMapper.createObjectNode()
                    .put("location", location)
                    .put("date", date.toString());
            calls.add(new ModelToolCall("weather.get_forecast", arguments));
        }

        if (tripIntent && route != null) {
            ObjectNode arguments = objectMapper.createObjectNode()
                    .put("origin", route.origin())
                    .put("destination", route.destination())
                    .put("avoidTraffic", containsAny(message, "避堵", "避开拥堵", "躲开拥堵"));
            calls.add(new ModelToolCall("route.plan", arguments));
        }

        String summary = route == null
                ? "读取当前出行相关状态"
                : "规划从" + route.origin() + "前往" + route.destination() + "的出行信息";
        String assistantMessage = containsAny(message, "打开空调", "关闭家", "关灯", "锁车")
                ? "本阶段只执行只读查询；车辆和家居写操作会在安全确认能力完成后处理。"
                : "已识别只读查询需求。";
        return new ModelToolPlan(summary, assistantMessage, calls);
    }

    private RouteEntities extractRoute(String message) {
        Matcher matcher = ROUTE_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        String origin = matcher.group(1).replaceFirst("^.*?(?=南京|无锡|苏州|上海|杭州)", "").trim();
        String destination = matcher.group(2).trim();
        return new RouteEntities(origin, destination);
    }

    private boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private record RouteEntities(String origin, String destination) {
    }
}
