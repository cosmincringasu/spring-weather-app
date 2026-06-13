package com.example.demo;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
public class WeatherController {

    private final RestTemplate restTemplate = new RestTemplate();

    @GetMapping("/api/weather")
    public WeatherResponse getWeather(HttpServletRequest request) {
        String ip = resolvePublicIp(request);

        Map<String, Object> geo = restTemplate.getForObject(
            "http://ip-api.com/json/" + ip, Map.class);

        double lat = ((Number) geo.get("lat")).doubleValue();
        double lon = ((Number) geo.get("lon")).doubleValue();
        String city = (String) geo.get("city");
        String country = (String) geo.get("country");

        Map<String, Object> weather = restTemplate.getForObject(
            String.format("https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s&current_weather=true", lat, lon),
            Map.class);

        Map<String, Object> current = (Map<String, Object>) weather.get("current_weather");
        double temperature = ((Number) current.get("temperature")).doubleValue();
        double windspeed  = ((Number) current.get("windspeed")).doubleValue();
        int code          = ((Number) current.get("weathercode")).intValue();

        return new WeatherResponse(city, country, temperature, windspeed, describeCode(code));
    }

    private String resolvePublicIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        ip = ip.split(",")[0].trim();

        // When running locally the request comes from loopback — fetch real public IP instead
        if (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")
                || ip.startsWith("192.168.") || ip.startsWith("10.")) {
            ip = restTemplate.getForObject("https://api.ipify.org?format=text", String.class);
        }
        return ip;
    }

    private String describeCode(int code) {
        if (code == 0)         return "Clear sky";
        if (code <= 2)         return "Partly cloudy";
        if (code == 3)         return "Overcast";
        if (code <= 49)        return "Foggy";
        if (code <= 59)        return "Drizzle";
        if (code <= 69)        return "Rain";
        if (code <= 79)        return "Snow";
        if (code <= 82)        return "Rain showers";
        if (code <= 86)        return "Snow showers";
        if (code <= 99)        return "Thunderstorm";
        return "Unknown";
    }
}
