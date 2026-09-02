package in.gov.sarthi.result.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Applied to free-text columns that are never queried by exact match
 * (grievance messages, feedback comments) — semantically secure
 * (AES-GCM, random IV per value) since there's no need to trade that off
 * for searchability the way SearchableEncryptedConverter does.
 */
@Converter(autoApply = false)
public class FreeTextEncryptedConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return PiiEncryptionService.encryptRandom(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return PiiEncryptionService.decryptRandom(dbData);
    }
}
