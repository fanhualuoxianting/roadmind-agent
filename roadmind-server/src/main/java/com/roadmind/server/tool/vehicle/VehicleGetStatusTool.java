package com.roadmind.server.tool.vehicle;

import com.roadmind.server.tool.RetryPolicy;
import com.roadmind.server.tool.RoadMindTool;
import com.roadmind.server.tool.ToolDependencyException;
import com.roadmind.server.tool.ToolDescriptor;
import com.roadmind.server.tool.ToolExecutionContext;
import com.roadmind.server.tool.ToolRiskLevel;
import com.roadmind.server.vehicle.application.VehicleApplicationService;
import com.roadmind.server.vehicle.domain.VehicleGatewayUnavailableException;
import com.roadmind.server.vehicle.domain.VehicleStatus;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class VehicleGetStatusTool implements RoadMindTool<VehicleGetStatusTool.Input, VehicleStatus> {

    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "vehicle.get_status",
            "1.0.0",
            "读取数字孪生车辆的电量、续航、温度、门锁和位置状态",
            ToolRiskLevel.READ_ONLY,
            Duration.ofSeconds(2),
            RetryPolicy.none(),
            true,
            "classpath:/tool-schemas/vehicle.get_status.json");

    private final VehicleApplicationService vehicles;

    public VehicleGetStatusTool(VehicleApplicationService vehicles) {
        this.vehicles = vehicles;
    }

    @Override
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public VehicleStatus execute(Input input, ToolExecutionContext context) {
        try {
            return vehicles.getStatus(input.vehicleId());
        } catch (VehicleGatewayUnavailableException exception) {
            throw new ToolDependencyException(
                    "VEHICLE_SIMULATOR_UNAVAILABLE",
                    "车辆数字孪生模拟器暂时离线",
                    true,
                    exception);
        }
    }

    public record Input(@NotBlank String vehicleId) {
    }
}
