package com.stokr.bootstrap.config;

import com.stokr.common.crypto.AesGcmFieldCipher;
import com.stokr.common.crypto.DevPlaintextFieldCipher;
import com.stokr.common.crypto.FieldCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.Base64;

@Configuration
public class FieldCipherConfiguration {

    @Bean
    public FieldCipher fieldCipher(@Value("${stokr.crypto.field-key:}") String base64Key) {
        if (!StringUtils.hasText(base64Key)) {
            return new DevPlaintextFieldCipher();
        }
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        return new AesGcmFieldCipher(raw);
    }
}
