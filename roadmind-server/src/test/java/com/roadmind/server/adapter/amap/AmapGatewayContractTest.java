package com.roadmind.server.adapter.amap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.roadmind.server.config.RoadMindExternalProperties;
import com.roadmind.server.route.RouteSummary;
import com.roadmind.server.trip.AmapTripRoutePlanner;
import com.roadmind.server.trip.RoutePlan;
import com.roadmind.server.weather.WeatherForecast;
import java.time.LocalDate;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AmapGatewayContractTest {

    private RoadMindExternalProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RoadMindExternalProperties();
        properties.setAmapBaseUrl("https://amap.example.test");
        properties.setAmapKey("test-key-not-a-secret");
    }

    @Test
    void mapsLiveWeatherAndKeepsSourceLabel() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(Matchers.containsString("/v3/weather/weatherInfo")))
                .andRespond(withSuccess("""
                        {"status":"1","forecasts":[{"city":"无锡市","casts":[
                          {"date":"2026-08-05","dayweather":"晴","nighttemp":"22","daytemp":"31"}
                        ]}]}
                        """, MediaType.APPLICATION_JSON));

        WeatherForecast forecast = new AmapWeatherGateway(builder, properties)
                .getForecast("无锡", LocalDate.parse("2026-08-05"));

        assertThat(forecast.sourceMode()).isEqualTo("LIVE");
        assertThat(forecast.provider()).isEqualTo("AMap Weather");
        assertThat(forecast.minimumCelsius()).isEqualTo(22);
        assertThat(forecast.maximumCelsius()).isEqualTo(31);
        server.verify();
    }

    @Test
    void geocodesBothEndsAndMapsLiveRouteSummary() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(Matchers.containsString("address=%E5%8D%97%E4%BA%AC")))
                .andRespond(withSuccess("""
                        {"status":"1","geocodes":[{"location":"118.7969,32.0603"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.containsString("address=%E6%97%A0%E9%94%A1")))
                .andRespond(withSuccess("""
                        {"status":"1","geocodes":[{"location":"120.3119,31.4912"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.containsString("/v3/direction/driving")))
                .andRespond(withSuccess("""
                        {"status":"1","route":{"paths":[{"distance":"169000","duration":"8280"}]}}
                        """, MediaType.APPLICATION_JSON));

        RouteSummary route = new AmapRouteGateway(builder, properties)
                .plan("南京", "无锡", true);

        assertThat(route.sourceMode()).isEqualTo("LIVE");
        assertThat(route.distanceKm()).isEqualTo(169.0);
        assertThat(route.durationMinutes()).isEqualTo(138);
        assertThat(route.trafficAvoidance()).isTrue();
        server.verify();
    }

    @Test
    void normalizesLiveDrivingPolylineForTheTripEngine() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(Matchers.containsString("address=%E5%8D%97%E4%BA%AC")))
                .andRespond(withSuccess("""
                        {"status":"1","geocodes":[{"location":"118.7969,32.0603"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.containsString("address=%E6%97%A0%E9%94%A1")))
                .andRespond(withSuccess("""
                        {"status":"1","geocodes":[{"location":"120.3119,31.4912"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.containsString("extensions=all")))
                .andRespond(withSuccess("""
                        {"status":"1","route":{"paths":[{"distance":"169000","duration":"8280","steps":[
                          {"polyline":"118.7969,32.0603;119.2000,31.9000"},
                          {"polyline":"119.2000,31.9000;120.3119,31.4912"}
                        ]}]}}
                        """, MediaType.APPLICATION_JSON));

        RoutePlan plan = new AmapTripRoutePlanner(builder, properties).plan("南京", "无锡", true);

        assertThat(plan.sourceMode()).isEqualTo("LIVE");
        assertThat(plan.coordinateSystem()).isEqualTo("GCJ-02");
        assertThat(plan.polyline()).hasSize(3);
        assertThat(plan.routeHash()).hasSize(64);
        server.verify();
    }
}
