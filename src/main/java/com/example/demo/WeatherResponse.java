package com.example.demo;

public record WeatherResponse(
    String city,
    String country,
    double temperature,
    double windspeed,
    String description
) {}
