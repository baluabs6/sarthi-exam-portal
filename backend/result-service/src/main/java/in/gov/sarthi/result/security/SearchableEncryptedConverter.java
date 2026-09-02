package in.gov.sarthi.result.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Applied to columns that need exact-match queries to keep working
 * against encrypted data (e.g. Grievance.rollNumber, so
 * findByRollNumberOrderByCreatedAtDesc still functions). See
 * PiiEncryptionService's class comment for the ECB tradeoff this makes.
 * autoApply=false — applied explicitly per-field via @Convert, so it's
 * obvious from the entity which columns are encrypted.
 */
@Converter(autoApply = false)
public class SearchableEncryptedConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return PiiEncryptionService.encryptDeterministic(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return PiiEncryptionService.decryptDeterministic(dbData);
    }
}
