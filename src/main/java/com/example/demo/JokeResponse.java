package com.example.demo;

public record JokeResponse(
    String setup,
    String punchline,
    String date
) {}
