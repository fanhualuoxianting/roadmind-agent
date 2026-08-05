package com.roadmind.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VehicleSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(VehicleSimulatorApplication.class, args);
    }
}
