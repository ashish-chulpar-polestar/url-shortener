package com.urlshortener.controller;

import java.time.Instant;

public record HealthResponse(String status, Instant timestamp) {}
