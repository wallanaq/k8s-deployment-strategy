package com.example.qrcode.repository;

import com.example.qrcode.entity.PixQrCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PixQrCodeRepository extends JpaRepository<PixQrCode, UUID> {
}
