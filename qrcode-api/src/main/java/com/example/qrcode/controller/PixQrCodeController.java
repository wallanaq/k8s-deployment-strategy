package com.example.qrcode.controller;

import com.example.qrcode.dto.PixQrCodeRequest;
import com.example.qrcode.dto.PixQrCodeResponse;
import com.example.qrcode.service.PixQrCodeService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/pix/qrcodes")
public class PixQrCodeController {

    private final PixQrCodeService service;

    public PixQrCodeController(PixQrCodeService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PixQrCodeResponse> create(@Valid @RequestBody PixQrCodeRequest request) {
        PixQrCodeResponse response = service.create(request);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PixQrCodeResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<PixQrCodeResponse> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(service.cancel(id));
    }
}
