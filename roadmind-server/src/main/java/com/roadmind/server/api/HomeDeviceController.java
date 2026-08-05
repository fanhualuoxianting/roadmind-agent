package com.roadmind.server.api;

import com.roadmind.server.home.HomeDeviceService;
import com.roadmind.server.home.HomeDeviceSnapshot;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/home-devices")
public class HomeDeviceController {

    private final HomeDeviceService service;

    public HomeDeviceController(HomeDeviceService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<HomeDeviceSnapshot>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{deviceId}")
    ApiResponse<HomeDeviceSnapshot> get(@PathVariable String deviceId) {
        return ApiResponse.ok(service.get(deviceId));
    }
}
