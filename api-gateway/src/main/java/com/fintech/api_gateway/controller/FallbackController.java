package com.fintech.api_gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FallbackController {

    @RequestMapping("/fallback/payment")
    public ResponseEntity<String> paymentFallback() {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("Payment service is currently unavailable");
    }

    @RequestMapping("/fallback/ledger")
    public ResponseEntity<String> ledgerFallback() {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("Ledger service is currently unavailable");
    }

    @RequestMapping("/fallback/identity")
    public ResponseEntity<String> identityFallback() {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("Identity service is currently unavailable");
    }

    @RequestMapping("/fallback/notification")
    public ResponseEntity<String> notificationFallback() {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("Notification service is currently unavailable");
    }
}