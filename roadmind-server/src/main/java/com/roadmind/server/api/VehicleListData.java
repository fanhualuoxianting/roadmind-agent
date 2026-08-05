package com.roadmind.server.api;

import com.roadmind.server.vehicle.domain.VehicleSummary;
import java.util.List;

public record VehicleListData(
        List<VehicleSummary> items,
        String nextCursor) {
}
